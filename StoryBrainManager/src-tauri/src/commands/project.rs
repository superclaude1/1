use crate::error::{AppError, AppResult};
use serde::{Deserialize, Serialize};
use tauri::Manager;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Project {
    pub id: String,
    pub name: String,
    pub novel_text: String,
    pub tracks: Vec<serde_json::Value>,
    pub created_at: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub final_audio_path: Option<String>,
}

fn projects_dir(app: &tauri::AppHandle) -> Result<std::path::PathBuf, AppError> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| AppError::Other(e.to_string()))?
        .join("projects");
    std::fs::create_dir_all(&dir)?;
    Ok(dir)
}

#[tauri::command]
pub async fn list_projects(app: tauri::AppHandle) -> AppResult<Vec<Project>> {
    let dir = projects_dir(&app)?;
    let mut result = vec![];
    let entries = std::fs::read_dir(&dir).map_err(|e| {
        AppError::Other(format!("读取工程目录失败: {e}"))
    })?;

    for entry in entries {
        let entry = entry?;
        let path = entry.path();
        if path.extension().map(|e| e == "json").unwrap_or(false) {
            let content = std::fs::read_to_string(&path)?;
            if let Ok(p) = serde_json::from_str::<Project>(&content) {
                result.push(p);
            }
        }
    }

    result.sort_by(|a, b| b.created_at.cmp(&a.created_at));
    Ok(result)
}

#[tauri::command]
pub async fn create_project(
    name: String,
    novel_text: String,
    app: tauri::AppHandle,
) -> AppResult<Project> {
    let project = Project {
        id: uuid::Uuid::new_v4().to_string(),
        name,
        novel_text,
        tracks: vec![],
        created_at: chrono_now(),
        final_audio_path: None,
    };

    let dir = projects_dir(&app)?;
    let path = dir.join(format!("{}.json", project.id));
    let json = serde_json::to_string_pretty(&project)
        .map_err(|e| AppError::JsonParse(e.to_string()))?;
    std::fs::write(&path, json)?;

    Ok(project)
}

#[tauri::command]
pub async fn save_project(project: Project, app: tauri::AppHandle) -> AppResult<()> {
    let dir = projects_dir(&app)?;
    let path = dir.join(format!("{}.json", project.id));
    let json = serde_json::to_string_pretty(&project)
        .map_err(|e| AppError::JsonParse(e.to_string()))?;
    std::fs::write(path, json)?;
    Ok(())
}

fn chrono_now() -> String {
    format!("{:?}", std::time::SystemTime::now())
}
