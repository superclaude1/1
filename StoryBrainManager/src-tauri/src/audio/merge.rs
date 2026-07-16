use super::format::{assert_mergeable, parse_wav_format};
use super::wav_header::{read_header, riff_chunk_size, rewrite_sizes, WAV_HEADER_LEN};
use crate::error::AppResult;
use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Seek, SeekFrom, Write};

/// 100% 纯 Rust、进程内存中完成的无损 WAV 追加合并。
/// 不依赖任何外部二进制子进程（对应文档中规避 Android SELinux 限制的方案）。
pub fn merge_wav_files(path_a: &str, path_b: &str, output_path: &str) -> AppResult<String> {
    let fmt_a = parse_wav_format(path_a)?;
    let fmt_b = parse_wav_format(path_b)?;
    assert_mergeable(&fmt_a, &fmt_b)?;

    let file_a = File::open(path_a)?;
    let file_b = File::open(path_b)?;
    let size_a = file_a.metadata()?.len();
    let size_b = file_b.metadata()?.len();

    let mut reader_a = BufReader::new(file_a);
    let mut reader_b = BufReader::new(file_b);

    let header_a = read_header(&mut reader_a)?;
    let mut header_b_buf = [0u8; 44];
    reader_b.read_exact(&mut header_b_buf)?;

    let s1 = size_a - WAV_HEADER_LEN;
    let s2 = size_b - WAV_HEADER_LEN;
    let s_new = (s1 + s2) as u32;
    let c_riff = riff_chunk_size(s_new);

    let out_file = File::create(output_path)?;
    let mut writer = BufWriter::new(out_file);

    writer.write_all(&header_a)?;

    std::io::copy(&mut reader_a, &mut writer)?;
    std::io::copy(&mut reader_b, &mut writer)?;

    writer.flush()?;
    drop(writer);

    let mut out_for_rewrite = std::fs::OpenOptions::new()
        .write(true)
        .open(output_path)?;
    rewrite_sizes(&mut out_for_rewrite, c_riff, s_new)?;
    out_for_rewrite.seek(SeekFrom::Start(0))?;

    Ok(output_path.to_string())
}

/// 依次合并多段音轨
pub fn merge_many(track_paths: &[String], output_path: &str) -> AppResult<String> {
    if track_paths.is_empty() {
        return Ok(output_path.to_string());
    }
    if track_paths.len() == 1 {
        std::fs::copy(&track_paths[0], output_path)?;
        return Ok(output_path.to_string());
    }

    let tmp_path = format!("{output_path}.tmp");
    merge_wav_files(&track_paths[0], &track_paths[1], &tmp_path)?;

    let mut current = tmp_path;
    for next in &track_paths[2..] {
        let new_tmp = format!("{output_path}.tmp2");
        merge_wav_files(&current, next, &new_tmp)?;
        std::fs::remove_file(&current)?;
        current = new_tmp;
    }

    std::fs::rename(&current, output_path)?;
    Ok(output_path.to_string())
}
