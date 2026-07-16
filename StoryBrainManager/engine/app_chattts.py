import os
# Configure CPU environment optimizations before importing torch or onnxruntime
os.environ["PYTHONUTF8"] = "1"
os.environ["MKL_NUM_THREADS"] = "4"
os.environ["OMP_NUM_THREADS"] = "4"
os.environ["OPENBLAS_NUM_THREADS"] = "4"
os.environ["VECLIB_MAXIMUM_THREADS"] = "4"
os.environ["NUMEXPR_NUM_THREADS"] = "4"

import sys
# Add MOSS-TTS-Nano path to sys.path
sys.path.append("D:/stnavel/MOSS-TTS-Nano")

import argparse
import base64
import io
import logging
import soundfile as sf
import socket
import webbrowser
import threading
import json
import hashlib
import shutil
import numpy as np
import scipy.signal as signal
from collections import deque
from fastapi import FastAPI, Form, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, JSONResponse
from onnx_tts_runtime import OnnxTtsRuntime

# Configure logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("Genie-Server")

# In-memory log buffer to query from Web GUI
log_buffer = deque(maxlen=300)

class DequeLoggingHandler(logging.Handler):
    def emit(self, record):
        try:
            msg = self.format(record)
            log_buffer.append(msg)
        except Exception:
            self.handleError(record)

# Add handler to root logger
log_handler = DequeLoggingHandler()
log_handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
logging.getLogger().addHandler(log_handler)

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

# Clean up temp references
TEMP_REF_DIR = "temp_references"
if os.path.exists(TEMP_REF_DIR):
    try:
        shutil.rmtree(TEMP_REF_DIR)
    except Exception:
        pass
os.makedirs(TEMP_REF_DIR, exist_ok=True)

app = FastAPI(title="MOSS-TTS-Nano Offline Server")

# Enable CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

logger.info("Initializing MOSS-TTS-Nano ONNX runtime...")
try:
    moss_runtime = OnnxTtsRuntime(
        model_dir="D:/stnavel/MOSS-TTS-Nano/models/MOSS-TTS-Nano-100M-ONNX",
        thread_count=4,
        max_new_frames=375,
        do_sample=True,
        sample_mode="fixed",
        execution_provider="cpu"
    )
    logger.info("MOSS-TTS-Nano ONNX runtime loaded successfully.")
except Exception as e:
    logger.error(f"Failed to load MOSS-TTS-Nano ONNX runtime: {e}", exc_info=True)
    raise

# Base audio directory of MOSS-TTS
MOSS_AUDIO_DIR = "D:/stnavel/MOSS-TTS-Nano/assets/audio"

# Female Chinese base references
FEMALE_REFERENCES = [
    {
        "wav": os.path.join(MOSS_AUDIO_DIR, "zh_4.wav"),
        "slice_s": 8.2
    },
    {
        "wav": os.path.join(MOSS_AUDIO_DIR, "zh_6.wav"),
        "slice_s": 7.2
    },
    {
        "wav": os.path.join(MOSS_AUDIO_DIR, "zh_11.wav"),
        "slice_s": 5.5
    }
]

# Male Chinese base references
MALE_REFERENCES = [
    {
        "wav": os.path.join(MOSS_AUDIO_DIR, "zh_1.wav"),
        "slice_s": 7.9
    },
    {
        "wav": os.path.join(MOSS_AUDIO_DIR, "zh_3.wav"),
        "slice_s": 7.8
    },
    {
        "wav": os.path.join(MOSS_AUDIO_DIR, "zh_10.wav"),
        "slice_s": 7.8
    }
]

resampled_cache = {}
last_client_ip = "无"

CUSTOM_VOICES_DIR = "custom_voices"
os.makedirs(CUSTOM_VOICES_DIR, exist_ok=True)

