use crate::catalog::{get_catalog, get_preset_by_id, ProviderPreset};
use crate::logging::{AppLogger, LogCategory, LogEntry, LogLevel, LogQuery};
use crate::poller::Poller;
use crate::providers::{ProviderConfig, ProviderManager, UsageStatus};
use crate::storage::Database;
use std::sync::Arc;
use tauri::{AppHandle, State, WebviewWindow};
use uuid::Uuid;

pub const CLOSE_ACTION_KEY: &str = "close_action";
/// settings 表里保存当前 App 主题的 key（cyberpunk / wuxia / guoman）。
/// Android 桌面组件进程不加载 WebView，直接读这张表给 widget 配色。
pub const APP_THEME_KEY: &str = "app_theme";
/// settings 表里保存"托盘图标是否可见"的 key（"1" / "0"）。
/// 默认值由前端在 settings 表无记录时假设为 true（参见 get_tray_visible）。
pub const TRAY_VISIBLE_KEY: &str = "tray_visible";
/// settings 表里保存"开机自启"偏好的 key（"1" / "0"）。仅桌面端有意义。
pub const AUTOSTART_KEY: &str = "autostart";

/// 关闭行为偏好的内存缓存类型（由 lib.rs 注入），供 `on_window_event` 同步读取
pub type CloseActionCache = Arc<std::sync::RwLock<Option<String>>>;

/// 命令入口埋点（Info, Command）—— 所有命令统一调用一次。
/// 调用者传入命令名（与 Rust 函数名一致，便于跟踪）。
fn log_command_entry(
    logger: &AppLogger,
    name: &str,
    details: Option<serde_json::Value>,
) {
    logger.log(
        LogLevel::Info,
        LogCategory::Command,
        Some(name.to_string()),
        "command invoked",
        details,
    );
}

/// 错误出口埋点（Error, Command）。被 `.map_err(|e| { ...; e.to_string() })?` 替代原有写法。
fn log_command_error(
    logger: &AppLogger,
    name: &str,
    err: impl ToString,
    details: Option<serde_json::Value>,
) -> String {
    let msg = err.to_string();
    let details_final = match details {
        Some(mut d) => {
            if let Some(obj) = d.as_object_mut() {
                obj.insert("error".to_string(), serde_json::Value::String(msg.clone()));
            }
            Some(d)
        }
        None => Some(serde_json::json!({ "error": msg.clone() })),
    };
    logger.log(
        LogLevel::Error,
        LogCategory::Command,
        Some(name.to_string()),
        "command failed",
        details_final,
    );
    msg
}

#[tauri::command]
pub async fn get_providers(
    manager: State<'_, Arc<ProviderManager>>,
) -> Result<Vec<ProviderConfig>, String> {
    Ok(manager.get_providers().await)
}

#[tauri::command]
pub async fn add_provider(
    provider: ProviderConfig,
    manager: State<'_, Arc<ProviderManager>>,
    db: State<'_, Arc<Database>>,
    poller: State<'_, Arc<Poller>>,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<(), String> {
    log_command_entry(
        &logger,
        "add_provider",
        Some(serde_json::json!({
            "provider_id": provider.id,
            "name": provider.name,
            // api_key 故意不入 details
        })),
    );
    db.save_provider(&provider)
        .map_err(|e| log_command_error(&logger, "add_provider", e, None))?;
    manager.add_provider(provider.clone()).await;
    if provider.is_enabled {
        poller.spawn_poll(provider);
    }
    Ok(())
}

#[tauri::command]
pub async fn update_provider(
    id: String,
    provider: ProviderConfig,
    manager: State<'_, Arc<ProviderManager>>,
    db: State<'_, Arc<Database>>,
    poller: State<'_, Arc<Poller>>,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<(), String> {
    log_command_entry(
        &logger,
        "update_provider",
        Some(serde_json::json!({
            "id": id,
            "name": provider.name,
        })),
    );
    db.save_provider(&provider)
        .map_err(|e| log_command_error(&logger, "update_provider", e, None))?;
    manager.update_provider(id.clone(), provider.clone()).await;
    if provider.is_enabled {
        poller.spawn_poll(provider);
    } else {
        poller.stop_poll(&id);
    }
    Ok(())
}

