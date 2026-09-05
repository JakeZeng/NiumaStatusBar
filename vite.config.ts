import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 版本号来源：pnpm/npm 跑 `pnpm build` 时会自动注入 npm_package_version。
// 不要改成 readFileSync('./package.json')：Vite 6.4.x + esbuild 0.25 在
// Windows / CRLF 场景会把 package.json 内容内联到临时 .mjs 时错误地加
// BOM（U+FEFF），触发 JSON.parse 在 line 6 col 23 抛 SyntaxError。
// 见 desktop-release skill 的故障排查章节。
const pkgVersion = process.env.npm_package_version ?? "0.0.0";

export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: {
    host: "0.0.0.0",
    port: 1420,
    strictPort: true,
  },
  envPrefix: ["VITE_", "TAURI_"],
  define: {
    __APP_VERSION__: JSON.stringify(pkgVersion),
  },
  build: {
    target: process.env.TAURI_PLATFORM === "windows" ? "chrome105" : "safari13",
    minify: !process.env.TAURI_DEBUG ? "esbuild" : false,
    sourcemap: !!process.env.TAURI_DEBUG,
    rollupOptions: {
      output: {
        manualChunks: {
          'react-vendor': ['react', 'react-dom'],
          'chart-vendor': ['recharts'],
          'i18n-vendor': ['i18next', 'react-i18next'],
          'icons-vendor': ['lucide-react'],
        },
      },
    },
    chunkSizeWarningLimit: 600,
  },
});