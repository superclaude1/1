use crate::error::AppResult;
use std::io::{Read, Seek, SeekFrom, Write};

pub const WAV_HEADER_LEN: u64 = 44;

// RIFF chunk size 偏移 4..8，Data chunk size 偏移 40..44（小端序）
const RIFF_SIZE_OFFSET: u64 = 4;
const DATA_SIZE_OFFSET: u64 = 40;

/// 读取标准 44 字节 WAV 头
pub fn read_header(reader: &mut impl Read) -> AppResult<[u8; 44]> {
    let mut header = [0u8; 44];
    reader.read_exact(&mut header)?;
    Ok(header)
}

/// C_riff = 36 + S_new
pub fn riff_chunk_size(new_data_size: u32) -> u32 {
    36 + new_data_size
}

/// 将新的 RIFF/Data chunk size 写回文件头（小端序）
pub fn rewrite_sizes(
    writer: &mut (impl Write + Seek),
    riff_size: u32,
    data_size: u32,
) -> AppResult<()> {
    writer.seek(SeekFrom::Start(RIFF_SIZE_OFFSET))?;
    writer.write_all(&riff_size.to_le_bytes())?;
    writer.seek(SeekFrom::Start(DATA_SIZE_OFFSET))?;
    writer.write_all(&data_size.to_le_bytes())?;
    Ok(())
}
