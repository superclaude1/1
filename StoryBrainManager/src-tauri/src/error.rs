use serde::Serialize;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum AppError {
    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),
    #[error("音频格式不匹配，无法无损合并: {0}")]
    AudioFormatMismatch(String),
    #[error("LLM 请求失败: {0}")]
    LlmRequest(String),
    #[error("JSON 解析失败: {0}")]
    JsonParse(String),
    #[error("音频录制错误: {0}")]
    Recording(String),
    #[error("其他错误: {0}")]
    Other(String),
}

impl From<hound::Error> for AppError {
    fn from(e: hound::Error) -> Self {
        AppError::Recording(format!("WAV 写入失败: {e}"))
    }
}

impl From<cpal::BuildStreamError> for AppError {
    fn from(e: cpal::BuildStreamError) -> Self {
        AppError::Recording(format!("音频流创建失败: {e}"))
    }
}

impl From<cpal::PlayStreamError> for AppError {
    fn from(e: cpal::PlayStreamError) -> Self {
        AppError::Recording(format!("音频流启动失败: {e}"))
    }
}

impl Serialize for AppError {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        serializer.serialize_str(&self.to_string())
    }
}

pub type AppResult<T> = Result<T, AppError>;
