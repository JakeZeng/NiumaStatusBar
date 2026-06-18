use crate::providers::{ProviderConfig, ProviderManager, UsageStatus};
use tauri::State;

#[tauri::command]
pub async fn get_providers(
    manager: State<'_, std::sync::Arc<ProviderManager>>,
) -> Result<Vec<ProviderConfig>, String> {
    Ok(manager.get_providers().await)
}

#[tauri::command]
pub async fn add_provider(
    provider: ProviderConfig,
    manager: State<'_, std::sync::Arc<ProviderManager>>,
) -> Result<(), String> {
    manager.add_provider(provider).await;
    Ok(())
}

#[tauri::command]
pub async fn update_provider(
    id: String,
    provider: ProviderConfig,
    manager: State<'_, std::sync::Arc<ProviderManager>>,
) -> Result<(), String> {
    manager.update_provider(id, provider).await;
    Ok(())
}

#[tauri::command]
pub async fn delete_provider(
    id: String,
    manager: State<'_, std::sync::Arc<ProviderManager>>,
) -> Result<(), String> {
    manager.delete_provider(id).await;
    Ok(())
}

#[tauri::command]
pub async fn fetch_provider_status(
    id: String,
    manager: State<'_, std::sync::Arc<ProviderManager>>,
) -> Result<UsageStatus, String> {
    let providers = manager.get_providers().await;
    let provider = providers
        .iter()
        .find(|p| p.id == id)
        .ok_or("Provider not found")?;
    manager.fetch_usage(provider).await
}
