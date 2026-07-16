mod commands;
mod error;
mod state;

use state::AppState;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .manage(AppState::default())
        .invoke_handler(tauri::generate_handler![
            commands::get_local_ip,
            commands::start_tts_server,
            commands::stop_tts_server,
            commands::get_tts_server_status,
        ])
        .run(tauri::generate_context!())
        .expect("error while running StoryBrainManager");
}
