use serde::{Deserialize, Serialize};

// 与 ChatTTS 原生支持的词级控制标签严格对齐
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum VoiceTag {
    #[serde(rename = "[laugh]")]
    Laugh,
    #[serde(rename = "[uv_break]")]
    UvBreak,
    #[serde(rename = "[lbreak]")]
    Lbreak,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DialogueLine {
    pub character: Option<String>,
    pub text: String,
    pub voice_tag: Option<VoiceTag>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExtractResult {
    pub dialogues: Vec<DialogueLine>,
}

/// 嵌入 system prompt 的 schema 样例，供 DeepSeek 严格对齐 key 拼写
pub const SCHEMA_EXAMPLE_JSON: &str = r#"{"dialogues":[{"character":"角色名或null","text":"对话或旁白文本","voice_tag":"[laugh] | [uv_break] | [lbreak] | null"}]}"#;
