# 步骤 04：麦克风录音能力接入（cpal真实录音）

## 目标
把 `commands/audio_record.rs` 中的占位实现替换成真实的麦克风采集逻辑，录制一段音频并
落盘为标准 WAV 文件（44字节头 + PCM数据），Windows 桌面端优先用 `cpal` 直接实现，不
依赖 Phase 2 才需要的 `tauri-plugin-native-audio`。

## 涉及文件
- `src-tauri/Cargo.toml`（新增依赖 `cpal = "0.15"`, `hound = "3"`，`hound` 用于快速写出
  合规 WAV 文件头，避免手写字节序出错）
- 重写 `src-tauri/src/commands/audio_record.rs`
- `src-tauri/src/state.rs`（可能需要加一个字段持有当前录音流的句柄，用于 stop 时正确
  停止采集）

## 实现要点
- `start_recording`：根据 `quality` 参数（low/medium/high）选择采样率与声道数
  （对应文档：Low 16kHz单声道 / Medium 44.1kHz单声道 / High 48kHz双声道），用 `cpal`
  打开默认输入设备的 input stream，写入 `hound::WavWriter`
- `stop_recording`：停止 stream，flush 并关闭 WavWriter，返回文件路径、时长（可用
  采样帧数 / 采样率估算）
- 输出路径：写入 `outputDir`（前端传入，Phase 1 阶段可以先固定为
  `$APPDATA/voxnovel/recordings/`），文件名用 `uuid`

## 验证方法
1. 完成代码改动，`npm run tauri dev`
2. 在任意页面挂载 `AudioRecorder` 组件（可临时放在 Home 页测试）
3. 选择质量档位（如 High），点击"开始录音"
4. 对着麦克风说 3~5 秒话，点击"停止录音"
5. 用文件管理器打开对应 `outputDir`，确认生成了一个新的 `.wav` 文件
6. 双击用系统自带播放器（或 Windows Media Player / 浏览器拖入）播放，确认：
   - 能听到刚才说的内容
   - 时长与实际录制时长大致相符（误差在 0.5 秒以内）
7. 分别用 Low / Medium / High 三档各录一次，用文件属性面板确认采样率/声道数与预设一致

## 完成标准 (DoD)
- [ ] 三档质量预设均能成功生成可播放的 wav 文件
- [ ] 音频内容与实际录制内容一致，无杂音/静音异常
- [ ] 文件采样率/声道数与所选质量档位参数吻合
- [ ] 停止录音后 Rust 侧无残留未释放的音频流（多次录制不报错、不崩溃）
