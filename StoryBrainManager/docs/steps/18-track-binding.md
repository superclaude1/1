# 步骤 18：角色音轨分配与录制绑定

## 目标
让 `ScriptEditor` 提取出的每一行对话，都能关联到"由哪段录音朗读"，形成
`对话行 → 录音文件` 的一一绑定关系，为步骤 19 的最终合成提供有序、可追溯的音轨列表。

## 涉及文件
- `src/pages/ProjectWorkspace/index.tsx`：整合 `ScriptEditor` + `AudioRecorder` +
  `AudioPlayer`，每一行对话旁边加"录制"按钮和（录制完成后的）"播放"按钮
- `src/types/index.ts`：可能需要给 `DialogueLine` 临时补充一个前端专用字段
  `trackFilePath?: string`（不影响后端 schema，仅前端展示态使用）

## 实现要点
```tsx
const [lineTracks, setLineTracks] = useState<Record<number, string>>({});

{dialogues.map((line, i) => (
  <div key={i}>
    <span>{line.character ?? "旁白"}：{line.text}</span>
    <AudioRecorder
      outputDir={appDataDir}
      onFinished={(filePath) => setLineTracks((prev) => ({ ...prev, [i]: filePath }))}
    />
    {lineTracks[i] && <AudioPlayer filePath={lineTracks[i]} label={`第${i + 1}行`} />}
  </div>
))}
```

## 验证方法
1. 对步骤 15 已验收过的提取结果（假设有 3 行对话）逐行点击"录制"，分别朗读对应的
   文本内容
2. 每行录制完成后，该行旁边应出现对应的播放按钮
3. 逐行点击播放，确认：
   - 第 1 行播放的是第 1 行录制的内容
   - 第 2 行播放的是第 2 行录制的内容，不会误播成第 1 行或第 3 行的内容
4. 故意重新录制第 2 行（覆盖录制），确认播放第 2 行时听到的是**新**录制的内容，
   旧绑定被正确替换而非残留

## 完成标准 (DoD)
- [ ] 每行对话都能独立录制并绑定，互不干扰
- [ ] 播放任意一行都精确对应该行文本内容
- [ ] 重新录制某一行能正确覆盖旧绑定，不产生"幽灵"旧录音残留
