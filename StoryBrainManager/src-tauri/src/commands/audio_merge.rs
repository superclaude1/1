use crate::audio::merge::merge_many;
use crate::error::AppResult;

#[tauri::command]
pub async fn merge_wav_tracks(track_paths: Vec<String>, output_path: String) -> AppResult<String> {
    merge_many(&track_paths, &output_path)
}
