import { invoke } from "@tauri-apps/api/core";
import type { ExtractResult } from "@/types";

// 对应 src-tauri/src/commands/llm_extract.rs
// Rust 端负责拼接 DeepSeek JSON Mode 四要素约束（response_format / "json"字面量 / schema样例 / max_tokens）
export async function extractDialogues(novelChunk: string): Promise<ExtractResult> {
  return invoke("extract_dialogues", { novelChunk });
}
