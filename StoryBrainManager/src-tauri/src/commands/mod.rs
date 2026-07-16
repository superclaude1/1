use std::process::{Command, Stdio, Child};
use std::io::{BufRead, BufReader};
use std::sync::Mutex;
use std::thread;
use std::net::UdpSocket;
use tauri::{State, Emitter, AppHandle};
use serde::{Serialize, Deserialize};
use crate::state::AppState;

#[derive(Serialize, Clone)]
pub struct TtsLogEvent {
    pub message: String,
}

#[derive(Serialize)]
pub struct ServerStatus {
    pub running: bool,
    pub port: u16,
    pub local_ip: String,
}

// 1. Get Local IP Address
#[tauri::command]
pub fn get_local_ip() -> String {
    let socket = match UdpSocket::bind("0.0.0.0:0") {
        Ok(s) => s,
        Err(_) => return "127.0.0.1".to_string(),
    };
    if socket.connect("8.8.8.8:80").is_ok() {
        if let Ok(local_addr) = socket.local_addr() {
            return local_addr.ip().to_string();
        }
    }
    "127.0.0.1".to_string()
}

// 2. Start TTS Server
#[tauri::command]
pub fn start_tts_server(
    app: AppHandle,
    state: State<'_, AppState>,
    port: u16,
) -> Result<String, String> {
    let mut proc_guard = state.python_process.lock().unwrap();
    if proc_guard.is_some() {
        return Err("Server is already running".to_string());
    }

    // CosyVoice 3 needs Python 3.10 (conda env or system Python)
    // Priority: conda env > system python > .venv fallback
    let python_path = if std::path::Path::new("C:\\Users\\wjs\\miniconda3\\envs\\cosyvoice\\python.exe").exists() {
        "C:\\Users\\wjs\\miniconda3\\envs\\cosyvoice\\python.exe"
    } else if std::path::Path::new("C:\\Users\\wjs\\.conda\\envs\\cosyvoice\\python.exe").exists() {
        "C:\\Users\\wjs\\.conda\\envs\\cosyvoice\\python.exe"
    } else {
        // Fallback: try system Python 3.10
        "python3"
    };
    let script_path = "D:\\stnavel\\StoryBrainManager\\engine\\app_cosyvoice.py";

    let mut child = Command::new(python_path)
        .arg(script_path)
        .arg("--host")
        .arg("0.0.0.0")
        .arg("--port")
        .arg(port.to_string())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("Failed to spawn Python process: {}", e))?;

    let stdout = child.stdout.take().ok_or("Failed to capture stdout")?;
    let stderr = child.stderr.take().ok_or("Failed to capture stderr")?;

    // Store child process handle
    *proc_guard = Some(child);

    // Spawn thread to read stdout
    let app_clone1 = app.clone();
    thread::spawn(move || {
        let reader = BufReader::new(stdout);
        for line in reader.lines() {
            if let Ok(text) = line {
                let _ = app_clone1.emit("tts-log", TtsLogEvent { message: text });
            }
        }
    });

    // Spawn thread to read stderr
    let app_clone2 = app.clone();
    thread::spawn(move || {
        let reader = BufReader::new(stderr);
        for line in reader.lines() {
            if let Ok(text) = line {
                let _ = app_clone2.emit("tts-log", TtsLogEvent { message: text });
            }
        }
    });

    Ok("Server started successfully".to_string())
}

// 3. Stop TTS Server
#[tauri::command]
pub fn stop_tts_server(state: State<'_, AppState>) -> Result<String, String> {
    let mut proc_guard = state.python_process.lock().unwrap();
    if let Some(mut child) = proc_guard.take() {
        let _ = child.kill();
        let _ = child.wait();
        Ok("Server stopped successfully".to_string())
    } else {
        Err("Server is not running".to_string())
    }
}

// 4. Get TTS Server Status
#[tauri::command]
pub fn get_tts_server_status(state: State<'_, AppState>, port: u16) -> ServerStatus {
    let proc_guard = state.python_process.lock().unwrap();
    ServerStatus {
        running: proc_guard.is_some(),
        port,
        local_ip: get_local_ip(),
    }
}
