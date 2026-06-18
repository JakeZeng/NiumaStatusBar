#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod commands;
mod providers;

use providers::ProviderManager;
use std::sync::Arc;
use tauri::{
    tray::{MouseButton, MouseButtonState, TrayIconEvent},
    Manager,
};

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .manage(Arc::new(ProviderManager::new()))
        .setup(|app| {
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
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
