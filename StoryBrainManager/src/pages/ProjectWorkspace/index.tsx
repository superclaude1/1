import { useState } from "react";
import { useParams } from "react-router-dom";
import AudioRecorder from "@/components/AudioRecorder";
import AudioPlayer from "@/components/AudioPlayer";
import type { AudioTrack } from "@/types";

const RECORDINGS_DIR = "D:\\voxnovel\\recordings";

export default function ProjectWorkspace() {
  const { projectId } = useParams();
  const [tracks, setTracks] = useState<AudioTrack[]>([]);

  return (
    <div className="p-8">
      <h2 className="text-xl font-medium mb-4">工程：{projectId}</h2>

      {/* 步骤05：录制 → 列表 → 播放闭环 */}
      <section className="mb-6">
        <h3 className="text-lg font-medium mb-2">音轨录制</h3>
        <AudioRecorder
          outputDir={RECORDINGS_DIR}
          onFinished={(filePath) =>
            setTracks((prev) => [
              ...prev,
              {
                id: crypto.randomUUID(),
                filePath,
                character: null,
                durationMs: 0,
                format: "wav",
              },
            ])
          }
        />
      </section>

      {tracks.length > 0 && (
        <section>
          <h3 className="text-lg font-medium mb-2">已录制音轨 ({tracks.length})</h3>
          <div className="space-y-2">
            {tracks.map((t) => (
              <div key={t.id} className="flex items-center gap-3 border rounded p-2">
                <span className="text-xs text-gray-500 w-20 truncate">{t.id.slice(0, 8)}</span>
                <AudioPlayer filePath={t.filePath} />
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
