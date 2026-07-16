import { useRef, useState } from "react";
import { toPlayableSrc } from "@/api/audio";

// 封装 convertFileSrc + <audio> 元素的本地文件播放逻辑
export function useAudioPlayback() {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [isPlaying, setIsPlaying] = useState(false);

  const play = (absolutePath: string) => {
    const src = toPlayableSrc(absolutePath);
    if (!audioRef.current) audioRef.current = new Audio();
    audioRef.current.src = src;
    audioRef.current.play();
    setIsPlaying(true);
  };

  const pause = () => {
    audioRef.current?.pause();
    setIsPlaying(false);
  };

  return { play, pause, isPlaying };
}
