# 步骤 13：结构化对话提取（真实业务schema）

## 目标
用一段真实小说文本片段调用已有的 `extract_dialogues`（`llm/deepseek_client.rs` +
`llm/schema.rs` + `llm/fallback.rs`），验证 DeepSeek JSON Mode 四要素约束
（response_format / prompt含"json"字面量 / schema样例 / max_tokens）全部生效，且
`voice_tag` 字段能正确对应 `[laugh]` / `[uv_break]` / `[lbreak]` / `null`。

## 涉及文件
- 无需新增文件，基于步骤 12 验证过的连通性，直接联调已有的
  `commands/llm_extract.rs` → `llm/deepseek_client.rs::extract_dialogues`

## 实现要点
本步骤主要是「联调」而非「新写代码」，如遇到问题，检查顺序：
1. `system_prompt` 里是否确实包含字面量单词 "json"（已在 `deepseek_client.rs` 中
   拼接，注意大小写和拼写不要被后续修改破坏）
2. `max_tokens` 是否够用（当前设为1500，若文本较长导致截断，`fallback.rs` 应能
   优雅降级为空数组而不是让程序崩溃）
3. DeepSeek 返回的 `choices[0].message.content` 本身是"字符串形式的JSON"，需要
   二次反序列化（`fallback.rs` 中已处理，确认这一步没有被跳过）

## 验证方法
1. 准备一段含对话的小说片段，例如：
   > "等等！"林黛玉止住脚步，悄声冷笑道："哼，我就知道是你。"贾宝玉尴尬地笑了笑，
   > 没有说话。
2. 调用 `extract_dialogues`，检查返回的 `dialogues` 数组，期望类似：
   - `{character: "林黛玉", text: "等等！", voice_tag: null}`
   - `{character: "林黛玉", text: "哼，我就知道是你。", voice_tag: "[laugh]" 或合理值}`
   - `{character: null 或 "旁白", text: "贾宝玉尴尬地笑了笑，没有说话。", voice_tag: null}`
   （模型对角色名/语气标签的判断允许有一定主观性，重点验证**结构**正确，而非语义
   100%精确）
3. 故意构造一段容易让模型跑偏的极端文本（如纯符号 `"@#$%^&*()"` 或超长重复字符），
   调用后确认程序不崩溃，返回空 `dialogues` 数组或合理的降级结果（验证
   `fallback.rs` 生效）

## 完成标准 (DoD)
- [ ] 正常小说片段能提取出结构合理的对话数组，字段名与 `DialogueLine` 完全对应
- [ ] `voice_tag` 取值严格落在 `[laugh]` / `[uv_break]` / `[lbreak]` / `null` 四者之内
- [ ] 异常/边界文本触发 fallback，返回空数组而非抛出未捕获异常
- [ ] 多次调用同一段文本，返回结构稳定（允许具体文字判断有轻微差异，但不应出现
      解析失败）
