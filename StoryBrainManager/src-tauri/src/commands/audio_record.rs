use crate::error::AppResult;
use crate::state::{ActiveRecording, AppState, SendStream};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use serde::{Deserialize, Serialize};
use std::io::BufWriter;
use std::sync::{Arc, Mutex};
use tauri::State;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecordingConfig {
    pub quality: String,
    pub output_dir: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioTrack {
    pub id: String,
    pub file_path: String,
    pub character: Option<String>,
    pub duration_ms: u64,
    pub format: String,
}

fn quality_config(quality: &str) -> (u32, u16) {
    match quality {
        "low" => (16000, 1),
        "medium" => (44100, 1),
        _ => (48000, 2),
    }
}

#[tauri::command]
pub async fn start_recording(
    config: RecordingConfig,
    state: State<'_, AppState>,
) -> AppResult<()> {
    let (sample_rate, channels) = quality_config(&config.quality);

    std::fs::create_dir_all(&config.output_dir)?;

    let host = cpal::default_host();
    let device = host
        .default_input_device()
        .ok_or_else(|| crate::error::AppError::Recording("未找到麦克风设备".into()))?;

    let cpal_config = cpal::StreamConfig {
        channels: channels.into(),
        sample_rate: cpal::SampleRate(sample_rate),
        buffer_size: cpal::BufferSize::Default,
    };

    let file_path = format!("{}/{}.wav", config.output_dir, uuid::Uuid::new_v4());
    let file = std::fs::File::create(&file_path)?;
    let spec = hound::WavSpec {
        channels,
        sample_rate,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    };
    let writer = hound::WavWriter::new(BufWriter::new(file), spec)?;
    let writer = Arc::new(Mutex::new(writer));
    let sample_count = Arc::new(Mutex::new(0u64));

    let w = writer.clone();
    let sc = sample_count.clone();

    let stream = device.build_input_stream(
        &cpal_config,
        move |data: &[f32], _: &cpal::InputCallbackInfo| {
            let mut writer = w.lock().unwrap();
            let mut count = sc.lock().unwrap();
            for &sample in data {
                let s = (sample.clamp(-1.0, 1.0) * 32767.0) as i16;
                writer.write_sample(s).ok();
                *count += 1;
            }
        },
        |err| eprintln!("[audio_record] stream error: {err}"),
        None,
    )?;

    stream.play()?;

    *state.recording.lock().unwrap() = Some(ActiveRecording {
        stream: Some(SendStream(stream)),
        writer: Some(writer),
        sample_count,
        sample_rate,
        channels,
        file_path: file_path.clone(),
    });

    Ok(())
}

#[tauri::command]
pub async fn stop_recording(state: State<'_, AppState>) -> AppResult<AudioTrack> {
    let mut guard = state.recording.lock().unwrap();
    let rec = guard
        .take()
        .ok_or_else(|| crate::error::AppError::Recording("没有正在进行的录音".into()))?;

    // drop stream 停止回调，然后 flush + finalize
    drop(rec.stream);

    let total_samples = *rec.sample_count.lock().unwrap();
    let duration_ms = if rec.sample_rate > 0 {
        (total_samples as f64 / rec.sample_rate as f64 * 1000.0) as u64
    } else {
        0
    };

    if let Some(writer) = rec.writer {
        match Arc::try_unwrap(writer) {
            Ok(mutex) => {
                let w = mutex.into_inner().unwrap();
                w.finalize()?;
            }
            Err(_) => {
                eprintln!("[audio_record] writer still referenced, skipping finalize");
            }
        }
    }

    Ok(AudioTrack {
        id: uuid::Uuid::new_v4().to_string(),
        file_path: rec.file_path,
        character: None,
        duration_ms,
        format: "wav".into(),
    })
}
