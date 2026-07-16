use super::schema::{ExtractResult, SCHEMA_EXAMPLE_JSON};
use crate::error::{AppError, AppResult};
use serde_json::json;

const DEEPSEEK_ENDPOINT: &str = "https://api.deepseek.com/chat/completions";

/// 严格遵守文档中 DeepSeek JSON Mode 的四项硬性约束：
/// 1) response_format = json_object
/// 2) prompt 中必须包含字面量 "json"
/// 3) system prompt 内嵌 schema 样例
/// 4) max_tokens 预留在 1000~2000 安全区间
pub async fn extract_dialogues(api_key: &str, novel_chunk: &str) -> AppResult<ExtractResult> {
    let system_prompt = format!(
        "You are a precise novel narrative parsing tool. You must analyze the input text \
         and return a valid json object matching the exact specified schema. Do not output \
         any markdown code blocks, explanatory prefix, or suffix text. Your JSON schema must \
         follow: {}",
        SCHEMA_EXAMPLE_JSON
    );

    let body = json!({
        "model": "deepseek-chat",
        "response_format": { "type": "json_object" },
        "messages": [
            { "role": "system", "content": system_prompt },
            { "role": "user", "content": novel_chunk }
        ],
        "max_tokens": 1500,
        "temperature": 0.2
    });

    let client = reqwest::Client::new();
    let resp = client
        .post(DEEPSEEK_ENDPOINT)
        .bearer_auth(api_key)
        .json(&body)
        .send()
        .await
        .map_err(|e| AppError::LlmRequest(e.to_string()))?;

    let text = resp
        .text()
        .await
        .map_err(|e| AppError::LlmRequest(e.to_string()))?;

    super::fallback::parse_with_fallback(&text)
}
