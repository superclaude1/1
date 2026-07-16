// Windows/桌面端可执行文件入口，实际启动逻辑在 lib.rs 的 run() 中，
// 以便未来 Phase 2 移动端复用同一份 Rust 核心逻辑。
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    voxnovel_lib::run();
}
