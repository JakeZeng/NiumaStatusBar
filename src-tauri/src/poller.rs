use crate::providers::{ProviderConfig, ProviderManager, UsageStatus};
use crate::storage::Database;
use std::sync::Arc;
use std::time::Duration;
use tauri::{AppHandle, Emitter};
use tokio::time;

/// 轮询调度器：为每个启用的 Provider 启动独立的异步轮询任务
pub struct Poller {
    manager: Arc<ProviderManager>,
    app_handle: AppHandle,
    db: Arc<Database>,
}

impl Poller {
    pub fn new(manager: Arc<ProviderManager>, app_handle: AppHandle, db: Arc<Database>) -> Self {
        Self { manager, app_handle, db }
    }

    /// 启动所有 Provider 的轮询
    pub async fn start_all(&self) {
        // 清理旧历史数据
        let _ = self.db.cleanup_old_history();
        
        let providers = self.manager.get_providers().await;
        for provider in providers {
            if provider.is_enabled {
                self.spawn_poll(provider);
            }
        }
    }

    /// 为单个 Provider 启动轮询
    pub fn spawn_poll(&self, provider: ProviderConfig) {
        let manager = self.manager.clone();
        let app_handle = self.app_handle.clone();
        let db = self.db.clone();
        let interval_secs = provider.refresh_interval.max(10); // 最小 10 秒

        tokio::spawn(async move {
            let mut ticker = time::interval(Duration::from_secs(interval_secs));

            loop {
                ticker.tick().await;

                // 检查 Provider 是否仍然启用
                let current = manager.get_providers().await;
                let still_enabled = current
                    .iter()
                    .any(|p| p.id == provider.id && p.is_enabled);
                if !still_enabled {
                    break;
                }

                let result = Self::fetch_with_retry(&manager, &provider, 3).await;

                match result {
                    Ok(status) => {
                        // 保存到历史记录
                        let _ = db.save_usage_history(&status);
                        // 更新内存中的状态
                        manager.update_status(provider.id.clone(), status.clone()).await;
                        // 通知前端
                        let _ = app_handle.emit("status-update", &status);
                    }
                    Err(err) => {
                        let error_status = UsageStatus {
                            provider_id: provider.id.clone(),
                            timestamp: chrono::Utc::now().timestamp(),
                            last_error: Some(err),
                            ..Default::default()
                        };
                        manager.update_status(provider.id.clone(), error_status.clone()).await;
                        let _ = app_handle.emit("status-update", &error_status);
                    }
                }
            }
        });
    }

    /// 带指数退避重试的请求
    async fn fetch_with_retry(
        manager: &Arc<ProviderManager>,
        provider: &ProviderConfig,
        max_retries: u32,
    ) -> Result<UsageStatus, String> {
        let mut delay = Duration::from_secs(1);

        for attempt in 0..max_retries {
            match manager.fetch_usage(provider).await {
                Ok(status) => return Ok(status),
                Err(err) => {
                    if attempt == max_retries - 1 {
                        return Err(format!("重试 {} 次后仍失败: {}", max_retries, err));
                    }
                    time::sleep(delay).await;
                    delay *= 2; // 指数退避
                }
            }
        }
        Err("重试次数耗尽".to_string())
    }
}
