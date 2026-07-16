# 步骤 14：ScriptEditor可视化校对界面联调

## 目标
把步骤 13 提取出的真实结果渲染到已有的 `ScriptEditor` 组件，让提取结果可以在界面上
被人工读取和校对，同时补上 loading / error 状态的可见反馈。

## 涉及文件
- `src/pages/ProjectWorkspace/index.tsx`：接入 `useLLMExtract` hook + 一个文本输入框
  （粘贴小说片段）+ `ScriptEditor` 组件

## 实现要点
```tsx
const [novelChunk, setNovelChunk] = useState("");
const [dialogues, setDialogues] = useState<DialogueLine[]>([]);
const { extract, loading, error } = useLLMExtract();

const handleExtract = async () => {
  const result = await extract(novelChunk);
  setDialogues(result);
};

<textarea value={novelChunk} onChange={(e) => setNovelChunk(e.target.value)} />
<button onClick={handleExtract} disabled={loading}>
  {loading ? "提取中..." : "提取"}
</button>
{error && <p className="text-red-500">{error}</p>}
<ScriptEditor dialogues={dialogues} onChange={setDialogues} />
```

## 验证方法
1. 在 ProjectWorkspace 的文本框粘贴一段约 300~500 字的小说片段（含多处对话）
2. 点击"提取"按钮，按钮应显示"提取中..."并禁用，避免重复点击
3. 等待请求完成（一般几秒内），按钮恢复可点击状态
4. 界面上按行展示「角色名 | 对话/旁白文本 | 语气标签」，与原文人工比对：
   - 角色归属基本正确（允许模型对旁白/无名角色的判断有主观性）
   - 文本内容与原文对应，无大段丢失或错位
5. 故意断网后点击提取，确认 `error` 状态能在界面上显示出可读的错误提示，而不是
   白屏或无限转圈

## 完成标准 (DoD)
- [ ] loading 状态在请求期间正确显示，请求结束后正确清除
- [ ] 正常提取结果能完整渲染为逐行列表
- [ ] 错误状态（如断网）有明确的界面提示
- [ ] 重复点击"提取"按钮不会导致并发请求堆积或界面状态错乱
