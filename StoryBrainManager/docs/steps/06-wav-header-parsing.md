# 步骤 06：WAV头部真实解析

## 目标
用真实的字节解析（或 Symphonia）读取 WAV 文件的 `fmt` chunk，得到
`channels` / `bits_per_sample` / `sample_rate`，替换 `merge.rs` 中当前占位的
`WavFormat { channels: 0, ... }`，为后续「合并前必须校验格式一致」提供真实数据。

## 涉及文件
- `src-tauri/src/audio/format.rs`：新增 `pub fn parse_wav_format(path: &str) -> AppResult<WavFormat>`
- `src-tauri/src/audio/merge.rs`：`merge_wav_files` 内调用 `parse_wav_format` 替代占位值

## 实现要点
标准 WAV（RIFF/PCM）的 `fmt` chunk 通常从偏移 12 开始，关键字段：
- 偏移 22~23：声道数（u16，小端序）
- 偏移 24~27：采样率（u32，小端序）
- 偏移 34~35：位深（u16，小端序）

```rust
pub fn parse_wav_format(path: &str) -> AppResult<WavFormat> {
    let mut file = std::fs::File::open(path)?;
    let mut header = [0u8; 44];
    file.read_exact(&mut header)?;
    let channels = u16::from_le_bytes([header[22], header[23]]);
    let sample_rate = u32::from_le_bytes([header[24], header[25], header[26], header[27]]);
    let bits_per_sample = u16::from_le_bytes([header[34], header[35]]);
    Ok(WavFormat { channels, bits_per_sample, sample_rate })
}
```
（若后续要支持非标准/带扩展 chunk 的 WAV，再升级为用 Symphonia 做正式 chunk 遍历）

## 验证方法
1. 用步骤 04 生成的两个不同质量档位的 wav 文件（如一个 High、一个 Low）
2. 写一个临时 Tauri command 或 Rust 单元测试，调用 `parse_wav_format` 并打印结果：
   ```rust
   #[test]
   fn test_parse() {
       let fmt = parse_wav_format("path/to/high.wav").unwrap();
       println!("{:?}", fmt); // 期望 channels=2, sample_rate=48000, bits_per_sample=16
   }
   ```
   `cargo test test_parse -- --nocapture`
3. 用 Windows 文件"属性"面板或音频编辑软件（如 Audacity 打开后看"项目采样率"）核对
   同一份文件的真实参数，确认与 Rust 解析结果一致

## 完成标准 (DoD)
- [ ] `cargo test` 通过，解析结果非全 0
- [ ] High 档位解析出约 `channels=2, sample_rate=48000, bits_per_sample=16`
- [ ] Low 档位解析出约 `channels=1, sample_rate=16000`
- [ ] 解析结果与第三方工具核对一致
