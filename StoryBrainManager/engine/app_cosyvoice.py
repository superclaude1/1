# -*- coding: utf-8 -*-
"""
StoryBrain 配音管理器 — CosyVoice 3 引擎
基于 Fun-CosyVoice3-0.5B-2512_RL (CPU 优化版)

按 AMD Ryzen 7 7735HS 平台报告配置：
- 限制 4 物理核心线程，防止降频
- 关闭 vLLM (纯 CPU PyTorch 推理)
- 流式 chunk 推理 (首包 ~150-250ms)
- 零样本声音克隆 + 情感控制标签
"""

import os
# ===== CPU 线程限制 (必须在导入 torch 之前) =====
os.environ["MKL_NUM_THREADS"] = "4"
os.environ["OMP_NUM_THREADS"] = "4"
os.environ["OPENBLAS_NUM_THREADS"] = "4"
os.environ["VECLIB_MAXIMUM_THREADS"] = "4"
os.environ["NUMEXPR_NUM_THREADS"] = "4"
os.environ["KMP_DUPLICATE_LIB_OK"] = "TRUE"

import sys
import argparse
import base64
import io
import logging
import socket
import threading
import json
import hashlib
import shutil
import time
import numpy as np
from collections import deque, OrderedDict
from pathlib import Path

import torch
# 严格限制 PyTorch CPU 线程
torch.set_num_threads(4)
torch.set_num_interop_threads(1)

# CosyVoice 路径
COSYVOICE_DIR = "D:/stnavel/CosyVoice"
COSYVOICE_MODEL_DIR = "D:/stnavel/CosyVoice/pretrained_models/Fun-CosyVoice3-0.5B"

sys.path.insert(0, COSYVOICE_DIR)
sys.path.insert(0, os.path.join(COSYVOICE_DIR, "third_party", "Matcha-TTS"))

import soundfile as sf
from fastapi import FastAPI, Form, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, JSONResponse

# ===== 日志 =====
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("CosyVoice-Server")

log_buffer = deque(maxlen=500)

class DequeLoggingHandler(logging.Handler):
    def emit(self, record):
        try:
            log_buffer.append(self.format(record))
        except Exception:
            self.handleError(record)

log_handler = DequeLoggingHandler()
log_handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
logging.getLogger().addHandler(log_handler)


# ===== 网络工具 =====
def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


# ===== 目录准备 =====
TEMP_REF_DIR = Path("temp_references")
CUSTOM_VOICES_DIR = Path("custom_voices")
SPK_CACHE_DIR = Path("speaker_cache")
for d in [TEMP_REF_DIR, CUSTOM_VOICES_DIR, SPK_CACHE_DIR]:
    if d.exists():
        try:
            shutil.rmtree(d)
        except Exception:
            pass
    d.mkdir(parents=True, exist_ok=True)


# ===== 加载 CosyVoice 模型 =====
logger.info("Loading CosyVoice 3 model...")

