import { useState } from "react";
import { extractDialogues } from "@/api/llm";
import type { DialogueLine } from "@/types";

export function useLLMExtract() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const extract = async (novelChunk: string): Promise<DialogueLine[]> => {
    setLoading(true);
    setError(null);
    try {
      const result = await extractDialogues(novelChunk);
      return result.dialogues;
    } catch (e) {
      setError(String(e));
      return [];
    } finally {
      setLoading(false);
    }
  };

  return { extract, loading, error };
}
