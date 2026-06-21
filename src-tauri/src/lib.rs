// 跨平台共享库入口
// 桌面端由 main.rs 间接调用 run()
// 移动端（Android/iOS）由 Tauri 2.x 移动端运行时直接调用 run()

mod catalog;
mod commands;
mod poller;
mod providers;
mod storage;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    use providers::ProviderManager;
    use std::sync::Arc;
    use tauri::Manager;

    // 桌面端会通过 cfg(desktop) 块继续链式追加全局快捷键插件，所以这里需要 mut
    #[cfg(desktop)]
    let mut builder = tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_notification::init());

    #[cfg(not(desktop))]
    let builder = tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_notification::init());

    // 全局快捷键插件仅桌面端支持
    #[cfg(desktop)]
    {
        builder = builder.plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(|app, _shortcut, _event| {
                    if let Some(window) = app.get_webview_window("main") {
                        if window.is_visible().unwrap_or(false) {
                            let _ = window.hide();
                        } else {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                })
                .build(),
        );
    }

    builder
        .setup(|app| {
            // 初始化数据库 - 使用 Tauri 的 path API 获取跨平台数据目录
            let app_data_dir = app.path().app_data_dir()
                .expect("Failed to get app data directory");
            let db = Arc::new(storage::Database::new(app_data_dir)
                .expect("Failed to initialize database"));

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
            let poller = poller::Poller::new(manager.clone(), app.handle().clone(), db.clone());
            tauri::async_runtime::spawn(async move {
                poller.start_all().await;
            });

            // ===== 桌面端专属功能：快捷键 =====
            #[cfg(desktop)]
            {
                use tauri_plugin_global_shortcut::{Code, GlobalShortcutExt, Modifiers, Shortcut};

                // 注册全局快捷键 Ctrl+Shift+M
                let shortcut = Shortcut::new(
                    Some(Modifiers::CONTROL | Modifiers::SHIFT),
                    Code::KeyM,
                );
                app.handle()
                    .global_shortcut()
                    .register(shortcut)
                    .map_err(|e| format!("Failed to register shortcut: {}", e))?;
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_providers,
            commands::add_provider,
            commands::update_provider,
            commands::delete_provider,
            commands::fetch_provider_status,
            commands::get_provider_status,
            commands::get_usage_history,
            commands::export_config,
            commands::import_config,
            commands::get_provider_catalog,
            commands::enable_preset,
            commands::toggle_provider,
            commands::get_active_preset_ids,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