#[tauri::command]
pub async fn delete_provider(
    id: String,
    manager: State<'_, Arc<ProviderManager>>,
    db: State<'_, Arc<Database>>,
    poller: State<'_, Arc<Poller>>,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<(), String> {
    log_command_entry(&logger, "delete_provider", Some(serde_json::json!({ "id": id })));
    db.delete_provider(&id)
        .map_err(|e| log_command_error(&logger, "delete_provider", e, None))?;
    manager.delete_provider(id.clone()).await;
    poller.stop_poll(&id);
    Ok(())
}

#[tauri::command]
pub async fn fetch_provider_status(
    id: String,
    manager: State<'_, Arc<ProviderManager>>,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<UsageStatus, String> {
    log_command_entry(&logger, "fetch_provider_status", Some(serde_json::json!({ "id": id })));
    let providers = manager.get_providers().await;
    let provider = providers
        .iter()
        .find(|p| p.id == id)
        .ok_or_else(|| {
            log_command_error(
                &logger,
                "fetch_provider_status",
                "Provider not found",
                Some(serde_json::json!({ "id": id })),
            );
            "Provider not found".to_string()
        })?;
    manager.fetch_usage(provider).await.map_err(|e| {
        log_command_error(
            &logger,
            "fetch_provider_status",
            e,
            Some(serde_json::json!({ "id": id })),
        )
    })
}

#[tauri::command]
pub async fn get_provider_status(
    id: String,
    manager: State<'_, Arc<ProviderManager>>,
) -> Result<Option<UsageStatus>, String> {
    Ok(manager.get_status(&id).await)
}

