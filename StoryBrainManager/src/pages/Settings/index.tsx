import { useState, useEffect } from "react";
import { setApiKey, getApiKey, testConnection } from "@/api/settings";

export default function Settings() {
  const [key, setKey] = useState("");
  const [saved, setSaved] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [testError, setTestError] = useState<string | null>(null);

  useEffect(() => {
    getApiKey().then((k) => {
      if (k) setKey(k);
    });
  }, []);

  const handleSave = async () => {
    await setApiKey(key);
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  };

  const handleTest = async () => {
    setTesting(true);
    setTestResult(null);
    setTestError(null);
    try {
      const result = await testConnection(key);
      setTestResult("连接成功 ✓");
      console.log("[settings] test connection:", result.slice(0, 200));
    } catch (e) {
      setTestError(String(e));
    } finally {
      setTesting(false);
    }
  };

  return (
    <div className="p-8">
      <h2 className="text-xl font-medium mb-4">设置</h2>

      <div className="max-w-md space-y-4">
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            DeepSeek API Key
          </label>
          <input
            type="password"
            className="w-full border rounded px-3 py-2 text-sm"
            value={key}
            onChange={(e) => setKey(e.target.value)}
            placeholder="sk-..."
          />
        </div>

        <div className="flex gap-3">
          <button
            className="px-4 py-2 rounded bg-indigo-600 text-white text-sm hover:bg-indigo-700"
            onClick={handleSave}
          >
            {saved ? "已保存 ✓" : "保存"}
          </button>
          <button
            className="px-4 py-2 rounded border text-sm hover:bg-gray-50"
            onClick={handleTest}
            disabled={testing || !key}
          >
            {testing ? "测试中..." : "测试连接"}
          </button>
        </div>

        {testResult && <p className="text-sm text-green-600">{testResult}</p>}
        {testError && <p className="text-sm text-red-600 break-all">连接失败：{testError}</p>}
      </div>
    </div>
  );
}
