# 步骤 02：前后端IPC连通性验证（ping命令）

## 目标
新增一个最简单的 `ping` Tauri command，验证前端 `invoke()` 能正确调用到 Rust 侧并拿到
返回值。这是后续所有 audio/llm/project command 能正常工作的前提条件，单独先验证一次。

## 涉及文件
- 新增 `src-tauri/src/commands/ping.rs`
- 修改 `src-tauri/src/commands/mod.rs`（加 `pub mod ping;`）
- 修改 `src-tauri/src/lib.rs`（`invoke_handler` 里注册 `commands::ping::ping`）
- 临时修改 `src/pages/Home/index.tsx`（加一个"测试连接"按钮，验证完可以保留或后续删除）

## 实现要点
`ping.rs`：
```rust
#[tauri::command]
pub fn ping(msg: String) -> String {
    format!("pong from Rust: {msg}")
}
```
前端调用：
```ts
import { invoke } from "@tauri-apps/api/core";
const result = await invoke<string>("ping", { msg: "hello" });
```

## 验证方法
1. 完成上述代码改动，`npm run tauri dev` 重新启动（或热更新）
2. 在 Home 页点击"测试连接"按钮
3. 页面上（或浏览器 console.log）应显示 `pong from Rust: hello`
4. 同时观察 `tauri dev` 终端，Rust 侧若加了 `println!` 也应能看到对应输出

## 完成标准 (DoD)
- [ ] 点击按钮后，前端界面能显示来自 Rust 的字符串返回值
- [ ] 字符串内容与预期一致（包含"pong from Rust"字样）
- [ ] 无 `invoke` 相关的 Promise rejection / 控制台报错
