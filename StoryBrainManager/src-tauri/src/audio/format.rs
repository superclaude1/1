use crate::error::{AppError, AppResult};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WavFormat {
    pub channels: u16,
    pub bits_per_sample: u16,
    pub sample_rate: u32,
}

impl WavFormat {
    pub fn block_align(&self) -> u32 {
        self.channels as u32 * (self.bits_per_sample as u32 / 8)
    }

    pub fn byte_rate(&self) -> u32 {
        self.sample_rate * self.block_align()
    }
}

/// 从 WAV 文件头直接按字节偏移解析 fmt chunk 关键字段
pub fn parse_wav_format(path: &str) -> AppResult<WavFormat> {
    let mut file = std::fs::File::open(path)?;
    let mut header = [0u8; 44];
    std::io::Read::read_exact(&mut file, &mut header)?;

    let channels = u16::from_le_bytes([header[22], header[23]]);
    let sample_rate = u32::from_le_bytes([header[24], header[25], header[26], header[27]]);
    let bits_per_sample = u16::from_le_bytes([header[34], header[35]]);

    Ok(WavFormat {
        channels,
        bits_per_sample,
        sample_rate,
    })
}

pub fn assert_mergeable(a: &WavFormat, b: &WavFormat) -> AppResult<()> {
    if a != b {
        return Err(AppError::AudioFormatMismatch(format!(
            "A={:?} 与 B={:?} 参数不一致，无法字节级无损合并",
            a, b
        )));
    }
    Ok(())
}
