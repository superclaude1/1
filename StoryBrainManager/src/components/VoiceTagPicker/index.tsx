import type { VoiceTag } from "@/types";

const TAGS: { value: VoiceTag; label: string }[] = [
  { value: "[laugh]", label: "笑声" },
  { value: "[uv_break]", label: "短停顿" },
  { value: "[lbreak]", label: "长停顿/换气" },
  { value: null, label: "无" },
];

interface Props {
  value: VoiceTag;
  onChange: (tag: VoiceTag) => void;
}

// 与 ChatTTS 原生支持的词级控制标签对齐
export default function VoiceTagPicker({ value, onChange }: Props) {
  return (
    <select
      className="border rounded px-2 py-1 text-sm"
      value={value ?? ""}
      onChange={(e) => onChange((e.target.value || null) as VoiceTag)}
    >
      {TAGS.map((t) => (
        <option key={t.label} value={t.value ?? ""}>
          {t.label}
        </option>
      ))}
    </select>
  );
}
