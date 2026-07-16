import { useState } from "react";
import { useAudioRecorder } from "@/hooks/useAudioRecorder";
import type { AudioQuality } from "@/types";

interface Props {
  outputDir: string;
  onFinished: (filePath: string) => void;
}

// Low(16kHz单声道) / Medium(44.1kHz单声道) / High(48kHz双声道) 三档预设
export default function AudioRecorder({ outputDir, onFinished }: Props) {
  const [quality, setQuality] = useState<AudioQuality>("high");
  const { isRecording, start, stop } = useAudioRecorder(outputDir);

  const handleStop = async () => {
    const track = await stop();
    onFinished(track.filePath);
  };

  return (
    <div className="flex items-center gap-3">
      <select
        className="border rounded px-2 py-1 text-sm"
        value={quality}
        onChange={(e) => setQuality(e.target.value as AudioQuality)}
        disabled={isRecording}
      >
        <option value="low">Low 16kHz 单声道</option>
        <option value="medium">Medium 44.1kHz 单声道</option>
        <option value="high">High 48kHz 双声道</option>
      </select>
      {!isRecording ? (
        <button className="px-3 py-1 rounded bg-red-600 text-white text-sm" onClick={() => start(quality)}>
          开始录音
        </button>
      ) : (
        <button className="px-3 py-1 rounded bg-gray-700 text-white text-sm" onClick={handleStop}>
          停止录音
        </button>
      )}
    </div>
  );
}
