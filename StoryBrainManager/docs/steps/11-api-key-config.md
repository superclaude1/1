# 步骤 11：DeepSeek API Key配置与存储

## 目标
Settings 页面能输入并保存 DeepSeek API Key 到 Rust 侧的 `AppState`（本步骤先用内存
`Mutex` 存储，暂不落盘，落盘持久化统一放到步骤 16 一起做，避免本步骤职责过多）。

## 涉及文件
- 新增 `src-tauri/src/commands/settings.rs`
- `src-tauri/src/commands/mod.rs`（加 `pub mod settings;`）
- `src-tauri/src/lib.rs`（注册 `set_api_key` / `get_api_key`）
- `src/pages/Settings/index.tsx`（加输入框 + 保存按钮，替换掉当前的 TODO 占位）
- `src/api/`（可新增 `settings.ts` 封装这两个 command 调用）

## 实现要点
```rust
#[tauri::command]
pub fn set_api_key(key: String, state: State<'_, AppState>) -> AppResult<()> {
    *state.deepseek_api_key.lock().unwrap() = Some(key);
    Ok(())
}

#[tauri::command]
pub fn get_api_key(state: State<'_, AppState>) -> AppResult<Option<String>> {
    Ok(state.deepseek_api_key.lock().unwrap().clone())
}
```

## 验证方法
1. 打开 Settings 页，输入一个测试字符串（先不用真实 Key，验证存取链路即可，如
   `"test-key-12345"`）
2. 点击保存
3. 刷新页面（或点击一个"重新读取"按钮调用 `get_api_key`），确认取回的值与刚才输入的
   完全一致
4. 关闭并重新打开应用（不重启进程只是重新加载页面的话不算，需要真正重启
   `tauri dev`），确认 Key 已丢失（因为本步骤只存内存，重启后清空属预期行为，
   不是 bug）

## 完成标准 (DoD)
- [ ] 保存后立即读取，能取回一致的字符串
- [ ] 未保存时 `get_api_key` 返回 `None`，前端能正确处理（不报错）
- [ ] 重启应用后 Key 清空（验证当前是内存态，非本步骤范围内的落盘需求）