try:
    from cosyvoice.cli.cosyvoice import AutoModel

    cosyvoice = AutoModel(model_dir=COSYVOICE_MODEL_DIR)
    logger.info(f"CosyVoice 3 loaded. Sample rate: {cosyvoice.sample_rate} Hz")

    # 预注册内置演示音色 (从参考音频)
    DEMO_VOICES = {
        "demo-1": {"gender": "male", "style": "青年男声-清亮", "ref": "D:/stnavel/CosyVoice/asset/zero_shot_prompt.wav"},
        "demo-2": {"gender": "male", "style": "中年男声-沉稳", "ref": "D:/stnavel/CosyVoice/asset/zero_shot_prompt.wav"},
        "demo-3": {"gender": "female", "style": "少女-甜美", "ref": "D:/stnavel/CosyVoice/asset/zero_shot_prompt.wav"},
        "demo-4": {"gender": "male", "style": "市井-幽默", "ref": "D:/stnavel/CosyVoice/asset/zero_shot_prompt.wav"},
        "demo-5": {"gender": "male", "style": "旁白-浑厚", "ref": "D:/stnavel/CosyVoice/asset/zero_shot_prompt.wav"},
        "demo-6": {"gender": "female", "style": "御姐-端庄", "ref": "D:/stnavel/CosyVoice/asset/zero_shot_prompt.wav"},
        "demo-7": {"gender": "male", "style": "少年-活力", "ref": "D:/stnavel/CosyVoice/asset/zero_shot_prompt.wav"},
    }

    # 尝试预加载演示音色到缓存
    speaker_cache = OrderedDict()  # LRU cache for speaker embeddings
    MAX_SPK_CACHE = 20

    for spk_id, info in DEMO_VOICES.items():
        if os.path.exists(info["ref"]):
            try:
                prompt_text = "希望你以后能够做的比我还好呦。"
                ok = cosyvoice.add_zero_shot_spk(prompt_text, info["ref"], spk_id)
                if ok:
                    speaker_cache[spk_id] = True
                    logger.info(f"  Pre-cached speaker: {spk_id} ({info['style']})")
            except Exception as e:
                logger.warning(f"  Failed to cache {spk_id}: {e}")

    logger.info(f"CosyVoice 3 ready. {len(speaker_cache)} speakers cached.")

except Exception as e:
    logger.error(f"Failed to load CosyVoice 3: {e}", exc_info=True)
    # 降级: 尝试给出清晰错误
    cosyvoice = None
    logger.error(
        "CosyVoice 3 未正确安装。请运行:\n"
        "  git clone --recursive https://github.com/FunAudioLLM/CosyVoice.git D:/stnavel/CosyVoice\n"
        "  cd D:/stnavel/CosyVoice\n"
        "  conda create -n cosyvoice python=3.10 -y && conda activate cosyvoice\n"
        "  pip install -r requirements.txt\n"
        "  python -c \"from modelscope import snapshot_download; "
        "snapshot_download('FunAudioLLM/Fun-CosyVoice3-0.5B-2512', local_dir='pretrained_models/Fun-CosyVoice3-0.5B')\""
    )


# ===== 配置管理 =====
CONFIG_FILE = "tts_config.json"

DEFAULT_CONFIG = {
    "temperature": 0.8,
    "top_p": 0.9,
    "top_k": 50,
    "speed": 1.0,
    "stream": True,
    "enable_laugh": True,
    "enable_breath": True,
}

def load_config():
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                cfg = json.load(f)
                # 合并默认值
                merged = {**DEFAULT_CONFIG, **cfg}
                return merged
        except Exception:
            pass
    return DEFAULT_CONFIG.copy()

def save_config(config):
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(config, f, indent=4, ensure_ascii=False)
    except Exception:
        pass


# ===== 音频处理 =====
def prepare_reference_audio(demo_id: str) -> tuple:
    """
    为指定角色准备参考音频，返回 (wav_path, prompt_text)。
    优先级: custom_voices/ > demo 内置 > 默认
    """
    parts = demo_id.split("_")
    name = parts[-1].strip() if parts else demo_id.strip()

    # 1. 检查自定义音色
    for ext in [".wav", ".mp3"]:
        custom_path = CUSTOM_VOICES_DIR / f"{name}{ext}"
        if custom_path.exists():
            logger.info(f"Using custom voice: {custom_path}")
            return str(custom_path), "希望你以后能够做的比我还好呦。"

    # 2. 检查已缓存的 speaker
    if demo_id in speaker_cache:
        return DEMO_VOICES.get(demo_id, {}).get("ref", ""), "希望你以后能够做的比我还好呦。"

    # 3. 使用 demo 参考
    info = DEMO_VOICES.get(demo_id, DEMO_VOICES.get("demo-5", {}))
    ref_path = info.get("ref", "")
    if ref_path and os.path.exists(ref_path):
        return ref_path, "希望你以后能够做的比我还好呦。"

    # 4. 最后的 fallback
    fallback = "D:/stnavel/CosyVoice/asset/zero_shot_prompt.wav"
    return fallback, "希望你以后能够做的比我还好呦。"


