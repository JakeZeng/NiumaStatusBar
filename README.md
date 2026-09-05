<div align="center">
  <img src="src-tauri/icons/icon.png" alt="NiumaStatusBar" width="128" height="128"/>
</div>

# AI 模型监控 (AI Model Monitor)

一款跨平台、轻量、高颜值的 AI 模型使用状态监控工具，支持自定义 API 配置与三大主题切换。

> **品牌标识**：数据脉冲徽章 — 圆角方块底 + 心电脉冲线，对应应用对 AI 模型用量（余额 / 5h / 周 / 月额度）的实时监控语义。配色为青(`#22d3ee`)→紫(`#8b5cf6`)→粉(`#ec4899`)对角渐变，呼应"赛博朋克"主题。
>
> 源文件：`src-tauri/icons/icon.svg` · 重新生成全部变体：`python scripts/generate_icons.py`。

## ✨ 特性

- 🎨 **三大主题** — 赛博朋克、武侠江湖、国漫，一键切换
- 🌐 **跨平台** — Windows / macOS / Linux / Android (Tauri 2.x)
- 🔌 **自定义 Provider** — 灵活配置任意 LLM API 接口
- 📊 **实时图表** — Recharts 余额趋势可视化
- 🌍 **多语言** — 中英双语 (i18n)
- 💾 **本地存储** — SQLite 持久化
- ⌨️ **全局快捷键** — `Ctrl+Shift+M` 显示/隐藏
- 📦 **轻量级** — 安装包仅 3-5MB
- 🔔 **系统通知** — 余额预警推送
- 📥 **导入导出** — 配置一键迁移

## 📸 截图

| 赛博朋克 | 武侠 | 国漫 |
|---------|------|------|
| 霓虹辉光 + 扫描线 | 水墨山水 | 祥云花瓣 |

## 🚀 快速开始

### 环境要求

- **Node.js** 20+
- **pnpm** 10+
- **Rust** 1.85+
- **Linux**: `libwebkit2gtk-4.1-dev`, `libgtk-3-dev`, `libappindicator3-dev`
- **Windows**: WebView2 (Win11 自带)
- **macOS**: 10.15+

### 安装

```bash
pnpm install
```

### 开发模式

```bash
pnpm tauri dev
```

### 生产打包

```bash
# 当前平台
pnpm tauri build

# 指定平台
pnpm tauri build --target x86_64-unknown-linux-gnu --bundles deb
pnpm tauri build --target aarch64-apple-darwin
pnpm tauri build --target x86_64-pc-windows-msvc
```

打包产物在 `src-tauri/target/<target>/release/bundle/`。

## 📖 使用说明

### 添加 Provider

1. 点击右上角 **添加 Provider**
2. 选择快速预设（OpenAI / Anthropic / DeepSeek）或自定义
3. 填写 API Key、Base URL、查询接口
4. 设置刷新间隔（最少 10 秒）
5. 保存

### 通用 API 配置格式

| 字段 | 说明 | 示例 |
|------|------|------|
| Base URL | API 根地址 | `https://api.openai.com` |
| 查询接口 | 状态查询路径 | `/v1/dashboard/billing/credit_grants` |
| 请求方法 | GET 或 POST | GET |
| API Key | 授权密钥 | `sk-...` |
| 刷新间隔 | 轮询频率(秒) | 60 |

### 主题切换

点击右上角调色板图标，在下拉菜单中选择主题：
- ⚡ 赛博朋克
- 🗡 武侠江湖
- ✨ 国漫

### 快捷键

- `Ctrl+Shift+M` — 显示/隐藏主窗口

### 导入/导出

- **导出** — 将所有 Provider 配置保存为 JSON 文件
- **导入** — 选择 JSON 文件批量恢复配置

## 🏗️ 项目结构

```
ai-model-monitor/
├── src/                    # React 前端
│   ├── components/         # UI 组件
│   ├── themes/             # 主题系统
│   ├── i18n/               # 国际化
│   ├── App.tsx             # 主应用
│   └── main.tsx            # 入口
├── src-tauri/              # Rust 后端
│   ├── src/
│   │   ├── main.rs         # 应用入口 + 托盘 + 快捷键
│   │   ├── providers.rs    # Provider 管理 + HTTP 请求
│   │   ├── poller.rs       # 异步轮询调度
│   │   ├── storage.rs      # SQLite 存储
│   │   └── commands.rs     # IPC 命令
│   ├── icons/              # 应用图标（统一品牌资产；源 SVG + 多尺寸 PNG/ICO/ICNS/iOS）
│   └── tauri.conf.json     # Tauri 配置
├── scripts/
│   └── generate_icons.py   # 从 icon.svg 重新生成全部图标变体
├── docs/                   # 文档
└── .github/workflows/      # CI/CD
```

## 🛠️ 技术栈

| 层级 | 技术 |
|------|------|
| **前端框架** | React 18 + TypeScript |
| **构建工具** | Vite 6 |
| **样式** | TailwindCSS 3 |
| **图表** | Recharts |
| **图标** | Lucide React |
| **状态管理** | Zustand + React Hooks |
| **国际化** | i18next + react-i18next |
| **后端** | Rust + Tauri 2.x |
| **HTTP** | reqwest |
| **数据库** | rusqlite (SQLite) |
| **异步** | tokio |

## 📦 已实现功能

### 核心
- [x] Provider 增删改查（SQLite 持久化）
- [x] 自定义 API 配置（Base URL / Headers / Params）
- [x] 异步并发轮询 + 指数退避重试
- [x] 实时状态推送（WebSocket 风格事件）
- [x] 使用历史记录（7 天滚动窗口）

### UI/UX
- [x] 三大主题（赛博朋克 / 武侠 / 国漫）
- [x] 多语言（中英）
- [x] 数据可视化（余额趋势图）
- [x] 响应式布局
- [x] 系统托盘
- [x] 全局快捷键

### 平台
- [x] Windows (msi / nsis)
- [x] macOS (dmg, aarch64 + x86_64)
- [x] Linux (deb / AppImage)
- [x] Android (开发中)

### DevOps
- [x] GitHub Actions CI
- [x] GitHub Actions Release
- [x] 跨平台自动构建
- [x] 自动发布到 GitHub Releases

## 🗺️ 路线图

- [ ] Android 正式适配
- [ ] Webhook 通知（钉钉/飞书/Slack）
- [ ] 多用户/团队共享配置
- [ ] 数据导出（CSV/Excel）
- [ ] 桌面 Widget 小组件
- [ ] 错误上报（Sentry 集成）

## 🤝 贡献

欢迎 PR 和 Issue！

## 📄 许可证

MIT