#[tauri::command]
pub async fn get_usage_history(
    provider_id: String,
    limit: Option<i64>,
    since: Option<i64>,
    db: State<'_, Arc<Database>>,
) -> Result<Vec<UsageStatus>, String> {
    db.get_usage_history(&provider_id, limit.unwrap_or(50), since)
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn export_config(
    db: State<'_, Arc<Database>>,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<String, String> {
    log_command_entry(&logger, "export_config", None);
    let providers = db
        .load_providers()
        .map_err(|e| log_command_error(&logger, "export_config", e, None))?;
    let export = serde_json::json!({
        "version": "1.0",
        "exported_at": chrono::Utc::now().timestamp(),
        "providers": providers,
    });
    serde_json::to_string_pretty(&export)
        .map_err(|e| log_command_error(&logger, "export_config", e, None))
}

#[tauri::command]
pub async fn import_config(
    json: String,
    manager: State<'_, Arc<ProviderManager>>,
    db: State<'_, Arc<Database>>,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<Vec<ProviderConfig>, String> {
    log_command_entry(&logger, "import_config", None);
    let parsed: serde_json::Value = serde_json::from_str(&json)
        .map_err(|e| log_command_error(&logger, "import_config", e, None))?;
    let providers: Vec<ProviderConfig> = serde_json::from_value(parsed["providers"].clone())
        .map_err(|e| log_command_error(&logger, "import_config", e, None))?;

    for provider in &providers {
        db.save_provider(provider)
            .map_err(|e| log_command_error(&logger, "import_config", e, None))?;
    }

    manager.set_providers(providers.clone()).await;
    Ok(providers)
}

// ============ 新增：ccSwitch 风格的供应商管理 ============

/// 获取预置目录（不包含 API Key）
#[tauri::command]
pub async fn get_provider_catalog() -> Result<Vec<ProviderPreset>, String> {
    Ok(get_catalog())
}

/// 通过预置 ID 启用一个 Provider（需用户填入 API Key）
/// 返回新创建的 ProviderConfig
#[tauri::command]
pub async fn enable_preset(
    preset_id: String,
    api_key: String,
    custom_name: Option<String>,
    refresh_interval: Option<u64>,
    manager: State<'_, Arc<ProviderManager>>,
    db: State<'_, Arc<Database>>,
    poller: State<'_, Arc<Poller>>,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<ProviderConfig, String> {
    // 注意：details 中故意不附 api_key
    log_command_entry(
        &logger,
        "enable_preset",
        Some(serde_json::json!({
            "preset_id": preset_id,
            "custom_name": custom_name,
            "refresh_interval": refresh_interval,
        })),
    );
    let preset = get_preset_by_id(&preset_id).ok_or_else(|| {
        log_command_error(
            &logger,
            "enable_preset",
            format!("Preset '{}' not found", preset_id),
            Some(serde_json::json!({ "preset_id": preset_id })),
        );
        format!("Preset '{}' not found", preset_id)
    })?;

    let provider = ProviderConfig {
        id: Uuid::new_v4().to_string(),
        name: custom_name.unwrap_or_else(|| preset.name.clone()),
        provider: preset.provider_type.clone(),
        base_url: preset.base_url.clone(),
        api_key,
        query_endpoint: preset.query_endpoint.clone(),
        query_method: preset.query_method.clone(),
        query_headers: preset.default_headers.clone(),
        // 把 model 作为 query_params 注入，便于"火山方舟"读取
        // 也方便用户在自定义 modal 里直接修改
        query_params: preset
            .default_model
            .as_ref()
            .map(|m| serde_json::json!({ "model": m }))
            .unwrap_or(serde_json::Value::Null),
        refresh_interval: refresh_interval.unwrap_or(preset.default_refresh_interval),
        is_enabled: true,
        status: "active".to_string(),
    };

    db.save_provider(&provider)
        .map_err(|e| log_command_error(&logger, "enable_preset", e, None))?;
    manager.add_provider(provider.clone()).await;
    poller.spawn_poll(provider.clone());
    Ok(provider)
}

/// 启用/禁用 Provider（不影响数据）
#[tauri::command]
pub async fn toggle_provider(
    id: String,
    enabled: bool,
    manager: State<'_, Arc<ProviderManager>>,
    db: State<'_, Arc<Database>>,
    poller: State<'_, Arc<Poller>>,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<(), String> {
    log_command_entry(
        &logger,
        "toggle_provider",
        Some(serde_json::json!({ "id": id, "enabled": enabled })),
    );
    let mut providers = manager.get_providers().await;
    let provider = providers.iter_mut().find(|p| p.id == id).ok_or_else(|| {
        log_command_error(
            &logger,
            "toggle_provider",
            "Provider not found",
            Some(serde_json::json!({ "id": id })),
        );
        "Provider not found".to_string()
    })?;

    provider.is_enabled = enabled;
    let updated = provider.clone();
    db.save_provider(&updated)
        .map_err(|e| log_command_error(&logger, "toggle_provider", e, None))?;
    manager.update_provider(id.clone(), updated.clone()).await;
    if enabled {
        poller.spawn_poll(updated);
    } else {
        poller.stop_poll(&id);
    }
    Ok(())
}

/// 查询已启用的预置 ID 列表（用于前端判断哪些已添加）
#[tauri::command]
pub async fn get_active_preset_ids(
    manager: State<'_, Arc<ProviderManager>>,
) -> Result<Vec<String>, String> {
    let providers = manager.get_providers().await;
    // 通过 provider_type 反推可能的预置 ID
    let active_types: Vec<String> = providers.iter().map(|p| p.provider.clone()).collect();
    Ok(active_types)
}

// ============ 关闭行为偏好 + 窗口控制 ============

/// 获取关闭按钮偏好：`"minimize_to_tray"` / `"exit"` / `None`（每次询问）
#[tauri::command]
pub async fn get_close_action(db: State<'_, Arc<Database>>) -> Result<Option<String>, String> {
    db.get_setting(CLOSE_ACTION_KEY).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn set_close_action(
    action: String,
    db: State<'_, Arc<Database>>,
    cache: State<'_, CloseActionCache>,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<(), String> {
    log_command_entry(
        &logger,
        "set_close_action",
        Some(serde_json::json!({ "action": action })),
    );
    if action != "minimize_to_tray" && action != "exit" {
        let msg = format!("invalid close_action: {}", action);
        log_command_error(&logger, "set_close_action", &msg, None);
        return Err(msg);
    }
    db.set_setting(CLOSE_ACTION_KEY, &action)
        .map_err(|e| log_command_error(&logger, "set_close_action", e, None))?;
    if let Ok(mut guard) = cache.write() {
        *guard = Some(action);
    }
    Ok(())
}

#[tauri::command]
pub async fn reset_close_action(
    db: State<'_, Arc<Database>>,
    cache: State<'_, CloseActionCache>,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<(), String> {
    log_command_entry(&logger, "reset_close_action", None);
    db.delete_setting(CLOSE_ACTION_KEY)
        .map_err(|e| log_command_error(&logger, "reset_close_action", e, None))?;
    if let Ok(mut guard) = cache.write() {
        *guard = None;
    }
    Ok(())
}

#[tauri::command]
pub async fn window_hide_to_tray(
    window: WebviewWindow,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<(), String> {
    log_command_entry(&logger, "window_hide_to_tray", None);
    window
        .hide()
        .map_err(|e| log_command_error(&logger, "window_hide_to_tray", e, None))
}

#[tauri::command]
pub async fn app_quit(
    app: AppHandle,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<(), String> {
    log_command_entry(&logger, "app_quit", None);
    app.exit(0);
    Ok(())
}

// ============ 托盘可见性 / 开机自启 ============
//
// 桌面端实现：偏好持久化到 settings 表 + 同步操作系统层。
// 移动端（Android/iOS）保留同名命令签名（前端 `isDesktopPlatform()` 已加，
// 但也提供 stub 实现保证后端 invoke_handler 列表在所有平台都有效）。
//
// `tray_visible` 通过全局静态 RwLock 缓存避免每次读取都打 SQLite。
// `autostart` 在 Windows 上操作 HKCU\Software\Microsoft\Windows\CurrentVersion\Run，
// Linux/macOS 仅持久化偏好（避免引入 platform-specific crate）。

#[cfg(desktop)]
mod tray_autostart_impl {
    use super::{Database, AUTOSTART_KEY, TRAY_VISIBLE_KEY};
    use std::sync::{OnceLock, RwLock};

    // 进程级缓存：OnceLock + RwLock<Option<bool>> 表达"未初始化 / 缓存值"
    #[allow(dead_code)] // 通过 cache() 函数间接被外部命令引用
    static TRAY_VISIBLE_CACHE: OnceLock<RwLock<Option<bool>>> = OnceLock::new();
    #[allow(dead_code)]
    static AUTOSTART_CACHE: OnceLock<RwLock<Option<bool>>> = OnceLock::new();

    pub(super) fn tray_visible_cache() -> &'static RwLock<Option<bool>> {
        TRAY_VISIBLE_CACHE.get_or_init(|| RwLock::new(None))
    }
    pub(super) fn autostart_cache() -> &'static RwLock<Option<bool>> {
        AUTOSTART_CACHE.get_or_init(|| RwLock::new(None))
    }

    pub(super) fn read_tray_visible(db: &Database) -> Result<bool, String> {
        if let Some(guard) = tray_visible_cache().read().ok() {
            if let Some(v) = *guard {
                return Ok(v);
            }
        }
        let raw = db.get_setting(TRAY_VISIBLE_KEY).map_err(|e| e.to_string())?;
        let v = match raw.as_deref() {
            Some("0") => false,
            _ => true, // None 或 "1" 都视为可见
        };
        if let Ok(mut guard) = tray_visible_cache().write() {
            *guard = Some(v);
        }
        Ok(v)
    }

    pub(super) fn read_autostart(db: &Database) -> Result<bool, String> {
        if let Some(guard) = autostart_cache().read().ok() {
            if let Some(v) = *guard {
                return Ok(v);
            }
        }
        let raw = db.get_setting(AUTOSTART_KEY).map_err(|e| e.to_string())?;
        let v = matches!(raw.as_deref(), Some("1"));
        if let Ok(mut guard) = autostart_cache().write() {
            *guard = Some(v);
        }
        Ok(v)
    }
}

#[tauri::command]
pub async fn get_tray_visible(
    db: State<'_, Arc<Database>>,
) -> Result<bool, String> {
    // 桌面端读偏好；移动端直接返回 true（tray 不可用时该值无意义）
    #[cfg(desktop)]
    { tray_autostart_impl::read_tray_visible(&db) }
    #[cfg(not(desktop))]
    { let _ = db; Ok(true) }
}

#[tauri::command]
pub async fn set_tray_visible(
    visible: bool,
    db: State<'_, Arc<Database>>,
    app: AppHandle,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<(), String> {
    log_command_entry(
        &logger,
        "set_tray_visible",
        Some(serde_json::json!({ "visible": visible })),
    );
    let raw = if visible { "1" } else { "0" };
    db.set_setting(TRAY_VISIBLE_KEY, raw)
        .map_err(|e| log_command_error(&logger, "set_tray_visible", e, None))?;
    #[cfg(desktop)]
    if let Ok(mut guard) = tray_autostart_impl::tray_visible_cache().write() {
        *guard = Some(visible);
    }
    #[cfg(desktop)]
    crate::tray::apply_tray_visibility(&app, visible);
    #[cfg(not(desktop))]
    { let _ = (&app, visible); }
    Ok(())
}

#[tauri::command]
pub async fn get_autostart(
    db: State<'_, Arc<Database>>,
) -> Result<bool, String> {
    #[cfg(desktop)]
    { tray_autostart_impl::read_autostart(&db) }
    #[cfg(not(desktop))]
    { let _ = db; Ok(false) }
}

#[tauri::command]
pub async fn set_autostart(
    enabled: bool,
    db: State<'_, Arc<Database>>,
    _app: AppHandle,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<(), String> {
    log_command_entry(
        &logger,
        "set_autostart",
        Some(serde_json::json!({ "enabled": enabled })),
    );
    let raw = if enabled { "1" } else { "0" };
    db.set_setting(AUTOSTART_KEY, raw)
        .map_err(|e| log_command_error(&logger, "set_autostart", e, None))?;
    #[cfg(desktop)]
    if let Ok(mut guard) = tray_autostart_impl::autostart_cache().write() {
        *guard = Some(enabled);
    }
    // Windows：同步操作注册表
    #[cfg(all(desktop, target_os = "windows"))]
    {
        apply_windows_autostart(enabled).map_err(|e| {
            log_command_error(&logger, "set_autostart", &e, None)
        })?;
    }
    // Linux/macOS：偏好已持久化，应用启动时由安装包/desktop 文件决定
    let _ = (_app, enabled);
    Ok(())
}

#[cfg(all(desktop, target_os = "windows"))]
fn apply_windows_autostart(enabled: bool) -> Result<(), String> {
    use std::process::Command;
    // 用 reg.exe 读写 HKCU，避免引入 winreg 依赖（构建矩阵更轻）
    // Run 键值名：固定为 exe 文件名（不含扩展名），保证可被后续 reg delete 命中
    let exe = std::env::current_exe()
        .map_err(|e| format!("current_exe: {}", e))?;
    let value_name = exe
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("ai-model-monitor")
        .to_string();
    let exe_str = exe.to_string_lossy().to_string();
    let key = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
    let output = if enabled {
        Command::new("reg")
            .args(["add", key, "/v", &value_name, "/t", "REG_SZ", "/d", &exe_str, "/f"])
            .output()
    } else {
        Command::new("reg")
            .args(["delete", key, "/v", &value_name, "/f"])
            .output()
    };
    match output {
        Ok(o) if o.status.success() => Ok(()),
        Ok(o) => {
            // reg delete 在 key 不存在时返回非零，禁用时容忍这种情况
            if !enabled {
                let stderr = String::from_utf8_lossy(&o.stderr).to_string();
                if stderr.contains("unable to find") {
                    return Ok(());
                }
            }
            Err(format!(
                "reg {}: {}",
                if enabled { "add" } else { "delete" },
                String::from_utf8_lossy(&o.stderr).trim()
            ))
        }
        Err(e) => Err(format!("spawn reg: {}", e)),
    }
}

// ============ 主题偏好（供 Android 桌面组件读取） ============

/// 前端切换主题时把主题 id 持久化到 settings 表。
/// Android widget 进程（不加载 WebView / localStorage）直读该值给卡片配色。
#[tauri::command]
pub async fn set_app_theme(
    theme: String,
    db: State<'_, Arc<Database>>,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<(), String> {
    log_command_entry(
        &logger,
        "set_app_theme",
        Some(serde_json::json!({ "theme": theme })),
    );
    if !matches!(theme.as_str(), "cyberpunk" | "wuxia" | "guoman") {
        let msg = format!("invalid theme: {}", theme);
        log_command_error(&logger, "set_app_theme", &msg, None);
        return Err(msg);
    }
    db.set_setting(APP_THEME_KEY, &theme)
        .map_err(|e| log_command_error(&logger, "set_app_theme", e, None))?;
    Ok(())
}

// ============ 日志查询 / 清空 ============

#[tauri::command]
pub async fn query_logs(
    q: LogQuery,
    logger: State<'_, Arc<AppLogger>>,
) -> Result<Vec<LogEntry>, String> {
    logger
        .query(q)
        .map_err(|e| log_command_error(&logger, "query_logs", e, None))
}

#[tauri::command]
pub async fn clear_logs(logger: State<'_, Arc<AppLogger>>) -> Result<(), String> {
    log_command_entry(&logger, "clear_logs", None);
    logger
        .clear()
        .map_err(|e| log_command_error(&logger, "clear_logs", e, None))?;
    Ok(())
}
