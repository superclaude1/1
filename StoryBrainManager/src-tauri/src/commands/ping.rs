// 步骤02：最小化IPC连通性验证命令，不涉及业务逻辑。
// 后续 audio/llm/project 各命令的调用链路与此完全一致，此处验证通过即可确认
// 前端 invoke() -> Tauri IPC 桥接 -> Rust #[tauri::command] 的链路是通的。
#[tauri::command]
pub fn ping(msg: String) -> String {
    println!("[ping] received from frontend: {msg}");
    format!("pong from Rust: {msg}")
}