def prepare_speaker_reference(demo_id: str) -> str:
    """
    Dynamically select base reference audio by gender, slice it if it is too long,
    and pitch-shift it based on name hash. If a custom voice exists under custom_voices/
    matching the character name, it is used directly.
    """
    # 1. Check if a custom voice exists for this character name
    parts = demo_id.split("_")
    name = parts[-1].strip() if len(parts) > 0 else demo_id.strip()
    
    custom_wav = os.path.join(CUSTOM_VOICES_DIR, f"{name}.wav")
    custom_mp3 = os.path.join(CUSTOM_VOICES_DIR, f"{name}.mp3")
    
    selected_custom = None
    if os.path.exists(custom_wav):
        selected_custom = custom_wav
    elif os.path.exists(custom_mp3):
        selected_custom = custom_mp3
        
    if selected_custom is not None:
        cache_key = f"custom_{selected_custom}"
        if cache_key in resampled_cache and os.path.exists(resampled_cache[cache_key]):
            return resampled_cache[cache_key]
        try:
            data, sr = sf.read(selected_custom)
            if data.ndim > 1:
                data = np.mean(data, axis=1)
            # Slice custom audio to max 8 seconds to prevent context overflow
            max_len = int(8.0 * sr)
            if len(data) > max_len:
                data = data[:max_len]
            temp_path = os.path.join(TEMP_REF_DIR, f"ref_custom_{name}.wav")
            sf.write(temp_path, data, sr)
            resampled_cache[cache_key] = temp_path
            logger.info(f"Loaded custom speaker reference for '{name}' from {selected_custom}")
            return temp_path
        except Exception as e:
            logger.error(f"Failed to load custom voice {selected_custom}: {e}")

    # Fallback to default speaker hashing
    is_female = False
    lower_id = demo_id.lower()
    
    # Identify gender based on demo_id naming conventions from Android app
    if "female" in lower_id or "demo-3" in lower_id or "demo-6" in lower_id:
        is_female = True

    # Use hash of the full speaker ID to deterministicly assign speaker parameters
    h = hashlib.md5(demo_id.encode("utf-8")).hexdigest()
    val = int(h, 16)
    
    # Select base speaker reference
    refs = FEMALE_REFERENCES if is_female else MALE_REFERENCES
    base_ref = refs[val % len(refs)]
    
    # Determine pitch shift factor (0.88 to 1.12)
    # Higher value = faster speed & higher pitch
    factor = 0.88 + (val % 25) / 100.0
    
    cache_key = f"{base_ref['wav']}_{factor:.2f}"
    if cache_key in resampled_cache and os.path.exists(resampled_cache[cache_key]):
        return resampled_cache[cache_key]

    try:
        # Read reference audio
        data, sr = sf.read(base_ref["wav"])
        if data.ndim > 1:
            data = np.mean(data, axis=1)
            
        # Slice it if needed to keep under 10 seconds
        if "slice_s" in base_ref:
            slice_samples = int(base_ref["slice_s"] * sr)
            data = data[:slice_samples]
            
        # Resample for pitch shifting
        new_len = int(len(data) / factor)
        resampled_data = signal.resample(data, new_len)
        
        # Write to temporary references directory
        temp_path = os.path.join(TEMP_REF_DIR, f"ref_{val}_{factor:.2f}.wav")
        sf.write(temp_path, resampled_data, sr)
        
        resampled_cache[cache_key] = temp_path
        logger.info(f"Created resampled reference for {demo_id}: {temp_path} (factor={factor:.2f}, sliced={ 'slice_s' in base_ref })")
        return temp_path
    except Exception as e:
        logger.error(f"Failed to process reference audio: {e}")
        return base_ref["wav"]

# Settings/Config File Management
CONFIG_FILE = "tts_config.json"

def load_tts_config():
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {
        "temperature_dialogue": 0.65,
        "temperature_narration": 0.60,
        "top_p_dialogue": 0.75,
        "top_p_narration": 0.70,
        "top_k": 20,
        "enable_text_normalization": "0",
        "enable_normalize_tts_text": "1",
        "enable_laugh": True,
        "enable_break": True
    }

def save_tts_config(config):
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(config, f, indent=4, ensure_ascii=False)
    except Exception:
        pass

