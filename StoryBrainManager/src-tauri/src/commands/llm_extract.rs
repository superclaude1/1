use crate::llm::deepseek_client;
use crate::llm::schema::ExtractResult;
use crate::error::{AppError, AppResult};
use crate::state::AppState;
use tauri::State;

#[tauri::command]
pub async fn extract_dialogues(
    novel_chunk: String,
    state: State<'_, AppState>,
) -> AppResult<ExtractResult> {
    let api_key = state
        .deepseek_api_key
        .lock()
        .unwrap()
        .clone()
        .ok_or_else(|| AppError::Other("尚未配置 DeepSeek API Key".into()))?;

    deepseek_client::extract_dialogues(&api_key, &novel_chunk).await
}
