import { useState, useEffect, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

interface ServerStatus {
  running: boolean;
  port: number;
  local_ip: string;
}

interface TtsLogPayload {
  message: string;
}

interface TtsSettings {
  temperature: number;
  top_p: number;
  top_k: number;
  speed: number;
  stream: boolean;
  enable_laugh: boolean;
  enable_breath: boolean;
}

const VOICE_DESCRIPTIONS: Record<string, { gender: string, age: string, style: string, desc: string, seed: number }> = {
  "demo-7": { gender: "男声", age: "少年/青年", style: "活力少侠", desc: "声线清亮阳光，情绪饱满，非常适合活泼、正义的主角或年轻侠客角色。", seed: 7777 },
  "demo-4": { gender: "男声", age: "中年", style: "老练市井", desc: "语速轻快幽默，适合江湖浪子、市井大叔或搞怪角色。", seed: 4444 },
  "demo-3": { gender: "女声", age: "少女", style: "甜美可爱", desc: "声线细腻纯真，略带台湾腔调，适合活泼可爱、温柔善良的小师妹或女主角。", seed: 3333 },
  "demo-6": { gender: "女声", age: "青年/御姐", style: "端庄稳重", desc: "语速沉稳大方，气场强，适合高冷师姐、成熟女性或派系掌门人角色。", seed: 6666 },
  "demo-5": { gender: "男声", age: "成年", style: "旁白播音", desc: "声线浑厚低沉，沉稳无私人感情波动，最适合用于旁白、环境描写与解说说明。", seed: 5555 },
};

export default function App() {
  const [serverRunning, setServerRunning] = useState(false);
  const [port, setPort] = useState(18083);
  const [localIp, setLocalIp] = useState("获取中...");
  const [logs, setLogs] = useState<string[]>([]);
  const [activeTab, setActiveTab] = useState<"dashboard" | "tuning" | "sandbox" | "logs">("dashboard");

  // Log filter and control states
  const [logFilter, setLogFilter] = useState<"all" | "system" | "request" | "error">("all");
  const [logQuery, setLogQuery] = useState("");
  const [autoScroll, setAutoScroll] = useState(true);

  // Model & Sandbox stats
  const [device, setDevice] = useState("CPU");
  const [lastClientIp, setLastClientIp] = useState("无");
  const [modelName, setModelName] = useState("CosyVoice-3-0.5B-RL");
  const [modelStatus, setModelStatus] = useState("未加载");

  // Voice Sandbox states
  const [sandboxText, setSandboxText] = useState("张师兄，大模型配音服务已就绪！笑死我了，哈哈！");
  const [sandboxVoice, setSandboxVoice] = useState("demo-7");
  const [loadingSandbox, setLoadingSandbox] = useState(false);

  // ChatTTS Settings states
  const [settings, setSettings] = useState<TtsSettings>({
    temperature: 0.8,
    top_p: 0.9,
    top_k: 50,
    speed: 1.0,
    stream: true,
    enable_laugh: true,
    enable_breath: true,
  });

  const [errorMessage, setErrorMessage] = useState("");
  const logEndRef = useRef<HTMLDivElement>(null);
  const isTauri = typeof (window as any).__TAURI_INTERNALS__ !== "undefined";

  // Sync status and load settings on startup
  useEffect(() => {
    refreshStatus();

    if (isTauri) {
      const unlisten = listen<TtsLogPayload>("tts-log", (event) => {
        setLogs((prev) => {
          const next = [...prev, event.payload.message];
          if (next.length > 300) next.shift();
          return next;
        });
      });
      return () => {
        unlisten.then((fn) => fn());
      };
    } else {
      const interval = setInterval(() => {
        refreshStatusWeb();
      }, 1000);
      return () => clearInterval(interval);
    }
  }, []);

  // Fetch TTS Settings once server is confirmed running
  useEffect(() => {
    if (serverRunning) {
      fetchSettings();
    }
  }, [serverRunning, port]);

  // Auto-scroll logs
  useEffect(() => {
    if (autoScroll) {
      logEndRef.current?.scrollIntoView({ behavior: "smooth" });
    }
  }, [logs, autoScroll]);

  const refreshStatus = async () => {
    if (isTauri) {
      try {
        const status: ServerStatus = await invoke("get_tts_server_status", { port });
        setServerRunning(status.running);
        setLocalIp(status.local_ip);
        if (status.running) {
          try {
            const response = await fetch(`http://127.0.0.1:${port}/api/status`);
            if (response.ok) {
              const extra = await response.json();
              setDevice(extra.device || "CPU");
              setLastClientIp(extra.last_client_ip || "无");
              setModelName(extra.model_name || "CosyVoice-3-0.5B-RL");
              setModelStatus(extra.model_status || "已加载");
            }
          } catch (err) {
            console.error("Failed to query runtime status", err);
          }
        } else {
          setModelStatus("未加载");
        }
      } catch (e: any) {
        console.error(e);
      }
    } else {
      await refreshStatusWeb();
    }
  };

  const refreshStatusWeb = async () => {
    try {
      const response = await fetch("/api/status");
      if (response.ok) {
        const status = await response.json();
        setServerRunning(true);
        setLocalIp(status.local_ip);
        setDevice(status.device || "CPU");
        setLastClientIp(status.last_client_ip || "无");
        setModelName(status.model_name || "CosyVoice-3-0.5B-RL");
        setModelStatus(status.model_status || "已加载");
      } else {
        setServerRunning(false);
        setModelStatus("未加载");
      }
    } catch (e) {
      setServerRunning(false);
      setModelStatus("未加载");
    }

    try {
      const response = await fetch("/api/logs");
      if (response.ok) {
        const data = await response.json();
        if (data.logs) {
          setLogs(data.logs);
        }
      }
    } catch (e) {
      console.error(e);
    }
  };

  const fetchSettings = async () => {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/api/settings`);
      if (response.ok) {
        const data = await response.json();
        setSettings(data);
      }
    } catch (e) {
      console.error("Failed to load settings", e);
    }
  };

  const handleSaveSettings = async (updatedSettings: TtsSettings) => {
    try {
      const formData = new FormData();
      formData.append("temperature", String(updatedSettings.temperature));
      formData.append("top_p", String(updatedSettings.top_p));
      formData.append("top_k", String(updatedSettings.top_k));
      formData.append("speed", String(updatedSettings.speed));
      formData.append("stream", updatedSettings.stream ? "true" : "false");
      formData.append("enable_laugh", updatedSettings.enable_laugh ? "true" : "false");
      formData.append("enable_breath", updatedSettings.enable_breath ? "true" : "false");

      const response = await fetch(`http://127.0.0.1:${port}/api/settings`, {
        method: "POST",
        body: formData,
      });

      if (response.ok) {
        setSettings(updatedSettings);
        setLogs((prev) => [...prev, "[SYSTEM] TTS Inference Config updated successfully."]);
      } else {
        throw new Error(`Server returned status ${response.status}`);
      }
    } catch (e: any) {
      alert(`保存参数配置失败: ${e.message}`);
    }
  };

  const handleToggleServer = async () => {
    setErrorMessage("");
    if (serverRunning) {
      try {
        await invoke("stop_tts_server");
        setServerRunning(false);
        setLogs((prev) => [...prev, "[SYSTEM] Server stopped manually."]);
      } catch (e: any) {
        setErrorMessage(e.toString());
      }
    } else {
      try {
        setLogs((prev) => [...prev, "[SYSTEM] Starting CosyVoice 3 FastAPI Server..."]);
        await invoke("start_tts_server", { port });
        setServerRunning(true);
      } catch (e: any) {
        setErrorMessage(e.toString());
        setLogs((prev) => [...prev, `[SYSTEM ERROR] ${e.toString()}`]);
      }
    }
    setTimeout(refreshStatus, 800);
  };

  const handleTestSynthesis = async () => {
    if (!sandboxText.trim()) return;
    setLoadingSandbox(true);
    try {
      const formData = new FormData();
      formData.append("text", sandboxText);
      formData.append("demo_id", sandboxVoice);

      const response = await fetch(`http://127.0.0.1:${port}/api/generate`, {
        method: "POST",
        body: formData,
      });

      if (!response.ok) {
        throw new Error(`Server returned ${response.status} ${response.statusText}`);
      }

      const resJson = await response.json();
      if (resJson.audio_base64) {
        const audioUrl = `data:audio/wav;base64,${resJson.audio_base64}`;
        const audio = new Audio(audioUrl);
        await audio.play();
      } else {
        throw new Error("No audio_base64 returned in JSON");
      }
    } catch (e: any) {
      alert(`测试生成失败: ${e.message}`);
    } finally {
      setLoadingSandbox(false);
    }
  };

  const handleCopyConfigUrl = () => {
    const url = `http://${localIp}:${port}`;
    navigator.clipboard.writeText(url);
    alert(`已复制服务器配置地址: ${url}`);
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col font-sans select-none antialiased bg-gradient-to-br from-slate-950 via-slate-900 to-indigo-950/20">
      
      {/* Frosted Header */}
      <header className="border-b border-white/[0.06] bg-slate-900/40 backdrop-blur-xl px-6 py-4 flex items-center justify-between sticky top-0 z-50">
        <div className="flex items-center space-x-3">
          <div className="h-9 w-9 rounded-xl bg-gradient-to-br from-emerald-400 to-cyan-500 flex items-center justify-center font-bold text-slate-950 shadow-lg shadow-emerald-500/10">
            🎙️
          </div>
          <div>
            <h1 className="font-semibold text-base tracking-tight">StoryBrain 配音管理器</h1>
            <p className="text-[11px] text-slate-400">CosyVoice 3 (0.5B-RL) — 广播剧级 AI 配音引擎</p>
          </div>
        </div>
        
        {/* Glow Status Pill */}
        <div className={`flex items-center space-x-2.5 px-3 py-1.5 rounded-full border transition-all duration-300 ${
          serverRunning 
            ? "bg-emerald-500/10 border-emerald-500/20 text-emerald-400 shadow-[0_0_15px_rgba(16,185,129,0.1)]" 
            : "bg-rose-500/10 border-rose-500/20 text-rose-400"
        }`}>
          <span className={`h-2 w-2 rounded-full ${serverRunning ? "bg-emerald-400 animate-pulse" : "bg-rose-400"}`}></span>
          <span className="text-xs font-semibold uppercase tracking-wider">
            {serverRunning ? "在线运行中" : "服务器已停止"}
          </span>
        </div>
      </header>

      {/* Main Grid Layout */}
      <div className="flex-1 max-w-7xl w-full mx-auto p-6 flex flex-col md:flex-row gap-6">
        
        {/* Sidebar Nav */}
        <aside className="w-full md:w-60 flex flex-row md:flex-col gap-1.5 md:gap-2 overflow-x-auto md:overflow-visible shrink-0 pb-2 md:pb-0">
          <button
            onClick={() => setActiveTab("dashboard")}
            className={`flex items-center justify-center md:justify-start space-x-3 px-4 py-3 rounded-xl text-xs font-semibold tracking-wide transition-all duration-200 shrink-0 ${
              activeTab === "dashboard"
                ? "bg-white/[0.08] text-white border border-white/[0.08] shadow-[0_4px_12px_rgba(255,255,255,0.03)]"
                : "text-slate-400 hover:text-slate-200 hover:bg-white/[0.03] border border-transparent"
            }`}
          >
            <span>📊</span>
            <span>控制面板</span>
          </button>
          
          <button
            onClick={() => setActiveTab("tuning")}
            className={`flex items-center justify-center md:justify-start space-x-3 px-4 py-3 rounded-xl text-xs font-semibold tracking-wide transition-all duration-200 shrink-0 ${
              activeTab === "tuning"
                ? "bg-white/[0.08] text-white border border-white/[0.08] shadow-[0_4px_12px_rgba(255,255,255,0.03)]"
                : "text-slate-400 hover:text-slate-200 hover:bg-white/[0.03] border border-transparent"
            }`}
          >
            <span>🎛️</span>
            <span>参数微调</span>
          </button>

          <button
            onClick={() => setActiveTab("sandbox")}
            className={`flex items-center justify-center md:justify-start space-x-3 px-4 py-3 rounded-xl text-xs font-semibold tracking-wide transition-all duration-200 shrink-0 ${
              activeTab === "sandbox"
                ? "bg-white/[0.08] text-white border border-white/[0.08] shadow-[0_4px_12px_rgba(255,255,255,0.03)]"
                : "text-slate-400 hover:text-slate-200 hover:bg-white/[0.03] border border-transparent"
            }`}
          >
            <span>🧪</span>
            <span>声线沙盒</span>
          </button>

          <button
            onClick={() => setActiveTab("logs")}
            className={`flex items-center justify-center md:justify-start space-x-3 px-4 py-3 rounded-xl text-xs font-semibold tracking-wide transition-all duration-200 shrink-0 ${
              activeTab === "logs"
                ? "bg-white/[0.08] text-white border border-white/[0.08] shadow-[0_4px_12px_rgba(255,255,255,0.03)]"
                : "text-slate-400 hover:text-slate-200 hover:bg-white/[0.03] border border-transparent"
            }`}
          >
            <span>📰</span>
            <span>运行日志</span>
          </button>
        </aside>

        {/* Content Container (Apple-Style Glassmorphism Card) */}
        <main className="flex-1 bg-white/[0.02] border border-white/[0.06] rounded-3xl p-6 md:p-8 backdrop-blur-xl shadow-[0_12px_40px_rgba(0,0,0,0.3)] flex flex-col min-h-[500px]">
          
          {/* TAB 1: DASHBOARD */}
          {activeTab === "dashboard" && (
            <div className="flex flex-col space-y-6 flex-1 max-w-4xl mx-auto w-full">
              <div className="text-center space-y-1.5 pb-2">
                <h2 className="text-xl font-bold tracking-tight bg-gradient-to-r from-white via-slate-100 to-indigo-200 bg-clip-text text-transparent">控制中心 Dashboard</h2>
                <p className="text-xs text-slate-400">一键管理本地大模型服务，实时与手机端完成配音连接与协同</p>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                
                {/* Left Side: Server Control & Model Info */}
                <div className="space-y-5">
                  {/* Model & Device Card */}
                  <div className="bg-slate-950/40 border border-white/[0.04] rounded-2xl p-5 space-y-4">
                    <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center space-x-2">
                      <span>🤖</span>
                      <span>模型与计算设备状态</span>
                    </h3>
                    
                    <div className="grid grid-cols-2 gap-3.5">
                      <div className="bg-white/[0.02] border border-white/[0.04] p-3 rounded-xl">
                        <p className="text-[10px] text-slate-500 font-medium">模型名称</p>
                        <p className="text-xs font-bold text-slate-200 mt-1">{modelName}</p>
                      </div>
                      <div className="bg-white/[0.02] border border-white/[0.04] p-3 rounded-xl">
                        <p className="text-[10px] text-slate-500 font-medium">计算单元 (Backend)</p>
                        <p className="text-xs font-bold text-cyan-400 mt-1 flex items-center space-x-1.5">
                          <span className={`h-1.5 w-1.5 rounded-full ${device.includes("CUDA") ? "bg-emerald-400 animate-pulse" : "bg-cyan-400"}`}></span>
                          <span>{device}</span>
                        </p>
                      </div>
                      <div className="bg-white/[0.02] border border-white/[0.04] p-3 rounded-xl">
                        <p className="text-[10px] text-slate-500 font-medium">模型状态</p>
                        <p className={`text-xs font-bold mt-1 ${serverRunning ? "text-emerald-400" : "text-slate-400"}`}>
                          {modelStatus}
                        </p>
                      </div>
                      <div className="bg-white/[0.02] border border-white/[0.04] p-3 rounded-xl">
                        <p className="text-[10px] text-slate-500 font-medium">模型规模</p>
                        <p className="text-xs font-bold text-slate-300 mt-1">0.5B 参数 (~4.5 GB)</p>
                      </div>
                    </div>
                  </div>

                  {/* Port Configuration & Switch */}
                  <div className="bg-slate-950/40 border border-white/[0.04] rounded-2xl p-5 space-y-4">
                    <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center space-x-2">
                      <span>⚡</span>
                      <span>服务接口控制</span>
                    </h3>
                    
                    <div className="flex flex-col space-y-1.5">
                      <label className="text-[11px] text-slate-500 font-medium">服务监听端口</label>
                      <input
                        type="number"
                        value={port}
                        onChange={(e) => setPort(Number(e.target.value))}
                        disabled={serverRunning}
                        className="bg-slate-900 border border-white/[0.08] rounded-xl px-4 py-2.5 text-sm font-mono text-slate-200 outline-none focus:border-cyan-500 disabled:opacity-50 transition"
                      />
                    </div>

                    {!isTauri ? (
                      <div className="bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 text-xs py-3.5 rounded-xl text-center font-medium shadow-sm">
                        🌐 网页管理控制台连接正常
                      </div>
                    ) : (
                      <button
                        onClick={handleToggleServer}
                        className={`w-full py-3.5 rounded-xl font-bold tracking-wider text-xs transition-all duration-300 transform active:scale-[0.98] ${
                          serverRunning
                            ? "bg-rose-500 hover:bg-rose-600 hover:shadow-[0_0_20px_rgba(244,63,94,0.15)] text-white"
                            : "bg-gradient-to-r from-emerald-400 to-cyan-500 hover:from-emerald-500 hover:to-cyan-600 text-slate-950 font-extrabold hover:shadow-[0_0_25px_rgba(16,185,129,0.2)]"
                        }`}
                      >
                        {serverRunning ? "🛑 关闭大模型配音服务" : "⚡ 启动大模型配音服务"}
                      </button>
                    )}

                    {errorMessage && (
                      <div className="bg-rose-500/10 border border-rose-500/20 text-rose-300 text-xs p-3.5 rounded-xl font-mono leading-relaxed break-words">
                        ⚠️ {errorMessage}
                      </div>
                    )}
                  </div>
                </div>

                {/* Right Side: Android Connection Hub */}
                <div className="bg-slate-950/40 border border-white/[0.04] rounded-2xl p-5 flex flex-col space-y-4">
                  <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center space-x-2">
                    <span>📱</span>
                    <span>和安卓端无线连接</span>
                  </h3>

                  <div className="flex-1 flex flex-col justify-between space-y-4">
                    {/* Connection details */}
                    <div className="bg-white/[0.02] border border-white/[0.04] rounded-xl p-4 space-y-3">
                      <div className="flex justify-between items-center text-xs">
                        <span className="text-slate-400">本机服务 IP：</span>
                        <span className="font-mono text-cyan-400 font-bold bg-white/[0.05] px-2 py-0.5 rounded">{localIp}</span>
                      </div>
                      <div className="flex justify-between items-center text-xs">
                        <span className="text-slate-400">手机端接口地址：</span>
                        <span className="font-mono text-emerald-400 font-bold bg-white/[0.05] px-2 py-0.5 rounded">http://{localIp}:{port}</span>
                      </div>
                      <div className="flex justify-between items-center text-xs pt-2.5 border-t border-white/[0.04]">
                        <span className="text-slate-400">最近连接设备：</span>
                        <span className={`font-mono font-bold flex items-center space-x-1.5 ${
                          lastClientIp === "无" ? "text-slate-500" : "text-emerald-400"
                        }`}>
                          {lastClientIp !== "无" && <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-ping"></span>}
                          <span>{lastClientIp === "无" ? "等待设备连接..." : lastClientIp}</span>
                        </span>
                      </div>
                    </div>

                    {/* QR Code Container */}
                    {serverRunning ? (
                      <div className="flex flex-col items-center justify-center p-3 bg-white/[0.02] border border-white/[0.04] rounded-xl">
                        <div className="p-2 bg-white rounded-lg shadow-lg">
                          <img
                            src={`https://api.qrserver.com/v1/create-qr-code/?size=130x130&data=${encodeURIComponent(`http://${localIp}:${port}`)}`}
                            alt="连接二维码"
                            className="w-[130px] h-[130px]"
                          />
                        </div>
                        <p className="text-[10px] text-slate-400 mt-2.5 text-center leading-relaxed">
                          用手机端扫描此二维码以快速连接配置。<br/>
                          请确保手机和电脑在**同一 WiFi 局域网**内。
                        </p>
                      </div>
                    ) : (
                      <div className="flex-1 flex flex-col items-center justify-center p-5 border border-dashed border-white/[0.08] rounded-xl text-slate-500">
                        <span className="text-2xl mb-1.5">📴</span>
                        <span className="text-xs">请先启动配音服务，以获取连接二维码。</span>
                      </div>
                    )}

                    {serverRunning && (
                      <button
                        onClick={handleCopyConfigUrl}
                        className="w-full py-2.5 bg-white/[0.04] hover:bg-white/[0.08] border border-white/[0.06] rounded-xl text-xs font-semibold tracking-wide text-slate-300 transition-all duration-200"
                      >
                        📋 复制安卓端连接配置 URL
                      </button>
                    )}
                  </div>
                </div>

              </div>
            </div>
          )}

          {/* TAB 2: TTS PARAM TUNING */}
          {activeTab === "tuning" && (
            <div className="flex flex-col space-y-6 flex-1 overflow-y-auto max-w-2xl mx-auto w-full">
              <div>
                <h2 className="text-lg font-bold tracking-tight">CosyVoice 3 参数配置</h2>
                <p className="text-xs text-slate-400">微调 CosyVoice 3 推理参数，对测试沙盒与安卓客户端实时生效</p>
              </div>

              {!serverRunning && (
                <div className="bg-amber-500/10 border border-amber-500/20 text-amber-300 text-xs p-4 rounded-xl">
                  💡 服务未开启。保存的配置会在大模型服务启动时自动加载生效。
                </div>
              )}

              <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
                <div className="bg-slate-950/40 border border-white/[0.04] rounded-2xl p-4 space-y-2.5">
                  <div className="flex justify-between items-center">
                    <span className="text-xs font-semibold text-slate-300">采样温度 (Temperature)</span>
                    <span className="text-xs font-bold text-cyan-400">{settings.temperature}</span>
                  </div>
                  <input
                    type="range"
                    min="0.1"
                    max="1.5"
                    step="0.05"
                    value={settings.temperature}
                    onChange={(e) => handleSaveSettings({ ...settings, temperature: Number(e.target.value) })}
                    className="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-cyan-500"
                  />
                  <p className="text-[10px] text-slate-500">越高越有变化和表现力；旁白建议 0.6-0.8，角色对话可用 0.8-1.1。</p>
                </div>

                <div className="bg-slate-950/40 border border-white/[0.04] rounded-2xl p-4 space-y-2.5">
                  <div className="flex justify-between items-center">
                    <span className="text-xs font-semibold text-slate-300">核采样阈值 (Top P)</span>
                    <span className="text-xs font-bold text-purple-400">{settings.top_p}</span>
                  </div>
                  <input
                    type="range"
                    min="0.1"
                    max="1.0"
                    step="0.05"
                    value={settings.top_p}
                    onChange={(e) => handleSaveSettings({ ...settings, top_p: Number(e.target.value) })}
                    className="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-purple-500"
                  />
                  <p className="text-[10px] text-slate-500">限制采样候选范围，降低可减少跑调和奇怪停顿。</p>
                </div>

                <div className="bg-slate-950/40 border border-white/[0.04] rounded-2xl p-4 space-y-2.5">
                  <div className="flex justify-between items-center">
                    <span className="text-xs font-semibold text-slate-300">候选范围 (Top K)</span>
                    <span className="text-xs font-bold text-amber-400">{settings.top_k}</span>
                  </div>
                  <input
                    type="range"
                    min="1"
                    max="100"
                    step="1"
                    value={settings.top_k}
                    onChange={(e) => handleSaveSettings({ ...settings, top_k: Number(e.target.value) })}
                    className="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-amber-500"
                  />
                  <p className="text-[10px] text-slate-500">越小越稳定，越大越自然多样；默认 50。</p>
                </div>

                <div className="bg-slate-950/40 border border-white/[0.04] rounded-2xl p-4 space-y-2.5">
                  <div className="flex justify-between items-center">
                    <span className="text-xs font-semibold text-slate-300">语速 (Speed)</span>
                    <span className="text-xs font-bold text-emerald-400">{settings.speed}x</span>
                  </div>
                  <input
                    type="range"
                    min="0.5"
                    max="1.5"
                    step="0.05"
                    value={settings.speed}
                    onChange={(e) => handleSaveSettings({ ...settings, speed: Number(e.target.value) })}
                    className="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-emerald-500"
                  />
                  <p className="text-[10px] text-slate-500">控制输出播放语速；1.0 为原速。</p>
                </div>
              </div>

              <div className="bg-slate-950/20 border border-white/[0.04] rounded-2xl p-5 space-y-3.5">
                <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400">CosyVoice 3 语气辅助处理</h3>
                
                <div className="flex items-center justify-between">
                  <div className="flex flex-col">
                    <span className="text-xs font-medium text-slate-200">流式推理 (Stream)</span>
                    <span className="text-[10px] text-slate-500">开启后降低首包延迟，更适合手机端实时试听。</span>
                  </div>
                  <input
                    type="checkbox"
                    checked={settings.stream}
                    onChange={(e) => handleSaveSettings({ ...settings, stream: e.target.checked })}
                    className="h-4 w-4 accent-cyan-500 rounded border-slate-800"
                  />
                </div>

                <div className="flex items-center justify-between pt-3.5 border-t border-white/[0.04]">
                  <div className="flex flex-col">
                    <span className="text-xs font-medium text-slate-200">融合自然笑声 ([laughter] 标签)</span>
                    <span className="text-[10px] text-slate-500">检测“哈哈、呵呵、笑”等文本时自动加入笑声控制标签。</span>
                  </div>
                  <input
                    type="checkbox"
                    checked={settings.enable_laugh}
                    onChange={(e) => handleSaveSettings({ ...settings, enable_laugh: e.target.checked })}
                    className="h-4 w-4 accent-emerald-500 rounded border-slate-800"
                  />
                </div>

                <div className="flex items-center justify-between pt-3.5 border-t border-white/[0.04]">
                  <div className="flex flex-col">
                    <span className="text-xs font-medium text-slate-200">标点换气停顿 ([breath] 标签)</span>
                    <span className="text-[10px] text-slate-500">在句号等位置插入自然换气，增强广播剧朗读感。</span>
                  </div>
                  <input
                    type="checkbox"
                    checked={settings.enable_breath}
                    onChange={(e) => handleSaveSettings({ ...settings, enable_breath: e.target.checked })}
                    className="h-4 w-4 accent-emerald-500 rounded border-slate-800"
                  />
                </div>
              </div>
            </div>
          )}

          {/* TAB 3: VOICE SANDBOX */}
          {activeTab === "sandbox" && (
            <div className="flex flex-col space-y-6 flex-1 max-w-4xl mx-auto w-full">
              <div className="text-center space-y-1.5 pb-2">
                <h2 className="text-xl font-bold tracking-tight bg-gradient-to-r from-white via-slate-100 to-indigo-200 bg-clip-text text-transparent">声线沙盒 Sandbox</h2>
                <p className="text-xs text-slate-400">在此调试各声线的真实效果，测试停顿换气和情绪笑声等拟真细节</p>
              </div>

              {!serverRunning && (
                <div className="bg-rose-500/10 border border-rose-500/20 text-rose-300 text-xs p-4 rounded-xl text-center">
                  💡 必须先返回“控制面板”开启大模型服务，才能运行发音沙盒测试。
                </div>
              )}

              <div className="grid grid-cols-1 md:grid-cols-12 gap-6 flex-1 items-start">
                
                {/* Left Panel: Selected Voice Metadata & Seed Info (5 cols) */}
                <div className="md:col-span-5 space-y-5">
                  <div className="bg-slate-950/40 border border-white/[0.04] rounded-2xl p-5 space-y-4">
                    <h3 className="text-xs font-bold uppercase tracking-wider text-slate-400 flex items-center space-x-2">
                      <span>👤</span>
                      <span>发声人特征描述</span>
                    </h3>

                    {VOICE_DESCRIPTIONS[sandboxVoice] ? (
                      <div className="space-y-4">
                        <div className="flex flex-wrap gap-2">
                          <span className="text-[10px] font-bold px-2 py-0.5 rounded bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
                            {VOICE_DESCRIPTIONS[sandboxVoice].gender}
                          </span>
                          <span className="text-[10px] font-bold px-2 py-0.5 rounded bg-purple-500/10 text-purple-400 border border-purple-500/20">
                            {VOICE_DESCRIPTIONS[sandboxVoice].age}
                          </span>
                          <span className="text-[10px] font-bold px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                            {VOICE_DESCRIPTIONS[sandboxVoice].style}
                          </span>
                        </div>

                        <div className="bg-white/[0.02] border border-white/[0.04] p-3.5 rounded-xl space-y-1.5">
                          <p className="text-[10px] text-slate-500 font-semibold">生成种子 Seed</p>
                          <p className="text-xs font-mono font-bold text-slate-300">{VOICE_DESCRIPTIONS[sandboxVoice].seed}</p>
                        </div>

                        <div className="space-y-1">
                          <p className="text-[10px] text-slate-500 font-semibold">声线特性</p>
                          <p className="text-xs text-slate-300 leading-relaxed bg-white/[0.01] p-3 rounded-xl border border-white/[0.03]">
                            {VOICE_DESCRIPTIONS[sandboxVoice].desc}
                          </p>
                        </div>
                      </div>
                    ) : (
                      <p className="text-xs text-slate-500 italic">未知发声配置</p>
                    )}
                  </div>

                  {/* Preprocessing info tip */}
                  <div className="bg-indigo-500/5 border border-indigo-500/10 rounded-2xl p-4 text-[11px] text-slate-400 leading-relaxed space-y-2">
                    <p className="font-semibold text-slate-300">💡 语气笑声与呼吸停顿提示：</p>
                    <p>当文本中带有“哈、笑”等字眼且大模型语气处理开启时，系统会自动在句首添加情绪化的自然笑声。</p>
                    <p>标点符号（，。！？）会自动触发换气微停顿以模拟人类真实呼吸节奏。</p>
                  </div>
                </div>

                {/* Right Panel: Voice Selection & Input Synthesis (7 cols) */}
                <div className="md:col-span-7 space-y-5">
                  <div className="bg-slate-950/40 border border-white/[0.04] rounded-2xl p-5 space-y-4">
                    <div className="flex flex-col space-y-2">
                      <label className="text-xs text-slate-400 font-semibold tracking-wide">选择声线模型</label>
                      <select
                        value={sandboxVoice}
                        onChange={(e) => setSandboxVoice(e.target.value)}
                        className="bg-slate-900 border border-white/[0.08] rounded-xl px-4 py-2.5 text-xs text-slate-200 outline-none focus:border-cyan-500 transition"
                      >
                        <option value="demo-7">活力少侠男声 (demo-7 - 种子 7777)</option>
                        <option value="demo-4">老练市井男声 (demo-4 - 种子 4444)</option>
                        <option value="demo-3">甜美少女女声 (demo-3 - 种子 3333)</option>
                        <option value="demo-6">端庄御姐女声 (demo-6 - 种子 6666)</option>
                        <option value="demo-5">旁白解说播音 (demo-5 - 种子 5555)</option>
                      </select>
                    </div>

                    <div className="flex flex-col space-y-2">
                      <label className="text-xs text-slate-400 font-semibold tracking-wide">测试文本</label>
                      <textarea
                        value={sandboxText}
                        onChange={(e) => setSandboxText(e.target.value)}
                        rows={4}
                        placeholder="请输入测试句段..."
                        className="bg-slate-900 border border-white/[0.08] rounded-xl px-4 py-3 text-xs text-slate-200 outline-none focus:border-cyan-500 transition resize-none leading-relaxed"
                      />
                    </div>

                    {loadingSandbox && (
                      <div className="flex items-center space-x-1.5 justify-center py-1">
                        <span className="w-1.5 h-3 bg-cyan-400 rounded animate-bounce"></span>
                        <span className="w-1.5 h-5.5 bg-cyan-400 rounded animate-bounce [animation-delay:0.1s]"></span>
                        <span className="w-1.5 h-4 bg-cyan-400 rounded animate-bounce [animation-delay:0.2s]"></span>
                        <span className="w-1.5 h-6 bg-cyan-400 rounded animate-bounce [animation-delay:0.15s]"></span>
                        <span className="w-1.5 h-3 bg-cyan-400 rounded animate-bounce [animation-delay:0.3s]"></span>
                        <span className="text-[10px] text-cyan-400 font-semibold ml-2 tracking-wide uppercase">大模型推理加速计算中...</span>
                      </div>
                    )}

                    <button
                      onClick={handleTestSynthesis}
                      disabled={!serverRunning || loadingSandbox}
                      className="w-full py-3 bg-gradient-to-r from-cyan-400 to-indigo-500 hover:from-cyan-500 hover:to-indigo-600 disabled:from-slate-800 disabled:to-slate-800 text-slate-950 disabled:text-slate-500 rounded-xl text-xs font-bold tracking-wider transition duration-300 transform active:scale-[0.98]"
                    >
                      {loadingSandbox ? "⏳ 正在生成中..." : "▶️ 合成并播放声音"}
                    </button>
                  </div>
                </div>

              </div>
            </div>
          )}

          {/* TAB 4: REALTIME LOGS */}
          {activeTab === "logs" && (
            <div className="flex flex-col flex-1 h-full min-h-[420px]">
              {/* Log Header Controls */}
              <div className="flex flex-col md:flex-row md:items-center justify-between pb-4 border-b border-white/[0.06] gap-4 shrink-0">
                <div className="space-y-0.5">
                  <h2 className="text-sm font-bold tracking-tight uppercase text-slate-300 flex items-center space-x-2">
                    <span className="relative flex h-2 w-2">
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-cyan-400 opacity-75"></span>
                      <span className="relative inline-flex rounded-full h-2 w-2 bg-cyan-500"></span>
                    </span>
                    <span>大模型服务控制台日志</span>
                  </h2>
                  <p className="text-[10px] text-slate-500">追踪大模型生成耗时、请求参数与安卓客户端连接日志</p>
                </div>
                
                <div className="flex flex-wrap items-center gap-2">
                  {/* AutoScroll Button */}
                  <button
                    onClick={() => setAutoScroll(!autoScroll)}
                    className={`text-xs px-2.5 py-1.5 rounded-lg border transition ${
                      autoScroll 
                        ? "bg-cyan-500/10 border-cyan-500/20 text-cyan-400 font-semibold" 
                        : "bg-white/[0.04] border-white/[0.06] text-slate-400 hover:text-slate-200"
                    }`}
                  >
                    {autoScroll ? "✔️ 滚动锁定" : "❌ 滚动锁定"}
                  </button>

                  <button
                    onClick={() => setLogs([])}
                    className="text-xs px-2.5 py-1.5 bg-white/[0.04] border border-white/[0.06] hover:bg-white/[0.08] rounded-lg text-slate-400 hover:text-slate-200 transition"
                  >
                    清空输出
                  </button>
                </div>
              </div>

              {/* Log Filter & Search */}
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 my-3.5 shrink-0">
                {/* Level Tabs */}
                <div className="flex bg-slate-950/60 p-1 border border-white/[0.04] rounded-lg text-[10px] font-semibold tracking-wide">
                  <button
                    onClick={() => setLogFilter("all")}
                    className={`px-3 py-1.5 rounded-md transition ${
                      logFilter === "all" ? "bg-white/[0.08] text-white" : "text-slate-400 hover:text-slate-200"
                    }`}
                  >
                    全部 ({logs.length})
                  </button>
                  <button
                    onClick={() => setLogFilter("system")}
                    className={`px-3 py-1.5 rounded-md transition ${
                      logFilter === "system" ? "bg-white/[0.08] text-white" : "text-slate-400 hover:text-slate-200"
                    }`}
                  >
                    系统
                  </button>
                  <button
                    onClick={() => setLogFilter("request")}
                    className={`px-3 py-1.5 rounded-md transition ${
                      logFilter === "request" ? "bg-white/[0.08] text-white" : "text-slate-400 hover:text-slate-200"
                    }`}
                  >
                    接口请求
                  </button>
                  <button
                    onClick={() => setLogFilter("error")}
                    className={`px-3 py-1.5 rounded-md transition ${
                      logFilter === "error" ? "bg-white/[0.08] text-white" : "text-slate-400 hover:text-slate-200"
                    }`}
                  >
                    错误/告警
                  </button>
                </div>

                {/* Search Bar */}
                <div className="relative flex-1 max-w-xs">
                  <input
                    type="text"
                    value={logQuery}
                    onChange={(e) => setLogQuery(e.target.value)}
                    placeholder="过滤控制台日志..."
                    className="w-full bg-slate-900 border border-white/[0.08] rounded-lg pl-8 pr-3 py-1.5 text-[10px] text-slate-200 outline-none focus:border-cyan-500/50 transition font-sans"
                  />
                  <span className="absolute left-2.5 top-1/2 -translate-y-1/2 text-slate-500 text-[10px]">🔍</span>
                </div>
              </div>

              {/* Log Output Frame */}
              <div className="flex-1 bg-slate-950/80 rounded-2xl border border-white/[0.04] p-4.5 font-mono text-[10px] overflow-y-auto leading-relaxed text-slate-300 flex flex-col space-y-1 shadow-inner max-h-[360px] lg:max-h-none">
                {(() => {
                  const filtered = logs.filter(log => {
                    if (logFilter === "all") return true;
                    if (logFilter === "system") return log.includes("[SYSTEM]") && !log.includes("ERROR");
                    if (logFilter === "request") return log.includes("Received request") || log.includes("Successfully synthesized") || log.includes("/api/");
                    if (logFilter === "error") return log.includes("[SYSTEM ERROR]") || log.includes("ERROR") || log.includes("WARNING") || log.includes("failed") || log.includes("Failed");
                    return true;
                  }).filter(log => log.toLowerCase().includes(logQuery.toLowerCase()));

                  if (filtered.length === 0) {
                    return (
                      <span className="text-slate-600 italic">
                        {logQuery ? "无符合匹配的日志记录。" : "控制台暂无输出。请启动大模型服务，并在手机端发起配音合成。"}
                      </span>
                    );
                  }

                  return filtered.map((log, index) => {
                    let colorClass = "text-slate-300";
                    if (log.includes("[SYSTEM]")) colorClass = "text-amber-400/90 font-semibold";
                    else if (log.includes("[SYSTEM ERROR]") || log.includes("ERROR")) colorClass = "text-rose-400 font-bold";
                    else if (log.includes("Received request")) colorClass = "text-cyan-400";
                    else if (log.includes("Successfully synthesized")) colorClass = "text-emerald-400 font-semibold";
                    else if (log.includes("WARNING")) colorClass = "text-amber-500/95";
                    else if (log.includes("INFO")) colorClass = "text-slate-500";

                    return (
                      <div key={index} className={`${colorClass} hover:bg-white/[0.02] py-0.5 px-1 rounded transition`}>
                        {log}
                      </div>
                    );
                  });
                })()}
                <div ref={logEndRef} />
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  );
}
