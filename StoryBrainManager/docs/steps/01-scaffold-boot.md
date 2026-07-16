# 步骤 01：项目初始化与空窗口启动

## 目标
确认 Node、Rust 工具链、WebView2 环境全部就绪，`npm run tauri dev` 能成功编译并弹出
VoxNovel 空白窗口。这一步不写任何业务代码，纯粹是环境验证，后面所有步骤都建立在此基础上。

## 涉及文件
无代码改动。仅确认以下文件存在且内容与脚手架一致：
- `package.json`
- `src-tauri/Cargo.toml`
- `src-tauri/tauri.conf.json`

## 实现要点
- Windows 需安装 Rust（`rustup`）+ MSVC 构建工具（Visual Studio Build Tools，勾选"使用 C++
  的桌面开发"工作负载）
- WebView2 Runtime：Win10 22H2+/Win11 通常已内置，若无需单独安装
- 首次 `npm install` 会拉取前端依赖；首次 `tauri dev` 会编译全部 Rust 依赖（含 tokio、
  reqwest、symphonia 等），耗时可能 3~10 分钟，属正常现象

## 验证方法
1. `cd D:\voxnovel`
2. `npm install`，确认退出码为 0，无红色报错
3. `npm run tauri dev`
4. 等待终端出现类似 `Compiling voxnovel v0.1.0` 的日志直到编译结束
5. 桌面弹出标题为「VoxNovel 声书」的窗口，窗口内显示"VoxNovel 声书"标题文字和一个空的
   项目列表区域（此时列表为空数组，属预期，不算报错）
6. 打开窗口右键 → 检查（若开发者工具可用）或观察终端，确认没有红色 error 日志

## 完成标准 (DoD)
- [ ] `npm install` 成功
- [ ] `npm run tauri dev` 成功编译并弹出窗口
- [ ] 窗口标题正确显示「VoxNovel 声书」
- [ ] 终端与前端控制台均无报错
- [ ] 任务管理器可见 `voxnovel.exe`（或对应开发态进程）正在运行
