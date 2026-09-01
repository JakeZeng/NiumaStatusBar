//! 桌面小组件（Android App Widget）共享数据快照
//!
//! 每次轮询完成后把「当前配置的所有供应商 + 最新用量」写成一个 JSON 文件，
//! 放在 app 数据目录下。Android 端的原生 AppWidgetProvider / Service 直接读取
//! 这个文件渲染，无需跨进程调用 Tauri 命令，且应用被杀后文件仍存在（显示最后已知数据）。
//!
//! 路径：`{app_data_dir}/ai-model-monitor/widget_snapshot.json`
//! Kotlin 端会从 `Context.getFilesDir()`（以及 `getExternalFilesDir`）下同一子路径读取，
//! 与 Tauri 的 `app_data_dir()` 保持一致。

use crate::providers::ProviderManager;
use crate::storage::Database;
use serde::Serialize;
use std::io::Write;
use tauri::Manager;

/// 单个供应商在小部件中的精简快照（camelCase 以便 Kotlin 直接按字段名读取）
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct WidgetProviderSnapshot {
    id: String,
    name: String,
    provider: String,
    /// 余额（未取到则为 null）
    balance: Option<f64>,
    balance_used: Option<f64>,
    balance_limit: Option<f64>,
    /// 币种 ISO 4217，如 CNY / USD
    currency: Option<String>,
    /// 5 小时窗口额度
    quota5h_remaining: Option<f64>,
    quota5h_total: Option<f64>,
    quota5h_remaining_percent: Option<f64>,
    /// 周窗口额度
    quota_week_remaining: Option<f64>,
    quota_week_total: Option<f64>,
    quota_week_remaining_percent: Option<f64>,
    /// 月窗口额度
    quota_month_remaining: Option<f64>,
    quota_month_total: Option<f64>,
    quota_month_remaining_percent: Option<f64>,
    /// 是否处于错误状态
    has_error: bool,
    /// 错误信息（截断，避免过长）
    last_error: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct WidgetSnapshot {
    /// 快照生成时间（unix 秒）
    updated_at: i64,
    providers: Vec<WidgetProviderSnapshot>,
}

/// 把当前所有供应商的最新状态写成 widget_snapshot.json。
/// 任何失败都静默忽略——小组件数据缺失不影响主程序运行。
pub async fn write_snapshot(
    app_handle: &tauri::AppHandle,
    manager: &ProviderManager,
    _db: &Database,
) {
    let data_dir = match app_handle.path().app_data_dir() {
        Ok(d) => d,
        Err(_) => return,
    };

    let providers = manager.get_providers().await;
    let mut out = Vec::with_capacity(providers.len());
    for p in &providers {
        let st = manager.get_status(&p.id).await;
        let snap = match &st {
            Some(s) => WidgetProviderSnapshot {
                id: p.id.clone(),
                name: p.name.clone(),
                provider: p.provider.clone(),
                balance: s.balance,
                balance_used: s.balance_used,
                balance_limit: s.balance_limit,
                currency: s.currency.clone(),
                quota5h_remaining: s.quota_5h_remaining,
                quota5h_total: s.quota_5h_total,
                quota5h_remaining_percent: s.quota_5h_remaining_percent,
                quota_week_remaining: s.quota_week_remaining,
                quota_week_total: s.quota_week_total,
                quota_week_remaining_percent: s.quota_week_remaining_percent,
                quota_month_remaining: s.quota_month_remaining,
                quota_month_total: s.quota_month_total,
                quota_month_remaining_percent: s.quota_month_remaining_percent,
                has_error: s.last_error.is_some(),
                last_error: s
                    .last_error
                    .as_ref()
                    .map(|e| truncate(e, 120)),
            },
            None => WidgetProviderSnapshot {
                id: p.id.clone(),
                name: p.name.clone(),
                provider: p.provider.clone(),
                balance: None,
                balance_used: None,
                balance_limit: None,
                currency: None,
                quota5h_remaining: None,
                quota5h_total: None,
                quota5h_remaining_percent: None,
                quota_week_remaining: None,
                quota_week_total: None,
                quota_week_remaining_percent: None,
                quota_month_remaining: None,
                quota_month_total: None,
                quota_month_remaining_percent: None,
                has_error: false,
                last_error: None,
            },
        };
        out.push(snap);
    }

    let snapshot = WidgetSnapshot {
        updated_at: chrono::Utc::now().timestamp(),
        providers: out,
    };
    let json = match serde_json::to_string_pretty(&snapshot) {
        Ok(j) => j,
        Err(_) => return,
    };

    let dir = data_dir.join("ai-model-monitor");
    if std::fs::create_dir_all(&dir).is_err() {
        return;
    }
    let path = dir.join("widget_snapshot.json");
    // 先写临时文件再 rename，保证小组件读到的一定是完整 JSON
    let tmp = dir.join("widget_snapshot.json.tmp");
    if let Ok(mut f) = std::fs::File::create(&tmp) {
        if f.write_all(json.as_bytes()).is_ok() && f.flush().is_ok() {
            let _ = std::fs::rename(&tmp, &path);
        } else {
            let _ = std::fs::remove_file(&tmp);
        }
    }
}

fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() > max {
        let mut t: String = s.chars().take(max).collect();
        t.push_str("…");
        t
    } else {
        s.to_string()
    }
}
