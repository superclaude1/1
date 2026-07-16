import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { invoke } from "@tauri-apps/api/core";
import { listProjects } from "@/api/project";
import { useProjectStore } from "@/stores/useProjectStore";
import AudioPlayer from "@/components/AudioPlayer";
import AudioRecorder from "@/components/AudioRecorder";
import type { AudioTrack } from "@/types";

const SAMPLE_WAV = "D:\\voxnovel\\test-assets\\sample.wav";
const RECORDINGS_DIR = "D:\\voxnovel\\recordings";

export default function Home() {
  const navigate = useNavigate();
  const { projects, setProjects } = useProjectStore();
  const [pingResult, setPingResult] = useState<string | null>(null);
  const [pingError, setPingError] = useState<string | null>(null);
  const [tracks, setTracks] = useState<AudioTrack[]>([]);

  useEffect(() => {
    listProjects().then(setProjects).catch(console.error);
  }, [setProjects]);

  const handlePing = async () => {
    setPingError(null);
    try {
      const result = await invoke<string>("ping", { msg: "hello" });
      setPingResult(result);
    } catch (err) {
      setPingError(String(err));
    }
  };

  return (
    <div className="p-8">
      <h1 className="text-2xl font-semibold mb-4">VoxNovel 声书</h1>

      {/* 步骤02：IPC连通验证 */}
      <div className="mb-6 flex items-center gap-3">
        <button
          className="px-4 py-2 rounded-lg bg-indigo-600 text-white hover:bg-indigo-700"
          onClick={handlePing}
        >
          测试连接
        </button>
        {pingResult && <span className="text-sm text-green-600">{pingResult}</span>}
        {pingError && <span className="text-sm text-red-600">调用失败：{pingError}</span>}
      </div>

      {/* 步骤03：本地WAV播放验证 */}
      <div className="mb-6">
        <h2 className="text-lg font-medium mb-2">本地音频播放测试</h2>
        <AudioPlayer filePath={SAMPLE_WAV} label="测试正弦波 (440Hz 3秒)" />
      </div>

      {/* 步骤04：真实录音验证 */}
      <div className="mb-6">
        <h2 className="text-lg font-medium mb-2">录音测试</h2>
        <AudioRecorder
          outputDir={RECORDINGS_DIR}
          onFinished={(filePath) =>
            setTracks((prev) => [
              ...prev,
              { id: crypto.randomUUID(), filePath, character: null, durationMs: 0, format: "wav" },
            ])
          }
        />
        {tracks.length > 0 && (
          <div className="mt-3 space-y-1">
            <h3 className="text-sm font-medium text-gray-600">已录制：</h3>
            {tracks.map((t) => (
              <AudioPlayer key={t.id} filePath={t.filePath} label={t.id.slice(0, 8)} />
            ))}
          </div>
        )}
      </div>

      <div className="grid gap-3">
        {projects.map((p) => (
          <button
            key={p.id}
            className="text-left p-4 rounded-lg border hover:bg-gray-50"
            onClick={() => navigate(`/project/${p.id}`)}
          >
            {p.name}
          </button>
        ))}
      </div>
    </div>
  );
}
