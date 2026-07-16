import { useAudioPlayback } from "@/hooks/useAudioPlayback";

interface Props {
  filePath: string;
  label?: string;
}

// 基于 convertFileSrc 的本地高保真音频播放组件
export default function AudioPlayer({ filePath, label }: Props) {
  const { play, pause, isPlaying } = useAudioPlayback();

  return (
    <div className="flex items-center gap-2">
      <button
        className="px-3 py-1 rounded bg-blue-600 text-white text-sm"
        onClick={() => (isPlaying ? pause() : play(filePath))}
      >
        {isPlaying ? "暂停" : "播放"}
      </button>
      {label && <span className="text-sm text-gray-600">{label}</span>}
    </div>
  );
}
