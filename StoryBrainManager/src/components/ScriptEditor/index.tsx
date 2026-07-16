import type { DialogueLine } from "@/types";

interface Props {
  dialogues: DialogueLine[];
  onChange: (dialogues: DialogueLine[]) => void;
}

// 展示 LLM 提取出的角色对话/旁白列表，支持人工校对与角色分配
export default function ScriptEditor({ dialogues }: Props) {
  return (
    <div className="space-y-2">
      {dialogues.map((line, i) => (
        <div key={i} className="flex items-center gap-2 text-sm border-b py-1">
          <span className="w-20 font-medium text-gray-700">{line.character ?? "旁白"}</span>
          <span className="flex-1">{line.text}</span>
          {line.voiceTag && <span className="text-xs text-blue-500">{line.voiceTag}</span>}
        </div>
      ))}
    </div>
  );
}
