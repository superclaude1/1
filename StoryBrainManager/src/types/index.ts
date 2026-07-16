// 与 Rust 端 serde 结构体保持字段对齐

export type AudioQuality = "low" | "medium" | "high";

export interface RecordingConfig {
  quality: AudioQuality;
  outputDir: string;
}

export type VoiceTag = "[laugh]" | "[uv_break]" | "[lbreak]" | null;

export interface DialogueLine {
  character: string | null;
  text: string;
  voiceTag: VoiceTag;
}

export interface ExtractResult {
  dialogues: DialogueLine[];
}

export interface AudioTrack {
  id: string;
  filePath: string;
  character: string | null;
  durationMs: number;
  format: "wav" | "m4a";
}

export interface Project {
  id: string;
  name: string;
  novelText: string;
  tracks: AudioTrack[];
  createdAt: string;
}
