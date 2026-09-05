// 桌面端系统托盘 + 用量轮播 tooltip
//
// - 左键单击图标 → 显示主窗口
// - 右键菜单：显示主窗口 / 退出
// - 每 3 秒切换 tooltip 到下一个已启用供应商，附带余额、5h/周/月 额度

#![cfg(desktop)]

use crate::logging::{AppLogger, LogCategory, LogLevel};
use crate::providers::{ProviderConfig, ProviderManager, UsageStatus};
use std::sync::Arc;
use std::time::Duration;
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Manager, Runtime,
};

const TRAY_ID: &str = "main-tray";
const ROTATE_INTERVAL_SECS: u64 = 3;

pub fn setup_tray<R: Runtime>(
    app: &AppHandle<R>,
    manager: Arc<ProviderManager>,
    logger: Arc<AppLogger>,
) -> tauri::Result<()> {
    let show_item = MenuItem::with_id(app, "tray-show", "显示主窗口", true, None::<&str>)?;
    let quit_item = MenuItem::with_id(app, "tray-quit", "退出", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show_item, &quit_item])?;

    let icon = app
        .default_window_icon()
        .cloned()
        .ok_or_else(|| tauri::Error::AssetNotFound("default window icon".into()))?;

    let logger_for_menu = logger.clone();
    TrayIconBuilder::with_id(TRAY_ID)
        .icon(icon)
        .tooltip("粮草用量｜加载中…")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(move |app, event| match event.id.as_ref() {
            "tray-show" => {
                logger_for_menu.log(
                    LogLevel::Info,
                    LogCategory::Tray,
                    Some("tray-show".into()),
                    "menu click: show",
                    None,
                );
                show_main_window(app);
            }
            "tray-quit" => {
                logger_for_menu.log(
                    LogLevel::Info,
                    LogCategory::Tray,
                    Some("tray-quit".into()),
                    "menu click: quit",
                    None,
                );
                app.exit(0);
            }
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main_window(tray.app_handle());
            }
        })
        .build(app)?;

    spawn_tooltip_rotator(app.clone(), manager, logger);
    Ok(())
}

fn show_main_window<R: Runtime>(app: &AppHandle<R>) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// 按偏好显隐托盘图标。Tauri 2 `TrayIcon::set_visible(bool)` 在显/隐间切换。
/// 启动时按 settings 表里的偏好应用；用户切换后由 `set_tray_visible` 命令调用。
pub fn apply_tray_visibility<R: Runtime>(app: &AppHandle<R>, visible: bool) {
    if let Some(tray) = app.tray_by_id(TRAY_ID) {
        let _ = tray.set_visible(visible);
    }
}

fn spawn_tooltip_rotator<R: Runtime>(
    app: AppHandle<R>,
    manager: Arc<ProviderManager>,
    logger: Arc<AppLogger>,
) {
    tauri::async_runtime::spawn(async move {
        let mut idx: usize = 0;
        let mut ticker = tokio::time::interval(Duration::from_secs(ROTATE_INTERVAL_SECS));
        loop {
            ticker.tick().await;
            let Some(tray) = app.tray_by_id(TRAY_ID) else {
                continue;
            };

            let enabled: Vec<ProviderConfig> = manager
                .get_providers()
                .await
                .into_iter()
                .filter(|p| p.is_enabled)
                .collect();

            let text = if enabled.is_empty() {
                "粮草用量｜暂无启用供应商".to_string()
            } else {
                let provider = &enabled[idx % enabled.len()];
                idx = idx.wrapping_add(1);
                let status = manager.get_status(&provider.id).await;
                format_tooltip(provider, status.as_ref())
            };

            if let Err(e) = tray.set_tooltip(Some(&text)) {
                logger.log(
                    LogLevel::Warn,
                    LogCategory::Tray,
                    Some("tooltip".into()),
                    "tooltip set failed",
                    Some(serde_json::json!({ "error": e.to_string() })),
                );
            }
        }
    });
}

fn format_tooltip(provider: &ProviderConfig, status: Option<&UsageStatus>) -> String {
    let mut lines: Vec<String> = Vec::with_capacity(4);
    lines.push(format!("[{}]", provider.name));

    let Some(s) = status else {
        lines.push("等待首次数据…".to_string());
        return lines.join("\n");
    };

    if let Some(err) = &s.last_error {
        lines.push(format!("⚠ {}", truncate(err, 60)));
        return lines.join("\n");
    }

    if let (Some(used), Some(limit)) = (s.balance_used, s.balance_limit) {
        lines.push(format!("余额 {:.2} / {:.2}", used, limit));
    } else if let Some(bal) = s.balance {
        lines.push(format!("余额 {:.2}", bal));
    }

    if let Some(p) = s.quota_5h_remaining_percent {
        lines.push(format!("5h 剩余 {}%", p.round() as i64));
    } else if let (Some(used), Some(total)) = (s.quota_5h_used, s.quota_5h_total) {
        lines.push(format!("5h {} / {}", used as i64, total as i64));
    }
    if let Some(ts) = s.quota_5h_reset_at {
        if let Some(text) = format_relative_reset(ts) {
            lines.push(format!("  ↻ {}", text));
        }
    }
    if let Some(p) = s.quota_week_remaining_percent {
        lines.push(format!("周剩余 {}%", p.round() as i64));
    } else if let (Some(used), Some(total)) = (s.quota_week_used, s.quota_week_total) {
        lines.push(format!("周 {} / {}", used as i64, total as i64));
    }
    if let Some(ts) = s.quota_week_reset_at {
        if let Some(text) = format_relative_reset(ts) {
            lines.push(format!("  ↻ {}", text));
        }
    }
    if let (Some(used), Some(total)) = (s.quota_month_used, s.quota_month_total) {
        lines.push(format!("月 {} / {}", used as i64, total as i64));
    }
    if let Some(ts) = s.quota_month_reset_at {
        if let Some(text) = format_relative_reset(ts) {
            lines.push(format!("  ↻ {}", text));
        }
    }

    if lines.len() == 1 {
        lines.push("暂无用量数据".to_string());
    }
    lines.join("\n")
}

fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() > max {
        let mut t: String = s.chars().take(max).collect();
        t.push('…');
        t
    } else {
        s.to_string()
    }
}

/// 把 unix 秒重置时间格式化为 "Xh Ym 后重置" / "Xm 后重置" / "已重置"。
/// 已过期或距今 ≤ 60 秒返回 None，由调用方决定是否展示。
fn format_relative_reset(reset_at_unix_sec: i64) -> Option<String> {
    let now = chrono::Utc::now().timestamp();
    let diff = reset_at_unix_sec - now;
    if diff <= 60 {
        return None;
    }

    let days = diff / 86400;
    let hours = (diff % 86400) / 3600;
    let minutes = (diff % 3600) / 60;

    if days > 0 {
        Some(if hours > 0 {
            format!("{}d{}h 后重置", days, hours)
        } else {
            format!("{}d 后重置", days)
        })
    } else if hours > 0 {
        Some(if minutes > 0 {
            format!("{}h{}m 后重置", hours, minutes)
        } else {
            format!("{}h 后重置", hours)
        })
    } else {
        Some(format!("{}m 后重置", minutes))
    }
}
