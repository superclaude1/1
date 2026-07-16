import { invoke } from "@tauri-apps/api/core";

export async function setApiKey(key: string): Promise<void> {
  return invoke("set_api_key", { key });
}

export async function getApiKey(): Promise<string | null> {
  return invoke("get_api_key");
}

export async function testConnection(apiKey: string): Promise<string> {
  return invoke("test_deepseek_connection", { apiKey });
}
