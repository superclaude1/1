# 步骤 12：DeepSeek API连通性最小验证

## 目标
用真实、有效的 DeepSeek API Key 发起一次最简单的请求，只验证 `reqwest` + JSON Mode
的网络链路本身没问题（网络可达、鉴权正确、response_format 参数被接受），暂不涉及本项目
业务 schema，避免把"网络能不能通"和"业务字段对不对"两类问题混在一起排查。

## 涉及文件
- 临时在 `src-tauri/src/llm/deepseek_client.rs` 加一个 `pub async fn
  ping_deepseek(api_key: &str) -> AppResult<String>` 测试函数（验证完可保留作为
  健康检查接口，或删除）
- 临时暴露一个 `commands/settings.rs` 里的 `test_deepseek_connection` command 供前端
  按钮调用

## 实现要点
```rust
pub async fn ping_deepseek(api_key: &str) -> AppResult<String> {
    let body = serde_json::json!({
        "model": "deepseek-v4-flash",
        "response_format": { "type": "json_object" },
        "messages": [
            { "role": "system", "content": "Return json only." },
            { "role": "user", "content": "请返回 {\"reply\": \"ok\"} 这样格式的 json" }
        ],
        "max_tokens": 100
    });
    let client = reqwest::Client::new();
    let resp = client
        .post("https://api.deepseek.com/chat/completions")
        .bearer_auth(api_key)
        .json(&body)
        .send()
        .await
        .map_err(|e| AppError::LlmRequest(e.to_string()))?;
    resp.text().await.map_err(|e| AppError::LlmRequest(e.to_string()))
}
```

## 验证方法
1. 在 Settings 页填入**真实**的 DeepSeek API Key 并保存（复用步骤 11）
2. 点击"测试连接"按钮
3. 前端或终端应打印出一段合法的 JSON 文本，形如
   `{"choices":[{"message":{"content":"{\"reply\": \"ok\"}"}}], ...}`
4. 如果返回 401/403，检查 Key 是否正确、账户是否有余额
5. 如果返回超时，检查本机网络能否访问 `api.deepseek.com`（如是否需要代理）

## 完成标准 (DoD)
- [ ] 收到 HTTP 200 响应
- [ ] 响应体是合法 JSON（可以被 `serde_json::from_str` 成功解析为 `Value`）
- [ ] 网络异常（无 Key / Key错误 / 断网）时能得到明确的错误提示而非无限等待
