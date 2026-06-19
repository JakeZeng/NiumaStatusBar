use crate::catalog::{get_catalog, get_preset_by_id, ProviderPreset};
use crate::providers::{ProviderConfig, ProviderManager, UsageStatus};
use crate::storage::Database;
use std::sync::Arc;
use tauri::State;
use uuid::Uuid;

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
) -> Result<(), String> {
    db.save_provider(&provider).map_err(|e| e.to_string())?;
    manager.add_provider(provider).await;
    Ok(())
}

#[tauri::command]
pub async fn update_provider(
    id: String,
    provider: ProviderConfig,
    manager: State<'_, Arc<ProviderManager>>,
    db: State<'_, Arc<Database>>,
) -> Result<(), String> {
    db.save_provider(&provider).map_err(|e| e.to_string())?;
    manager.update_provider(id, provider).await;
    Ok(())
}

#[tauri::command]
pub async fn delete_provider(
    id: String,
    manager: State<'_, Arc<ProviderManager>>,
    db: State<'_, Arc<Database>>,
) -> Result<(), String> {
    db.delete_provider(&id).map_err(|e| e.to_string())?;
    manager.delete_provider(id).await;
    Ok(())
}

#[tauri::command]
pub async fn fetch_provider_status(
    id: String,
    manager: State<'_, Arc<ProviderManager>>,
) -> Result<UsageStatus, String> {
    let providers = manager.get_providers().await;
    let provider = providers
        .iter()
        .find(|p| p.id == id)
        .ok_or("Provider not found")?;
    manager.fetch_usage(provider).await
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
) -> Result<String, String> {
    let providers = db.load_providers().map_err(|e| e.to_string())?;
    let export = serde_json::json!({
        "version": "1.0",
        "exported_at": chrono::Utc::now().timestamp(),
        "providers": providers,
    });
    serde_json::to_string_pretty(&export).map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn import_config(
    json: String,
    manager: State<'_, Arc<ProviderManager>>,
    db: State<'_, Arc<Database>>,
) -> Result<Vec<ProviderConfig>, String> {
    let parsed: serde_json::Value = serde_json::from_str(&json).map_err(|e| e.to_string())?;
    let providers: Vec<ProviderConfig> = serde_json::from_value(
        parsed["providers"].clone()
    ).map_err(|e| e.to_string())?;

    for provider in &providers {
        db.save_provider(provider).map_err(|e| e.to_string())?;
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
) -> Result<ProviderConfig, String> {
    let preset = get_preset_by_id(&preset_id)
        .ok_or_else(|| format!("Preset '{}' not found", preset_id))?;

    let provider = ProviderConfig {
        id: Uuid::new_v4().to_string(),
        name: custom_name.unwrap_or_else(|| preset.name.clone()),
        provider: preset.provider_type.clone(),
        base_url: preset.base_url.clone(),
        api_key,
        query_endpoint: preset.query_endpoint.clone(),
        query_method: preset.query_method.clone(),
        query_headers: preset.default_headers.clone(),
        query_params: serde_json::Value::Null,
        refresh_interval: refresh_interval.unwrap_or(preset.default_refresh_interval),
        is_enabled: true,
        status: "active".to_string(),
    };

    db.save_provider(&provider).map_err(|e| e.to_string())?;
    manager.add_provider(provider.clone()).await;
    Ok(provider)
}

/// 启用/禁用 Provider（不影响数据）
#[tauri::command]
pub async fn toggle_provider(
    id: String,
    enabled: bool,
    manager: State<'_, Arc<ProviderManager>>,
    db: State<'_, Arc<Database>>,
) -> Result<(), String> {
    let mut providers = manager.get_providers().await;
    let provider = providers
        .iter_mut()
        .find(|p| p.id == id)
        .ok_or("Provider not found")?;

    provider.is_enabled = enabled;
    let updated = provider.clone();
    db.save_provider(&updated).map_err(|e| e.to_string())?;
    Ok(())
}

/// 查询已启用的预置 ID 列表（用于前端判断哪些已添加）
#[tauri::command]
pub async fn get_active_preset_ids(
    manager: State<'_, Arc<ProviderManager>>,
) -> Result<Vec<String>, String> {
    let providers = manager.get_providers().await;
    // 通过 provider_type 反推可能的预置 ID
    let active_types: Vec<String> = providers
        .iter()
        .map(|p| p.provider.clone())
        .collect();
    Ok(active_types)
}
