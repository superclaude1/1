# StoryBrain — AI 小说转广播剧全栈工具链

低成本跨平台小说广播剧制作工具，覆盖 **AI 剧情分析 → 角色图谱 → 高质量配音** 完整链路。

## 项目结构

```
D:\stnavel\
├── StoryBrain/              # Android 端 — 小说阅读 & AI 剧情分析
│   ├── app/src/             # Kotlin 源码 (ImportActivity, ReaderActivity, GraphActivity...)
│   ├── import_to_neo4j.py   # 剧情图谱导出到 Neo4j
│   └── ...
│
├── StoryBrainManager/       # Windows 桌面端 — TTS 配音引擎管理
│   ├── src/                 # React 19 + TypeScript 前端 (Tauri v2)
│   ├── src-tauri/           # Rust 后端 (音频合并 / LLM 对接 / 进程管理)
│   ├── engine/              # Python TTS 引擎
│   │   ├── app_chattts.py      # [旧] MOSS-TTS-Nano 引擎
│   │   └── app_cosyvoice.py    # [新] CosyVoice 3 (0.5B-RL) 引擎
│   ├── adb_connect.bat      # USB 直连手机助手
│   └── 双击运行管理器.bat    # 一键启动
│
├── MOSS-TTS-Nano/           # [旧] OpenMOSS 微型 TTS 模型 (0.1B)
├── install_cosyvoice.bat    # CosyVoice 3 一键安装脚本
└── plant/                   # 测试小说文本
```

## TTS 引擎升级: MOSS-TTS-Nano → CosyVoice 3

| 特性 | MOSS-TTS-Nano (旧) | CosyVoice 3 0.5B-RL (新) |
|---|---|---|
| 参数量 | 0.1B | 0.5B |
| 中文字错率 (CER) | ~3-5% | **0.81%** (商用级) |
| 声音克隆 | 有限 (hash 变调) | 零样本 3-10s 精确克隆 |
| 情感控制 | 无 | [laugh] [breath] [happy] 等 |
| 流式首包延迟 | ~800ms | **150-250ms** |
| 支持方言 | 无 | 粤语/四川/东北 等 18+ 方言 |
| 内存占用 | ~200MB | ~4.5GB |
| CPU 线程 | 自动 | 限制 4 核 (防止降频) |

## 快速开始

### 1. 安装 CosyVoice 3 引擎

```bat
D:\stnavel\install_cosyvoice.bat
```

自动完成: conda 环境创建 → 依赖安装 → 模型下载 (~2GB)

### 2. 启动配音管理端

```bat
D:\stnavel\StoryBrainManager\双击运行管理器.bat
```

### 3. 连接手机

**方式 A — USB 直连 (推荐，零延迟):**
```bat
D:\stnavel\StoryBrainManager\adb_connect.bat
```
手机端配置地址: `http://127.0.0.1:18083`

**方式 B — WiFi 局域网:**
启动服务器后，控制台会显示本机 IP 和二维码，手机扫描即可。

**方式 C — 手机热点:**
电脑连接手机热点 → 自动处于同一局域网。

## Android 端 (StoryBrain)

技术栈: Kotlin + Gradle (AGP 8.2.2, Android 14)

```bash
cd StoryBrain
./gradlew assembleDebug
```

功能:
- 导入 TXT 小说 (自动编码检测、去广告、分章)
- LLM 自动分析角色/剧情/关系 (支持 Gemini / OpenAI 兼容)
- ECharts 剧情关系图谱可视化
- 角色聊天 (CharacterChatActivity)
- 全书搜索
- 对接桌面端 CosyVoice 3 配音服务

## 桌面端 API

启动后服务运行在 `http://<ip>:18083`

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/generate` | POST | 合成语音 (text + demo_id) |
| `/api/generate_stream` | POST | 流式合成 (SSE) |
| `/api/status` | GET | 服务器状态 |
| `/api/settings` | GET/POST | TTS 参数配置 |
| `/api/logs` | GET | 运行日志 |

## 硬件要求

- **CPU**: AMD Ryzen 7 7735HS (8 核) 或同级
- **内存**: 16GB+ (推荐 32GB)
- **存储**: 10GB 可用空间
- **GPU**: 不需要 (纯 CPU 推理)

## 路线图

- [x] Phase 1: Windows 桌面端核心闭环
- [x] CosyVoice 3 引擎升级
- [x] USB 直连 (ADB reverse tunnel)
- [ ] Phase 2: Android 端完整配音集成
- [ ] PyInstaller 打包为独立 EXE
- [ ] Phase 3: 云端弹性扩展

## 许可

MIT License — 详见各子目录 LICENSE 文件。
