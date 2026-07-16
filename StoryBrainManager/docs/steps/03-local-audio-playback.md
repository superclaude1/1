# 步骤 03：本地静态WAV文件播放验证

## 目标
验证 `convertFileSrc` + `capabilities/default.json` + `tauri.conf.json` 的 CSP
`media-src` 配置是否正确，能否播放本地磁盘上一个预置的测试 WAV 文件。这一步专门用来
提前暴露 Tauri v2 权限/CSP 配置问题，避免在真实录音场景里和"录音是否正常"混在一起排查。

## 涉及文件
- 新增测试素材：任意找一个 3~5 秒的 `.wav` 文件，放到 `D:\voxnovel\test-assets\sample.wav`
  （目录需自建，`test-assets/` 建议加入 `.gitignore` 或保留作为长期测试素材）
- 临时在 `src/pages/Home/index.tsx` 或新建一个 `src/pages/DevTest/index.tsx` 里用
  `AudioPlayer` 组件加载该绝对路径（`D:\\voxnovel\\test-assets\\sample.wav`）

## 实现要点
- `src/components/AudioPlayer` 组件已存在，直接传入绝对路径即可：
  ```tsx
  <AudioPlayer filePath="D:\\voxnovel\\test-assets\\sample.wav" label="测试音频" />
  ```
- 如果播放失败，优先检查两处配置：
  1. `src-tauri/tauri.conf.json` 里 `app.security.csp.media-src` 是否包含
     `asset: https://asset.localhost`
  2. `src-tauri/capabilities/default.json` 的 `fs:scope` 是否覆盖了测试文件所在路径
     （当前默认只放行了 `$APPDATA/**` 和 `$APPLOCALDATA/**`，测试阶段可临时加一条
     `test-assets` 的绝对路径范围，正式录音路径统一后可收紧）

## 验证方法
1. 准备好 `test-assets/sample.wav`
2. 页面上点击"播放"按钮
3. 应能听到该测试音频的实际声音内容
4. 打开开发者工具 Console，确认没有 CSP 报错（形如 `Refused to load media because it
   violates the following Content Security Policy directive`）或 `asset protocol not
   allowed` 类报错

## 完成标准 (DoD)
- [x] 点击播放能实际听到声音
- [x] 控制台无 CSP / asset 协议相关报错
- [x] 暂停按钮功能正常（再次点击能停止播放）
