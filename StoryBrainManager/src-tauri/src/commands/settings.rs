use crate::error::{AppError, AppResult};
use crate::state::AppState;
use tauri::State;

#[tauri::command]
pub fn set_api_key(key: String, state: State<'_, AppState>) -> AppResult<()> {
    *state.deepseek_api_key.lock().unwrap() = Some(key);
    Ok(())
}

#[tauri::command]
pub fn get_api_key(state: State<'_, AppState>) -> AppResult<Option<String>> {
    Ok(state.deepseek_api_key.lock().unwrap().clone())
}

/// 步骤12：DeepSeek API 连通性最小验证
#[tauri::command]
pub async fn test_deepseek_connection(api_key: String) -> AppResult<String> {
    let body = serde_json::json!({
        "model": "deepseek-chat",
        "response_format": { "type": "json_object" },
        "messages": [
            { "role": "system", "content": "Return json only." },
            { "role": "user", "content": "请返回 {\"reply\":\"ok\"} 这样格式的 json" }
        ],
        "max_tokens": 100
    });

    let client = reqwest::Client::new();
    let resp = client
        .post("https://api.deepseek.com/chat/completions")
        .bearer_auth(&api_key)
        .json(&body)
        .send()
        .await
        .map_err(|e| AppError::LlmRequest(e.to_string()))?;

    let status = resp.status();
    let text = resp.text().await.map_err(|e| AppError::LlmRequest(e.to_string()))?;

    if !status.is_success() {
        return Err(AppError::LlmRequest(format!("HTTP {status}: {text}")));
    }

    Ok(text)
}
