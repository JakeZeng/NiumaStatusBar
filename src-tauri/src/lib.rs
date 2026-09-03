// 跨平台共享库入口
// 桌面端由 main.rs 间接调用 run()
// 移动端（Android/iOS）由 Tauri 2.x 移动端运行时直接调用 run()

mod catalog;
mod commands;
mod logging;
mod poller;
mod providers;
mod storage;
#[cfg(desktop)]
mod tray;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    use providers::ProviderManager;
    use std::sync::Arc;
    #[cfg(desktop)]
    use tauri::Emitter;
    use tauri::Manager;

    // 关闭行为偏好的内存缓存，供 on_window_event 同步读取
    #[cfg(desktop)]
    let close_action_cache: Arc<std::sync::RwLock<Option<String>>> =
        Arc::new(std::sync::RwLock::new(None));

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

    #[cfg(desktop)]
    {
        let cache = close_action_cache.clone();
        builder = builder.on_window_event(move |window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                let action = cache.read().ok().and_then(|g| g.clone());
                match action.as_deref() {
                    Some("exit") => {
                        // 不阻拦，让窗口关闭后 Tauri 自动退出
                    }
                    Some("minimize_to_tray") => {
                        api.prevent_close();
                        let _ = window.hide();
                    }
                    _ => {
                        api.prevent_close();
                        let _ = window.emit("close-requested", ());
                    }
                }
            }
        });
    }

    let builder = builder;
    builder
        .setup(move |app| {
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

            // 载入关闭行为偏好到内存缓存
            #[cfg(desktop)]
            {
                let saved = db.get_setting(commands::CLOSE_ACTION_KEY).ok().flatten();
                if let Ok(mut guard) = close_action_cache.write() {
                    *guard = saved;
                }
                // 暴露给命令层，set/reset 时同步刷新
                app.manage(close_action_cache.clone());
            }

            // 注册状态
            app.manage(db.clone());
            app.manage(manager.clone());

            // 初始化日志器（在 poller 与 tray 前，确保 start_all 触发首条 Setup 日志可被捕获）
            let logger = logging::AppLogger::new(db.clone(), app.handle().clone());
            app.manage(logger.clone());
            manager.set_logger(logger.clone());
            logger.log(
                logging::LogLevel::Info,
                logging::LogCategory::Setup,
                Some("lib.rs".into()),
                "application started",
                Some(serde_json::json!({
                    "version": env!("CARGO_PKG_VERSION"),
                })),
            );
            // 启动清理 7 天前旧日志，与 usage_history 一致
            if let Err(e) = db.cleanup_old_logs(7) {
                logger.log(
                    logging::LogLevel::Warn,
                    logging::LogCategory::Database,
                    Some("cleanup_old_logs".into()),
                    "failed to cleanup old logs",
                    Some(serde_json::json!({ "error": e.to_string() })),
                );
            }

            // 初始化轮询器（并注册为 State，供 commands 即时启停单个 Provider）
            let poller = Arc::new(poller::Poller::new(
                manager.clone(),
                app.handle().clone(),
                db.clone(),
            ));
            poller.set_logger(logger.clone());
            app.manage(poller.clone());
            tauri::async_runtime::spawn(async move {
                poller.start_all().await;
            });

            // ===== 桌面端专属功能：快捷键 + 系统托盘 =====
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

                // 注册系统托盘 + 用量轮播 tooltip
                tray::setup_tray(app.handle(), manager.clone(), logger.clone())
                    .map_err(|e| format!("Failed to setup tray: {}", e))?;

                // 按用户偏好应用托盘显隐（无偏好时默认可见）
                let tray_visible = db
                    .get_setting(commands::TRAY_VISIBLE_KEY)
                    .ok()
                    .flatten()
                    .map(|v| v != "0")
                    .unwrap_or(true);
                tray::apply_tray_visibility(app.handle(), tray_visible);
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_providers,
            commands::add_provider,
            commands::update_provider,
            commands::delete_provider,
            commands::fetch_provider_status,
            commands::widget_refresh_all,
            commands::get_provider_status,
            commands::get_usage_history,
            commands::export_config,
            commands::import_config,
            commands::get_provider_catalog,
            commands::enable_preset,
            commands::toggle_provider,
            commands::get_active_preset_ids,
            commands::get_close_action,
            commands::set_close_action,
            commands::reset_close_action,
            commands::window_hide_to_tray,
            commands::app_quit,
            commands::set_app_theme,
            commands::query_logs,
            commands::clear_logs,
            commands::get_tray_visible,
            commands::set_tray_visible,
            commands::get_autostart,
            commands::set_autostart,
            commands::get_widget_status,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
