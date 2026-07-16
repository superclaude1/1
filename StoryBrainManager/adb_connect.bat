@echo off
chcp 65001 >nul
title StoryBrain USB 连接助手

echo ====================================================
echo    StoryBrain USB 直连助手 (ADB Reverse Tunnel)
echo ====================================================
echo.
echo 此工具通过 USB 数据线建立手机与电脑的直接连接，
echo 无需 WiFi，延迟更低，连接更稳定。
echo.
echo 使用前请确保：
echo   1. 手机已通过 USB 数据线连接到电脑
echo   2. 手机已开启"开发者选项"和"USB 调试"
echo   3. 手机已授权此电脑的 USB 调试请求
echo.

REM 检查 ADB 是否存在
set "ADB="
if exist "%~dp0adb\adb.exe" (
    set "ADB=%~dp0adb\adb.exe"
    echo [√] 使用自带 ADB
) else (
    where adb >nul 2>&1
    if %ERRORLEVEL% EQU 0 (
        set "ADB=adb"
        echo [√] 使用系统 ADB
    ) else (
        echo [×] 未找到 ADB！正在下载 Android Platform Tools...
        call :DOWNLOAD_ADB
        if errorlevel 1 (
            echo [×] 自动下载失败，请手动安装 Android SDK Platform Tools
            echo     下载地址: https://developer.android.com/studio/releases/platform-tools
            pause
            exit /b 1
        )
        set "ADB=%~dp0adb\adb.exe"
    )
)

echo.
echo ----------------------------------------
echo   步骤 1: 检查 USB 连接
echo ----------------------------------------
%ADB% devices
echo.

REM 等待设备授权
%ADB% wait-for-device
if %ERRORLEVEL% NEQ 0 (
    echo [×] 未检测到设备！请检查 USB 连接和手机授权。
    pause
    exit /b 1
)

echo [√] 设备已连接！

echo.
echo ----------------------------------------
echo   步骤 2: 建立反向端口转发
echo ----------------------------------------
echo 将手机 127.0.0.1:18083 映射到电脑 localhost:18083...

%ADB% reverse tcp:18083 tcp:18083
if %ERRORLEVEL% EQU 0 (
    echo [√] 端口转发成功！
    echo.
    echo   手机端配置地址: http://127.0.0.1:18083
    echo.
) else (
    echo [×] 端口转发失败！尝试重新连接...
    %ADB% disconnect
    %ADB% reconnect
    timeout /t 2 /nobreak >nul
    %ADB% reverse tcp:18083 tcp:18083
    if %ERRORLEVEL% EQU 0 (
        echo [√] 重试成功！
    ) else (
        echo [×] 重试失败，请检查 USB 连接。
    )
)

echo.
echo ----------------------------------------
echo   步骤 3: 验证连接
echo ----------------------------------------
%ADB% reverse --list
echo.

echo ====================================================
echo   连接已建立！
echo.
echo   现在请在手机端 StoryBrain 的"配音设置"中
echo   将服务器地址设置为: http://127.0.0.1:18083
echo.
echo   注意: USB 断开后需重新运行此脚本。
echo ====================================================
echo.
pause
exit /b 0

:DOWNLOAD_ADB
echo 正在下载 Android Platform Tools...
if not exist "%~dp0adb" mkdir "%~dp0adb"

REM 下载 Windows 版 platform-tools
set "URL=https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
set "ZIP=%~dp0adb\platform-tools.zip"

powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%URL%' -OutFile '%ZIP%'}" 
if %ERRORLEVEL% NEQ 0 exit /b 1

powershell -Command "& {Expand-Archive -Path '%ZIP%' -DestinationPath '%~dp0adb' -Force}"
if %ERRORLEVEL% NEQ 0 exit /b 1

REM 移动 adb 到 adb 目录
if exist "%~dp0adb\platform-tools\adb.exe" (
    move "%~dp0adb\platform-tools\*" "%~dp0adb\" >nul 2>&1
    rmdir /s /q "%~dp0adb\platform-tools" >nul 2>&1
)
del "%ZIP%" >nul 2>&1
echo [√] ADB 下载完成！
exit /b 0