@app.post("/api/generate")
async def generate_audio(
    request: Request,
    text: str = Form(...),
    demo_id: str = Form("demo-5"),
    enable_text_normalization: str = Form(None),
    enable_normalize_tts_text: str = Form(None),
):
    global last_client_ip
    last_client_ip = request.client.host
    logger.info(f"Received request from {last_client_ip} - text: '{text}', demo_id: {demo_id}")
    
    try:
        # 1. Allocate and prepare reference audio path
        ref_wav = prepare_speaker_reference(demo_id)
        
        # 2. Perform synthesis using MOSS-TTS-Nano
        temp_output_wav = os.path.join(TEMP_REF_DIR, f"out_{hashlib.md5(text.encode('utf-8')).hexdigest()[:8]}.wav")
        moss_runtime.synthesize(
            text=text,
            voice=None,
            prompt_audio_path=ref_wav,
            output_audio_path=temp_output_wav,
            sample_mode="fixed",
            do_sample=True,
            streaming=True,
            max_new_frames=375,
            voice_clone_max_text_tokens=75,
            enable_wetext=False,
            enable_normalize_tts_text=True,
        )
        
        if not os.path.exists(temp_output_wav):
            raise FileNotFoundError("Synthesized audio file was not created by MOSS-TTS-Nano")
            
        # 3. Read synthesized audio file and convert to Base64
        with open(temp_output_wav, "rb") as f:
            wav_bytes = f.read()
            
        try:
            os.remove(temp_output_wav)
        except Exception:
            pass
            
        audio_base64 = base64.b64encode(wav_bytes).decode("utf-8")
        logger.info(f"Successfully synthesized audio using MOSS-TTS-Nano for demo_id {demo_id}")
        return {
            "status": "success",
            "audio_base64": audio_base64
        }
    except Exception as e:
        logger.error(f"Synthesis failed: {e}", exc_info=True)
        return {
            "status": "error",
            "message": str(e)
        }

@app.get("/api/status")
async def get_status():
    return {
        "status": "online",
        "port": 18083,
        "local_ip": get_local_ip(),
        "device": "CPU (Optimized)",
        "last_client_ip": last_client_ip,
        "model_name": "MOSS-TTS-Nano (ONNX-CPU)",
        "model_status": "已加载 (Loaded)"
    }

@app.get("/api/logs")
async def get_logs():
    return {
        "logs": list(log_buffer)
    }

@app.get("/api/settings")
async def get_settings():
    return load_tts_config()

@app.post("/api/settings")
async def save_settings(
    temperature_dialogue: float = Form(...),
    temperature_narration: float = Form(...),
    top_p_dialogue: float = Form(...),
    top_p_narration: float = Form(...),
    top_k: int = Form(...),
    enable_text_normalization: str = Form(...),
    enable_normalize_tts_text: str = Form(...),
    enable_laugh: int = Form(...),
    enable_break: int = Form(...)
):
    config = {
        "temperature_dialogue": temperature_dialogue,
        "temperature_narration": temperature_narration,
        "top_p_dialogue": top_p_dialogue,
        "top_p_narration": top_p_narration,
        "top_k": top_k,
        "enable_text_normalization": enable_text_normalization,
        "enable_normalize_tts_text": enable_normalize_tts_text,
        "enable_laugh": enable_laugh == 1,
        "enable_break": enable_break == 1
    }
    save_tts_config(config)
    logger.info("TTS Config updated via settings API.")
    return {"status": "success", "config": config}

# Serve React static assets
dist_dir = "D:/stnavel/StoryBrainManager/dist"
if os.path.exists(dist_dir):
    @app.get("/")
    async def read_index():
        return FileResponse(os.path.join(dist_dir, "index.html"))
    app.mount("/assets", StaticFiles(directory=os.path.join(dist_dir, "assets")), name="assets")

if __name__ == "__main__":
    import time
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1", help="Binding host")
    parser.add_argument("--port", type=int, default=18083, help="Port to run server on")
    parser.add_argument("--background", action="store_true", help="Run in background server mode (no GUI window)")
    args = parser.parse_args()
    
    def run_fastapi(host: str, port: int):
        import uvicorn
        logger.info(f"Starting FastAPI backend on {host}:{port}...")
        uvicorn.run(app, host=host, port=port, log_level="warning")

    if args.background:
        # Standalone background server mode
        import uvicorn
        logger.info("Starting background server mode...")
        uvicorn.run(app, host=args.host, port=args.port)
    else:
        # Desktop GUI Mode
        # 1. Start FastAPI server in a background daemon thread
        server_thread = threading.Thread(target=run_fastapi, args=(args.host, args.port), daemon=True)
        server_thread.start()
        
        # 2. Wait a moment for server to bind
        time.sleep(1.0)
        
        # 3. Import webview and launch native desktop window
        import webview
        logger.info("Launching desktop GUI window...")
        
        # Configure app window size and parameters
        window = webview.create_window(
            title="StoryBrain 配音管理器",
            url=f"http://127.0.0.1:{args.port}",
            width=1200,
            height=780,
            min_size=(900, 600),
            resizable=True
        )
        
        # Start the GUI loop. This blocks until the window is closed.
        webview.start()
        logger.info("GUI Window closed. Exiting...")
