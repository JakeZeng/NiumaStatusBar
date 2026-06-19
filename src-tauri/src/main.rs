#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod commands;
mod poller;
mod providers;
mod storage;

use providers::ProviderManager;
use std::sync::Arc;
use tauri::{
    tray::{MouseButton, MouseButtonState, TrayIconEvent},
    Manager,
};

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .setup(|app| {
            // 初始化数据库
            let db = Arc::new(storage::Database::new().expect("Failed to initialize database"));
            
            // 初始化 Provider 管理器
            let manager = Arc::new(ProviderManager::new());
            
            // 从数据库加载 Provider
            if let Ok(providers) = db.load_providers() {
                tauri::async_runtime::block_on(async {
                    manager.set_providers(providers).await;
                });
            }
            
            // 注册状态
            app.manage(db.clone());
            app.manage(manager.clone());
            
            // 初始化轮询器
            let poller = poller::Poller::new(manager.clone(), app.handle().clone());
            tauri::async_runtime::spawn(async move {
                poller.start_all().await;
            });
            
            // 系统托盘
            let _tray = tauri::tray::TrayIconBuilder::new()
                .tooltip("AI 模型监控")
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        let app = tray.app_handle();
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                })
                .build(app)?;
            
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_providers,
            commands::add_provider,
            commands::update_provider,
            commands::delete_provider,
            commands::fetch_provider_status,
            commands::get_provider_status,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
