# -*- mode: python ; coding: utf-8 -*-
"""
StoryBrain 配音管理器 — PyInstaller 打包配置 (CosyVoice 3)
"""

import os
import sys

# CosyVoice 路径
COSYVOICE_DIR = "D:/stnavel/CosyVoice"
COSYVOICE_MODEL_DIR = "D:/stnavel/CosyVoice/pretrained_models/Fun-CosyVoice3-0.5B"

a = Analysis(
    ['engine/app_cosyvoice.py'],
    pathex=[
        COSYVOICE_DIR,
        os.path.join(COSYVOICE_DIR, 'third_party', 'Matcha-TTS'),
        'engine',
    ],
    binaries=[],
    datas=[
        # 打包 CosyVoice 核心代码
        (os.path.join(COSYVOICE_DIR, 'cosyvoice'), 'cosyvoice'),
        (os.path.join(COSYVOICE_DIR, 'third_party'), 'third_party'),
        # 不打包模型 (太大, 运行时动态加载)
    ],
    hiddenimports=[
        # PyTorch
        'torch', 'torch.nn', 'torch.utils',
        'torchaudio',
        # CosyVoice 内部
        'cosyvoice', 'cosyvoice.cli', 'cosyvoice.cli.cosyvoice',
        'cosyvoice.flow', 'cosyvoice.flow.flow_matching',
        'cosyvoice.llm', 'cosyvoice.llm.llm',
        'cosyvoice.tokenizer',
        'cosyvoice.utils', 'cosyvoice.utils.common', 'cosyvoice.utils.file_utils',
        'cosyvoice.hifigan',
        # Matcha-TTS
        'matcha', 'matcha.hifigan',
        # 网络
        'fastapi', 'uvicorn', 'starlette',
        'websockets', 'asyncio',
        # 音频
        'soundfile', 'numpy', 'scipy',
        # WebView
        'webview',
        # 其他
        'json', 'hashlib', 're', 'io', 'base64',
        'concurrent.futures',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        'tkinter', 'matplotlib', 'pandas',
        'jupyter', 'ipython', 'notebook',
        'tensorflow', 'tensorboard',
        'transformers',  # CosyVoice uses its own, not HF transformers
    ],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='StoryBrain配音管理器',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='StoryBrain配音管理器',
)
