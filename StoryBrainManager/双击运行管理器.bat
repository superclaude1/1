@echo off
chcp 65001 >nul
title StoryBrain 配音管理器 (CosyVoice 3)

echo =======================================================
echo    StoryBrain 配音管理器 — CosyVoice 3 (0.5B-RL)
echo    AMD Ryzen 7 7735HS CPU 优化版
echo =======================================================
echo.
echo 正在启动 CosyVoice 3 引擎，首次加载约需 30-60 秒...
echo 软件窗口即将打开，请耐心等待...
echo.
echo 提示: 如需 USB 直连手机，请先运行 adb_connect.bat
echo.

cd /d "%~dp0"

REM 检测 CosyVoice conda 环境
set "PYTHON="
if exist "C:\Users\wjs\miniconda3\envs\cosyvoice\python.exe" (
    set "PYTHON=C:\Users\wjs\miniconda3\envs\cosyvoice\python.exe"
) else if exist "C:\Users\wjs\.conda\envs\cosyvoice\python.exe" (
    set "PYTHON=C:\Users\wjs\.conda\envs\cosyvoice\python.exe"
) else if exist "engine\.venv\Scripts\python.exe" (
    echo [!] CosyVoice conda 环境未找到，使用 .venv 回退
    set "PYTHON=engine\.venv\Scripts\python.exe"
) else (
    echo [×] 未找到 Python 环境！请先运行:
    echo     D:\stnavel\install_cosyvoice.bat
    pause
    exit /b 1
)

"%PYTHON%" engine\app_cosyvoice.py --host 0.0.0.0 --port 18083

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [×] 启动失败！请检查 CosyVoice 是否正确安装。
    echo     运行安装脚本: D:\stnavel\install_cosyvoice.bat
    pause
)