def apply_emotion_tags(text: str, config: dict) -> str:
    """根据配置在文本中插入情感控制标签"""
    if config.get("enable_laugh"):
        # CosyVoice 3 支持 [laughter] 标签
        text = text.replace("哈哈", "[laughter]哈哈[laughter]")
        text = text.replace("呵呵", "[laughter]呵呵[laughter]")
        text = text.replace("笑", "[laughter]笑[laughter]")

    if config.get("enable_breath"):
        # 在句号、逗号处自然插入呼吸
        text = text.replace("。", "。[breath]")
        # 避免过多呼吸标记
        import re
        text = re.sub(r'\[breath\]\[breath\]', '[breath]', text)

    return text


# ===== 最后客户端 IP 追踪 =====
last_client_ip = "无"


# ===== FastAPI 应用 =====
app = FastAPI(title="StoryBrain CosyVoice 3 Server")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.post("/api/generate")
async def generate_audio(
    request: Request,
    text: str = Form(...),
    demo_id: str = Form("demo-5"),
    speed: float = Form(1.0),
):
    global last_client_ip
    last_client_ip = request.client.host if request.client else "未知"
    logger.info(f"TTS request from {last_client_ip}: demo_id={demo_id}, text_len={len(text)}")

    if cosyvoice is None:
        return JSONResponse(
            {"status": "error", "message": "CosyVoice 3 model not loaded. Check server logs."},
            status_code=503
        )

    try:
        config = load_config()
        ref_wav, prompt_text = prepare_reference_audio(demo_id)
        processed_text = apply_emotion_tags(text, config)

        # 使用 instruct 格式: instruct_text + <|endofprompt|> + text_to_speak
        speaker_style = DEMO_VOICES.get(demo_id, {}).get("style", "")
        instruct = f"You are a helpful assistant. 请用自然的语气朗读，适度表现情感。<|endofprompt|>"

        # 流式合成
        audio_chunks = []
        sample_rate = cosyvoice.sample_rate

        for i, result in enumerate(cosyvoice.inference_zero_shot(
            instruct + processed_text,
            prompt_text,
            ref_wav,
            stream=config.get("stream", True)
        )):
            speech = result["tts_speech"]
            if isinstance(speech, torch.Tensor):
                speech = speech.cpu().numpy()
            audio_chunks.append(speech)

        if not audio_chunks:
            raise RuntimeError("CosyVoice 3 returned no audio")

        # 合并所有 chunk
        full_audio = np.concatenate(audio_chunks, axis=-1) if len(audio_chunks) > 1 else audio_chunks[0]

        # 导出为 WAV bytes
        buffer = io.BytesIO()
        sf.write(buffer, full_audio, sample_rate, format="WAV")
        buffer.seek(0)
        wav_bytes = buffer.read()

        audio_base64 = base64.b64encode(wav_bytes).decode("utf-8")
        duration_sec = len(full_audio) / sample_rate if sample_rate else 0
        logger.info(f"Synthesized {duration_sec:.1f}s audio for {demo_id} (CosyVoice 3)")

        return {
            "status": "success",
            "audio_base64": audio_base64,
            "duration_sec": round(duration_sec, 2),
            "model": "CosyVoice-3-0.5B-RL",
        }

    except Exception as e:
        logger.error(f"Synthesis failed: {e}", exc_info=True)
        return JSONResponse(
            {"status": "error", "message": str(e)},
            status_code=500
        )


