import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Tauri 桌面端专用 Vite 配置
export default defineConfig(async () => ({
  plugins: [react()],
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    watch: {
      ignored: ["**/src-tauri/**"],
    },
  },
  resolve: {
    alias: { "@": "/src" },
  },
}));
