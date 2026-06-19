use crate::providers::{ProviderConfig, ProviderManager, UsageStatus};
use crate::storage::Database;
use std::sync::Arc;
use tauri::State;

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
    // 保存到数据库
    db.save_provider(&provider).map_err(|e| e.to_string())?;
    // 添加到内存
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
    // 更新数据库
    db.save_provider(&provider).map_err(|e| e.to_string())?;
    // 更新内存
    manager.update_provider(id, provider).await;
    Ok(())
}

#[tauri::command]
pub async fn delete_provider(
    id: String,
    manager: State<'_, Arc<ProviderManager>>,
    db: State<'_, Arc<Database>>,
) -> Result<(), String> {
    // 从数据库删除
    db.delete_provider(&id).map_err(|e| e.to_string())?;
    // 从内存删除
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
