# 步骤 16：工程数据落盘持久化

## 目标
把 `commands/project.rs` 中 `create_project` / `save_project` / `list_projects` 的
占位实现改为真实写入磁盘（`$APPDATA/voxnovel/projects/*.json`），使工程数据在应用
重启后依然存在。

## 涉及文件
- `src-tauri/src/commands/project.rs`（重写三个 command 的实现）
- `src-tauri/Cargo.toml`（如未引入，需确认 `tauri` 的 `path` API 可用；已有的
  `tauri_plugin_fs` 也可选用，但直接用 `std::fs` + `app.path().app_data_dir()`
  更直接）
- `src-tauri/capabilities/default.json`（确认 `fs:scope` 已覆盖 `$APPDATA/**`，
  脚手架里已配置，此步骤只需确认未被后续改动破坏）

## 实现要点
```rust
fn projects_dir(app: &tauri::AppHandle) -> AppResult<std::path::PathBuf> {
    let dir = app.path().app_data_dir().map_err(|e| AppError::Other(e.to_string()))?
        .join("projects");
    std::fs::create_dir_all(&dir)?;
    Ok(dir)
}

#[tauri::command]
pub async fn save_project(project: Project, app: tauri::AppHandle) -> AppResult<()> {
    let dir = projects_dir(&app)?;
    let path = dir.join(format!("{}.json", project.id));
    let json = serde_json::to_string_pretty(&project)
        .map_err(|e| AppError::JsonParse(e.to_string()))?;
    std::fs::write(path, json)?;
    Ok(())
}

#[tauri::command]
pub async fn list_projects(app: tauri::AppHandle) -> AppResult<Vec<Project>> {
    let dir = projects_dir(&app)?;
    let mut result = vec![];
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        if entry.path().extension().map(|e| e == "json").unwrap_or(false) {
            let content = std::fs::read_to_string(entry.path())?;
            if let Ok(p) = serde_json::from_str::<Project>(&content) {
                result.push(p);
            }
        }
    }
    Ok(result)
}
```

## 验证方法
1. 通过前端（临时用 `create_project` 测试按钮，或直接进入步骤 17 一起验证）创建一个
   工程，命名如"测试工程A"
2. 调用 `save_project` 保存
3. 用文件管理器打开 `%APPDATA%\voxnovel\projects\`（Windows 上通常是
   `C:\Users\<你的用户名>\AppData\Roaming\com.voxnovel.app\projects\`，具体路径以
   `tauri.conf.json` 的 `identifier` 为准），确认能看到一个 `<uuid>.json` 文件，
   用记事本打开内容可读，字段与工程数据一致
4. 完全关闭应用（不是页面刷新，是真正退出 `tauri dev` 进程），重新启动
5. Home 页调用 `list_projects`，确认"测试工程A"依然出现在列表中

## 完成标准 (DoD)
- [ ] 保存后能在磁盘上找到对应 json 文件，内容可读且字段完整
- [ ] 应用完全重启后，之前创建的工程数据不丢失
- [ ] 多个工程并存时，`list_projects` 能正确返回全部工程，无遗漏无重复
