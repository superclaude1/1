use crate::audio::merge::merge_many;
use crate::commands::project::Project;
use crate::error::{AppError, AppResult};
use serde_json;
use tauri::Manager;

/// 合成最终广播剧音频：合并音轨 → 保存路径到工程 → 落盘
#[tauri::command]
pub async fn synthesize_project(
    project_id: String,
    track_paths: Vec<String>,
    app: tauri::AppHandle,
) -> AppResult<String> {
    if track_paths.is_empty() {
        return Err(AppError::Other("没有可合成的音轨".into()));
    }

    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| AppError::Other(e.to_string()))?;
    std::fs::create_dir_all(&dir)?;

    let output_path = dir
        .join(format!("final_{}.wav", project_id))
        .to_string_lossy()
        .to_string();

    merge_many(&track_paths, &output_path)?;

    // 更新工程记录
    let projects_dir = app
        .path()
        .app_data_dir()
        .map_err(|e| AppError::Other(e.to_string()))?
        .join("projects");
    let project_file = projects_dir.join(format!("{}.json", project_id));

    if project_file.exists() {
        let content = std::fs::read_to_string(&project_file)?;
        let mut project: Project =
            serde_json::from_str(&content).map_err(|e| AppError::JsonParse(e.to_string()))?;
        project.final_audio_path = Some(output_path.clone());
        let json = serde_json::to_string_pretty(&project)
            .map_err(|e| AppError::JsonParse(e.to_string()))?;
        std::fs::write(&project_file, json)?;
    }

    Ok(output_path)
}
