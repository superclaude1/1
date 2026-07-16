@echo off
chcp 65001 >nul
title StoryBrain — CosyVoice 3 一键安装脚本

echo ====================================================
echo    StoryBrain CosyVoice 3 环境安装
echo    AMD Ryzen 7 7735HS + 32GB RAM (CPU Optimized)
echo ====================================================
echo.

REM ===== 检查 conda =====
where conda >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [×] 未找到 conda！请先安装 Miniconda:
    echo     https://docs.conda.io/en/latest/miniconda.html
    echo     安装后重新运行此脚本。
    pause
    exit /b 1
)

echo [√] conda 已检测到

REM ===== 创建 CosyVoice conda 环境 =====
echo.
echo [1/5] 创建 Python 3.10 环境...
call conda create -n cosyvoice python=3.10 -y 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [!] 环境可能已存在，继续...
)
call conda activate cosyvoice
echo [√] 环境就绪

REM ===== 克隆 CosyVoice =====
echo.
echo [2/5] 克隆 CosyVoice 仓库...
if exist "D:\stnavel\CosyVoice\.git" (
    echo [√] CosyVoice 已存在，跳过克隆
) else (
    git clone --recursive https://github.com/FunAudioLLM/CosyVoice.git D:\stnavel\CosyVoice
    if %ERRORLEVEL% NEQ 0 (
        echo [!] 克隆失败，尝试使用 gitee 镜像...
        git clone --recursive https://gitee.com/mirrors/CosyVoice.git D:\stnavel\CosyVoice
    )
)

REM ===== 安装依赖 =====
echo.
echo [3/5] 安装依赖 (这可能需要几分钟)...
pushd D:\stnavel\CosyVoice
call conda run -n cosyvoice pip install -r requirements.txt -i https://mirrors.aliyun.com/pypi/simple/ --trusted-host=mirrors.aliyun.com
call conda run -n cosyvoice pip install soundfile webview pyinstaller -i https://mirrors.aliyun.com/pypi/simple/ --trusted-host=mirrors.aliyun.com
popd
echo [√] 依赖安装完成

REM ===== 下载模型 =====
echo.
echo [4/5] 下载 CosyVoice 3 RL 模型 (~2GB)...
pushd D:\stnavel\CosyVoice
if not exist "pretrained_models" mkdir pretrained_models
call conda run -n cosyvoice python -c "from modelscope import snapshot_download; snapshot_download('FunAudioLLM/Fun-CosyVoice3-0.5B-2512', local_dir='pretrained_models/Fun-CosyVoice3-0.5B')"
if %ERRORLEVEL% NEQ 0 (
    echo [!] ModelScope 下载失败，尝试 HuggingFace...
    call conda run -n cosyvoice pip install huggingface_hub -i https://mirrors.aliyun.com/pypi/simple/
    call conda run -n cosyvoice python -c "from huggingface_hub import snapshot_download; snapshot_download('FunAudioLLM/Fun-CosyVoice3-0.5B-2512', local_dir='pretrained_models/Fun-CosyVoice3-0.5B')"
)
popd
echo [√] 模型下载完成

REM ===== 创建参考音频目录 =====
echo.
echo [5/5] 准备参考音频...
if not exist "D:\stnavel\CosyVoice\asset" mkdir "D:\stnavel\CosyVoice\asset"

REM 如果 asset 下没有参考音频，从 MOSS-TTS-Nano 复制一些
if not exist "D:\stnavel\CosyVoice\asset\zero_shot_prompt.wav" (
    echo [!] 请将一段 3-10 秒的参考语音放到:
    echo     D:\stnavel\CosyVoice\asset\zero_shot_prompt.wav
    echo     (可以是任意 .wav 格式的中文语音)
)

echo.
echo ====================================================
echo    安装完成！
echo.
echo    启动配音服务:
echo      D:\stnavel\StoryBrainManager\双击运行管理器.bat
echo.
echo    USB 连接 (推荐):
echo      D:\stnavel\StoryBrainManager\adb_connect.bat
echo ====================================================
echo.
pause
