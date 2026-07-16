# 步骤 07：双文件无损合并验证

## 目标
跑通 `merge_wav_files`，验证两个格式一致的 WAV 文件能被字节级无损拼接，且文件头的
RIFF/Data chunk size 被正确重写。

## 涉及文件
- `src-tauri/src/audio/merge.rs`（补全，使用步骤 06 的 `parse_wav_format` + 已有的
  `assert_mergeable` 校验）
- 前端：在 ProjectWorkspace 临时加一个"合并测试"按钮，传入两条已录制音轨的路径，调用
  `mergeTracks`（`src/api/audio.ts` 中已封装）

## 实现要点
```rust
pub fn merge_wav_files(path_a: &str, path_b: &str, output_path: &str) -> AppResult<String> {
    let fmt_a = format::parse_wav_format(path_a)?;
    let fmt_b = format::parse_wav_format(path_b)?;
    format::assert_mergeable(&fmt_a, &fmt_b)?;
    // ...（沿用已有的 header 复制 + 数据流追加 + rewrite_sizes 逻辑）
}
```

## 验证方法
1. 复用步骤 05 的录制界面，录制 A（说"一二三四"）和 B（说"五六七八"），确保用同一
   质量档位（如都用 High）
2. 调用合并，生成 `merged.wav`
3. 播放 `merged.wav`：应先听到"一二三四"，紧接着无缝听到"五六七八"，总时长约等于
   A 时长 + B 时长
4. 用十六进制查看器（如 HxD，Windows 免费工具）打开 `merged.wav`：
   - 偏移 `0x04~0x07`（RIFF chunk size）应等于 `36 + S_new`
   - 偏移 `0x28~0x2B`（即十进制 40~43，Data chunk size）应等于 `S_new`
   - `S_new` = A 数据段字节数 + B 数据段字节数（即两文件大小分别减去 44 字节头后求和）
5. 用 Python 快速核对（如果本机有 Python）：
   ```python
   import struct
   with open("merged.wav", "rb") as f:
       data = f.read(44)
       riff_size = struct.unpack("<I", data[4:8])[0]
       data_size = struct.unpack("<I", data[40:44])[0]
       print(riff_size, data_size)
   ```

## 完成标准 (DoD)
- [ ] 播放顺序正确：先A后B，内容完整无丢失/无杂音
- [ ] 总时长 ≈ A时长 + B时长（误差 < 0.1秒）
- [ ] 文件头字节值（riff_size / data_size）与公式计算结果吻合
- [ ] 合并耗时明显 < 1秒（体现"无重采样，纯字节拼接"的性能特征）
