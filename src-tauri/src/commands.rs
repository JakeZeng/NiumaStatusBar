use crate::catalog::{get_catalog, get_preset_by_id, ProviderPreset};
use crate::logging::{AppLogger, LogCategory, LogEntry, LogLevel, LogQuery};
use crate::poller::Poller;
use crate::providers::{ProviderConfig, ProviderManager, UsageStatus};
use crate::storage::Database;
use std::sync::Arc;
use tauri::{AppHandle, State, WebviewWindow};
use uuid::Uuid;

pub const CLOSE_ACTION_KEY: &str = "close_action";

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
