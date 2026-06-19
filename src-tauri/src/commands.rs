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
