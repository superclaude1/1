# 步骤 05【检查点】：桌面音频闭环验收

## 目标
串联步骤 01~04，完成「录音 → 保存 → 列表展示 → 播放」的最小闭环。这一步不新增底层能力，
只做整合联调，用于暴露前 4 步组合起来才会出现的问题（比如状态管理、多组件协同）。

## 涉及文件
- `src/pages/ProjectWorkspace/index.tsx`：接入 `AudioRecorder`，录制完成的
  `filePath` 存入本地 `useState<AudioTrack[]>` 数组，逐条渲染为列表，每项配一个
  `AudioPlayer`

## 实现要点
```tsx
const [tracks, setTracks] = useState<AudioTrack[]>([]);

<AudioRecorder
  outputDir={appDataDir}
  onFinished={(filePath) =>
    setTracks((prev) => [...prev, { id: crypto.randomUUID(), filePath, character: null, durationMs: 0, format: "wav" }])
  }
/>

{tracks.map((t) => (
  <AudioPlayer key={t.id} filePath={t.filePath} label={t.id.slice(0, 8)} />
))}
```

## 验证清单（端到端操作，不看代码）
1. 冷启动 `npm run tauri dev`，窗口正常打开（复用步骤 01 的验证）
2. 进入 ProjectWorkspace，点击"开始录音"，说话 3 秒，点击"停止录音"
   → 列表新增一条记录（复用步骤 04）
3. 点击该条记录的播放按钮，能听到刚才录制的内容（复用步骤 03）
4. 连续录制 3 段内容不同的音频（如分别说"一""二""三"）
5. 任意乱序点击这 3 条记录的播放按钮，确认每条播放的内容都和录制时说的一致，不会
   串号/错位
6. 全程无控制台报错，无 IPC 调用失败（复用步骤 02 打下的 IPC 基础）

## 完成标准 (DoD)
- [ ] 仅通过 UI 操作即可完成"录 3 段音频，任意播放验证内容正确"的完整流程
- [ ] 3 条记录互不混淆，播放内容与录制内容一一对应
- [ ] 全程无未捕获异常、无红色控制台报错
