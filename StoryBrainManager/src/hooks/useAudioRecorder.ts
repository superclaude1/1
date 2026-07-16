import { useState } from "react";
import { startRecording, stopRecording } from "@/api/audio";
import type { AudioQuality, AudioTrack } from "@/types";

export function useAudioRecorder(outputDir: string) {
  const [isRecording, setIsRecording] = useState(false);

  const start = async (quality: AudioQuality = "high") => {
    await startRecording({ quality, outputDir });
    setIsRecording(true);
  };

  const stop = async (): Promise<AudioTrack> => {
    const track = await stopRecording();
    setIsRecording(false);
    return track;
  };

  return { isRecording, start, stop };
}