@app.post("/api/generate_stream")
async def generate_audio_stream(
    request: Request,
    text: str = Form(...),
    demo_id: str = Form("demo-5"),
):
    """流式返回音频 chunk (SSE)"""
    from fastapi.responses import StreamingResponse

    global last_client_ip
    last_client_ip = request.client.host if request.client else "未知"

    if cosyvoice is None:
        return JSONResponse(
            {"status": "error", "message": "Model not loaded"},
            status_code=503
        )

    config = load_config()
    ref_wav, prompt_text = prepare_reference_audio(demo_id)
    processed_text = apply_emotion_tags(text, config)
    instruct = "You are a helpful assistant. 请用自然的语气朗读。<|endofprompt|>"

    async def audio_stream():
        try:
            for result in cosyvoice.inference_zero_shot(
                instruct + processed_text,
                prompt_text,
                ref_wav,
                stream=True
            ):
                speech = result["tts_speech"]
                if isinstance(speech, torch.Tensor):
                    speech = speech.cpu().numpy()
                buf = io.BytesIO()
                sf.write(buf, speech, cosyvoice.sample_rate, format="WAV")
                buf.seek(0)
                chunk_b64 = base64.b64encode(buf.read()).decode("utf-8")
                yield f"data: {json.dumps({'audio_base64': chunk_b64})}\n\n"
            yield "data: [DONE]\n\n"
        except Exception as e:
            yield f"data: {json.dumps({'error': str(e)})}\n\n"

    return StreamingResponse(audio_stream(), media_type="text/event-stream")


@app.get("/api/status")
async def get_status():
    return {
        "status": "online" if cosyvoice else "degraded",
        "port": 18083,
        "local_ip": get_local_ip(),
        "device": "CPU (Ryzen 7, 4 threads optimized)",
        "last_client_ip": last_client_ip,
        "model_name": "Fun-CosyVoice3-0.5B-2512_RL",
        "model_status": "已加载 (Loaded)" if cosyvoice else "加载失败",
        "sample_rate": cosyvoice.sample_rate if cosyvoice else None,
        "cached_speakers": len(speaker_cache),
    }


@app.get("/api/logs")
async def get_logs():
    return {"logs": list(log_buffer)}


@app.get("/api/settings")
async def get_settings():
    return load_config()


@app.post("/api/settings")
async def save_settings(
    temperature: float = Form(0.8),
    top_p: float = Form(0.9),
    top_k: int = Form(50),
    speed: float = Form(1.0),
    stream: bool = Form(True),
    enable_laugh: bool = Form(True),
    enable_breath: bool = Form(True),
):
    config = {
        "temperature": temperature,
        "top_p": top_p,
        "top_k": top_k,
        "speed": speed,
        "stream": stream,
        "enable_laugh": enable_laugh,
        "enable_breath": enable_breath,
    }
    save_config(config)
    logger.info("TTS Config updated.")
    return {"status": "success", "config": config}


@app.post("/api/speaker/upload")
async def upload_speaker(request: Request):
    """上传自定义参考音频来注册新音色"""
    # ... 简化处理: 接收 Base64 WAV
    pass


# ===== 静态文件服务 =====
dist_dir = Path("D:/stnavel/StoryBrainManager/dist")
if dist_dir.exists():
    @app.get("/")
    async def read_index():
        return FileResponse(str(dist_dir / "index.html"))
    app.mount("/assets", StaticFiles(directory=str(dist_dir / "assets")), name="assets")


# ===== 启动入口 =====
if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0", help="Binding host (0.0.0.0 for LAN access)")
    parser.add_argument("--port", type=int, default=18083)
    parser.add_argument("--background", action="store_true")
    args = parser.parse_args()

    def run_fastapi(host: str, port: int):
        import uvicorn
        logger.info(f"CosyVoice 3 server starting on {host}:{port}")
        uvicorn.run(app, host=host, port=port, log_level="warning")

    if args.background:
        import uvicorn
        logger.info("Background server mode")
        uvicorn.run(app, host=args.host, port=args.port)
    else:
        server_thread = threading.Thread(target=run_fastapi, args=(args.host, args.port), daemon=True)
        server_thread.start()
        time.sleep(2.0)

        try:
            import webview
            logger.info("Launching desktop GUI window...")
            window = webview.create_window(
                title="StoryBrain 配音管理器 (CosyVoice 3)",
                url=f"http://127.0.0.1:{args.port}",
                width=1200,
                height=780,
                min_size=(900, 600),
                resizable=True
            )
            webview.start()
            logger.info("GUI closed. Exiting.")
        except ImportError:
            logger.info("webview not installed, running server only. Press Ctrl+C to stop.")
            try:
                while True:
                    time.sleep(1)
            except KeyboardInterrupt:
                logger.info("Server stopped.")
