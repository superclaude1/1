# VoxNovel 声书

低成本跨平台小说广播剧制作工具 —— 基于 Tauri v2 + Rust + React。

当前阶段：**Phase 1 — Windows 桌面端核心逻辑闭环**

## 技术栈

- 前端：React 19 + TypeScript + Tailwind CSS + Vite + zustand
- 后端：Tauri v2 + Rust（Symphonia / wavers 无损音频合并，reqwest 对接 DeepSeek）

## 环境准备（Windows）

1. Node.js 18+，pnpm/npm 均可
2. Rust 工具链（`rustup`），MSVC 构建工具（Visual Studio Build Tools，勾选"使用 C++ 的桌面开发"）
3. WebView2 Runtime（Win10/11 通常已内置）

## 安装与运行

```powershell
cd voxnovel
npm install
npm run tauri dev
```

首次运行会编译 Rust 依赖，耗时较长属正常现象。

## 打包

```powershell
npm run tauri build
```

产物在 `src-tauri/target/release/bundle/`（msi / nsis 安装包）。

## 目录说明

- `src/` 前端 React 代码，页面在 `src/pages`，业务组件在 `src/components`
- `src-tauri/src/audio/` 纯 Rust 音频合并核心（`merge.rs` + `wav_header.rs`），不依赖 FFmpeg
  子进程，Phase 2 移植 Android 时直接复用，天然规避 SELinux 子进程执行限制
- `src-tauri/src/llm/` DeepSeek JSON Mode 封装，严格遵循四项约束（response_format /
  prompt 含"json" / schema 样例 / max_tokens）
- `src-tauri/capabilities/` Tauri v2 权限白名单，本地音频播放依赖此处的 `fs` 与 asset 协议范围配置

## 待办（Phase 1 范围内）

- [ ] `audio_record.rs` 接入实际录音能力（Windows 端可先用 `cpal` 直接采集，未来统一迁移到
      `tauri-plugin-audio-recorder` 以便和 Phase 2 Android 端共用接口）
- [ ] `merge.rs` 补上真实的 WAV fmt chunk 解析（当前 `WavFormat` 校验为占位逻辑）
- [ ] `project.rs` 落盘持久化（写入 `$APPDATA/voxnovel/projects/*.json`）
- [ ] Settings 页接入 DeepSeek API Key 配置并写入 `AppState`
- [x] 补充应用图标（`src-tauri/icons/`，已生成 32x32/128x128/128x128@2x/icon.png/icon.ico）

## Phase 2 / 3 预告

- Phase 2（Android 移植）：引入 `tauri-plugin-native-audio`（Media3 ExoPlayer 前台服务）、
  `AndroidManifest.xml` 权限声明，废除任何子进程调用
- Phase 3（云端弹性扩展）：Cloudflare Pages 静态托管 + Workers/Lambda 边缘函数 + COS/OSS 冷存储
