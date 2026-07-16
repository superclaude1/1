use super::schema::ExtractResult;
use crate::error::{AppError, AppResult};

/// DeepSeek 返回的原始响应是 chat.completions 格式，
/// message.content 字段本身才是我们要的 JSON 字符串，需要二次反序列化。
/// 网络抖动或长尾文本可能导致解析失败，此处提供兜底逻辑避免界面崩溃。
pub fn parse_with_fallback(raw_response: &str) -> AppResult<ExtractResult> {
    let envelope: serde_json::Value = serde_json::from_str(raw_response)
        .map_err(|e| AppError::JsonParse(format!("外层响应解析失败: {e}")))?;

    let content = envelope["choices"][0]["message"]["content"]
        .as_str()
        .ok_or_else(|| AppError::JsonParse("缺少 message.content 字段".into()))?;

    serde_json::from_str::<ExtractResult>(content).or_else(|e| {
        // 兜底：解析失败时返回空列表而非直接报错中断界面
        eprintln!("[llm::fallback] JSON 解析失败，已降级为空结果: {e}");
        Ok(ExtractResult { dialogues: vec![] })
    })
}
