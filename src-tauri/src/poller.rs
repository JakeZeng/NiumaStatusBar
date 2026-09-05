use crate::logging::{AppLogger, LogCategory, LogLevel};
use crate::providers::{ProviderConfig, ProviderManager, UsageStatus};
use crate::storage::Database;
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::Mutex;
use std::time::Duration;
use tauri::{AppHandle, Emitter};
use tokio::sync::Notify;
use tokio::time;

/// 轮询调度器：为每个启用的 Provider 启动独立的异步轮询任务
pub struct Poller {
    manager: Arc<ProviderManager>,
    app_handle: AppHandle,
    db: Arc<Database>,
    /// provider_id -> 停止信号。spawn_poll 时插入，stop_poll 时 notify_waiters 终止 task。
    handles: Mutex<HashMap<String, Arc<Notify>>>,
    /// 可选的应用日志器；由 lib.rs setup 阶段通过 set_logger 注入
    logger: Mutex<Option<Arc<AppLogger>>>,
}

impl Poller {
    pub fn new(manager: Arc<ProviderManager>, app_handle: AppHandle, db: Arc<Database>) -> Self {
        Self {
            manager,
            app_handle,
            db,
            handles: Mutex::new(HashMap::new()),
            logger: Mutex::new(None),
        }
    }

    /// 注入日志器（lib.rs setup 阶段调用）。可重复调用覆盖。
    pub fn set_logger(&self, logger: Arc<AppLogger>) {
        *self.logger.lock().unwrap() = Some(logger);
    }

    fn logger_ref(&self) -> Option<Arc<AppLogger>> {
        self.logger.lock().unwrap().clone()
    }

    /// 启动所有 Provider 的轮询
    pub async fn start_all(&self) {
        // 清理旧历史数据
        if let Err(e) = self.db.cleanup_old_history() {
            if let Some(l) = self.logger_ref() {
                l.log(
                    LogLevel::Error,
                    LogCategory::Database,
                    Some("poller.start_all".into()),
                    "cleanup old history failed",
                    Some(serde_json::json!({ "error": e.to_string() })),
                );
            }
        }

        let providers = self.manager.get_providers().await;
        if let Some(l) = self.logger_ref() {
            l.log(
                LogLevel::Info,
                LogCategory::Poller,
                Some("poller.start_all".into()),
                "start_all enumerating providers",
                Some(serde_json::json!({
                    "total": providers.len(),
                    "enabled": providers.iter().filter(|p| p.is_enabled).count(),
                    "disabled": providers.iter().filter(|p| !p.is_enabled).count(),
                    "ids": providers.iter().map(|p| format!("{}:enabled={}", p.id, p.is_enabled)).collect::<Vec<_>>(),
                })),
            );
        }
        for provider in providers {
            if provider.is_enabled {
                self.spawn_poll(provider);
            } else if let Some(l) = self.logger_ref() {
                l.log(
                    LogLevel::Info,
                    LogCategory::Poller,
                    Some(provider.id.clone()),
                    "start_all skip disabled",
                    Some(
                        serde_json::json!({ "name": provider.name, "provider": provider.provider }),
                    ),
                );
            }
        }
    }

    /// 停止单个 Provider 的轮询任务（若存在）
    pub fn stop_poll(&self, provider_id: &str) {
        if let Some(notify) = self.handles.lock().unwrap().remove(provider_id) {
            notify.notify_waiters();
        }
    }

    /// 为单个 Provider 启动轮询。如已有同 id 的任务在跑，先停掉再启。
    pub fn spawn_poll(&self, provider: ProviderConfig) {
        self.stop_poll(&provider.id);

        let manager = self.manager.clone();
        let app_handle = self.app_handle.clone();
        let db = self.db.clone();
        let logger = self.logger_ref();
        let interval_secs = provider.refresh_interval.max(10);
        let stop = Arc::new(Notify::new());
        self.handles
            .lock()
            .unwrap()
            .insert(provider.id.clone(), stop.clone());

        tokio::spawn(async move {
            // 立即先跑一次，避免用户启用后要等一整个间隔才能看到数据
            Self::tick_once(&manager, &app_handle, &db, logger.as_ref(), &provider).await;

            let mut ticker = time::interval(Duration::from_secs(interval_secs));
            ticker.set_missed_tick_behavior(time::MissedTickBehavior::Delay);
            // 第一次 tick 立即返回，丢弃以与上面的 tick_once 对齐节奏
            ticker.tick().await;

            loop {
                tokio::select! {
                    _ = stop.notified() => break,
                    _ = ticker.tick() => {}
                }

                // 检查 Provider 是否仍然启用
                let current = manager.get_providers().await;
                let still_enabled = current.iter().any(|p| p.id == provider.id && p.is_enabled);
                if !still_enabled {
                    break;
                }

                Self::tick_once(&manager, &app_handle, &db, logger.as_ref(), &provider).await;
            }
        });
    }

    async fn tick_once(
        manager: &Arc<ProviderManager>,
        app_handle: &AppHandle,
        db: &Arc<Database>,
        logger: Option<&Arc<AppLogger>>,
        provider: &ProviderConfig,
    ) {
        if let Some(l) = logger {
            l.log(
                LogLevel::Info,
                LogCategory::Poller,
                Some(provider.id.clone()),
                "poll tick start",
                None,
            );
        }

        match Self::fetch_with_retry(manager, provider, logger, 3).await {
            Ok(status) => {
                let latency = status.avg_latency.unwrap_or(0);
                if let Err(e) = db.save_usage_history(&status) {
                    if let Some(l) = logger {
                        l.log(
                            LogLevel::Error,
                            LogCategory::Database,
                            Some(provider.id.clone()),
                            "save usage history failed",
                            Some(serde_json::json!({ "error": e.to_string() })),
                        );
                    }
                }
                manager
                    .update_status(provider.id.clone(), status.clone())
                    .await;
                let _ = app_handle.emit("status-update", &status);
                if let Some(l) = logger {
                    l.log(
                        LogLevel::Info,
                        LogCategory::Poller,
                        Some(provider.id.clone()),
                        "poll tick ok",
                        Some(serde_json::json!({ "latency_ms": latency })),
                    );
                }
            }
            Err(err) => {
                let error_status = UsageStatus {
                    provider_id: provider.id.clone(),
                    timestamp: chrono::Utc::now().timestamp(),
                    last_error: Some(err),
                    ..Default::default()
                };
                manager
                    .update_status(provider.id.clone(), error_status.clone())
                    .await;
                let _ = app_handle.emit("status-update", &error_status);
                if let Some(l) = logger {
                    l.log(
                        LogLevel::Error,
                        LogCategory::Poller,
                        Some(provider.id.clone()),
                        "poll tick failed after retries",
                        Some(serde_json::json!({
                            "attempts": 3,
                            "last_error": error_status.last_error,
                        })),
                    );
                }
            }
        }
    }

    /// 带指数退避重试的请求
    async fn fetch_with_retry(
        manager: &Arc<ProviderManager>,
        provider: &ProviderConfig,
        logger: Option<&Arc<AppLogger>>,
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
                    if let Some(l) = logger {
                        l.log(
                            LogLevel::Warn,
                            LogCategory::Poller,
                            Some(provider.id.clone()),
                            "retry",
                            Some(serde_json::json!({
                                "attempt": attempt + 1,
                                "delay_ms": delay.as_millis(),
                                "error": err,
                            })),
                        );
                    }
                    time::sleep(delay).await;
                    delay *= 2; // 指数退避
                }
            }
        }
        Err("重试次数耗尽".to_string())
    }
}
