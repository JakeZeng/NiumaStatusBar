# 开发指南

## 本地开发

```bash
# 安装依赖
pnpm install

# 启动开发服务器（前端 HMR + Rust 热重载）
pnpm tauri dev

# 仅前端开发（无 Tauri 壳）
pnpm dev
```

## 代码风格

- 前端：TypeScript strict 模式
- 后端：`cargo fmt` + `cargo clippy`

```bash
cd src-tauri && cargo fmt && cargo clippy
```

## 调试技巧

### 1. 前端 DevTools

在 `tauri.conf.json` 中开启：

```json
"app": {
  "windows": [{
    "label": "main",
    "devtools": true
  }]
}
```

### 2. Rust 日志

使用 `tauri-plugin-log`：

```rust
use log::{info, error};
info!("Polling started");
error!("Fetch failed: {}", err);
```

### 3. 数据库查看

```bash
# Linux 数据库位置
~/.local/share/ai-model-monitor/data.db

# 使用 sqlite3 查看
sqlite3 ~/.local/share/ai-model-monitor/data.db
> .tables
> .schema providers
> SELECT * FROM providers;
> SELECT * FROM usage_history ORDER BY timestamp DESC LIMIT 10;
```

## 添加新的 Provider 预设

编辑 [ConfigModal.tsx](file:///workspace/src/components/ConfigModal.tsx) 中的 `PROVIDER_PRESETS`：

```typescript
const PROVIDER_PRESETS = [
  { 
    type: 'openai', 
    name: 'OpenAI', 
    baseUrl: 'https://api.openai.com', 
    endpoint: '/v1/dashboard/billing/credit_grants' 
  },
  // 添加更多...
];
```

## 添加新主题

1. 在 [index.css](file:///workspace/src/index.css) 添加主题 CSS 变量
2. 在 [ThemeManager.ts](file:///workspace/src/themes/ThemeManager.ts) 添加主题配置
3. 在 [ThemedBackground.tsx](file:///workspace/src/components/ThemedBackground.tsx) 添加背景装饰

## 添加新 IPC 命令

1. 在 [commands.rs](file:///workspace/src-tauri/src/commands.rs) 定义命令
2. 在 [main.rs](file:///workspace/src-tauri/src/main.rs) 注册到 `invoke_handler`
3. 前端使用 `invoke('command_name', { args })` 调用

## 打包发布

```bash
# 1. 更新版本号
# 编辑 src-tauri/tauri.conf.json
# 编辑 package.json

# 2. 提交并打 tag
git add .
git commit -m "Release v0.2.0"
git tag v0.2.0
git push origin v0.2.0

# 3. GitHub Actions 自动构建并发布 Release
```

## 常见问题

### Q: SQLite 中文路径报错？
A: Tauri 在 Windows 下需使用 UTF-8 路径，避免用户目录含特殊字符。

### Q: 托盘图标不显示？
A: 检查 `tauri.conf.json` 的 `trayIcon.iconPath` 是否指向真实 PNG 文件。

### Q: WebView2 缺失？
A: Windows 10 早期版本需手动安装 [WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/)。

### Q: macOS 提示无法打开？
A: 需在「系统设置 → 隐私与安全性」点击「仍要打开」，或使用开发者签名。
