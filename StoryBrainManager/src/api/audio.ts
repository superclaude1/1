import { invoke } from "@tauri-apps/api/core";
import { convertFileSrc } from "@tauri-apps/api/core";
import type { RecordingConfig, AudioTrack } from "@/types";

// 对应 src-tauri/src/commands/audio_record.rs
export async function startRecording(config: RecordingConfig): Promise<void> {
  return invoke("start_recording", { config });
}

export async function stopRecording(): Promise<AudioTrack> {
  return invoke("stop_recording");
}

// 对应 src-tauri/src/commands/audio_merge.rs
export async function mergeTracks(trackPaths: string[], outputPath: string): Promise<string> {
  return invoke("merge_wav_tracks", { trackPaths, outputPath });
}

// 本地音频文件 -> Tauri 安全资源 URL，供 <audio> 标签播放
export function toPlayableSrc(absolutePath: string): string {
  return convertFileSrc(absolutePath);
}

// 对应 src-tauri/src/commands/synthesize.rs
export async function synthesizeProject(
  projectId: string,
  trackPaths: string[]
): Promise<string> {
  return invoke("synthesize_project", { projectId, trackPaths });
}
