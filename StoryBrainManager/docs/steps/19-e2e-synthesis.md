# 步骤 19：端到端合成完整广播剧片段

## 目标
把步骤 18 中按对话原始顺序绑定好的多段录音，调用步骤 10 已验收过的合并管线，生成一个
完整的广播剧音频文件，并保存进工程数据（触发步骤 16 的落盘）。

## 涉及文件
- `src/pages/ProjectWorkspace/index.tsx`：加"合成完整音频"按钮
- `src/types/index.ts`：`Project` 类型补充一个 `finalAudioPath?: string` 字段
- `src-tauri` 侧的 `Project` 结构体（`commands/project.rs`）同步补充该字段（注意
  Rust 与 TS 字段命名分别是 `final_audio_path` / `finalAudioPath`，靠 `camelCase`
  serde 配置自动转换，需确认 `project.rs` 里的 `Project` struct 有对应字段并加上
  `#[serde(rename_all = "camelCase")]`，脚手架里已配置）

## 实现要点
```tsx
const handleSynthesize = async () => {
  const orderedPaths = dialogues.map((_, i) => lineTracks[i]).filter(Boolean);
  const outputPath = `${appDataDir}/final_${projectId}.wav`;
  await mergeTracks(orderedPaths, outputPath);
  const updatedProject = { ...currentProject, finalAudioPath: outputPath };
  await saveProject(updatedProject);
};
```

## 验证方法
1. 完成步骤 18 中 3~4 行对话的分角色朗读绑定
2. 点击"合成完整音频"
3. 等待合并完成（应在 1 秒内，参考步骤 07/09 的性能特征）
4. 播放生成的最终音频文件，确认：
   - 内容按对话原始顺序（第1行→第2行→第3行→...）连续播放，无跳漏
   - 每行之间衔接自然（因为是无损字节拼接，不会有额外静音或杂音断层）
5. 调用 `save_project` 后，重启应用，重新进入该工程，确认能读到
   `finalAudioPath`，且该路径指向的文件依然存在且可播放（验证落盘持久化，复用
   步骤 16）

## 完成标准 (DoD)
- [ ] 合成的最终音频文件顺序、内容完全对应原始对话顺序
- [ ] 用系统播放器可以直接完整播放该文件
- [ ] 工程保存后，重启应用仍能找到并播放该最终音频文件
- [ ] 若某一行尚未录制（`lineTracks[i]` 为空），合成逻辑应跳过该行或给出提示，
      而不是直接崩溃
