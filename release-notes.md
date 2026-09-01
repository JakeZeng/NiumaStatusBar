# 发版草稿

> 维护者在打 tag 之前更新本文件，然后同步把内容复制到 `.github/workflows/release.yml` 的
> `tauri-action` step 的 `releaseBody` 字段里。CI 不会自动读取本文件（避免发版时忘记同步），
> 手动同步一次即可。

---

## v0.1.40+ (待发版)

### 修复

- **桌面组件尺寸修正（Android）**：之前 `minWidth=180dp` 让部分 launcher
  按 `minWidth / 80dp/格` ceil 到 3 cells，widget 显示为 1×3。
  现在 `minWidth=160dp` + `paddingHorizontal=8dp`，所有 launcher 稳定显示 1×2。
  **已添加 widget 的用户请长按移除后重新添加**（Android 不会自动 resize 已放置的 widget）。

### 新增

- 1x2 横条 widget 每 5 秒自动轮播所有 enabled provider（v0.1.39）
- 多 provider 时横条末尾显示 `1/3` 页码
- 清理：删除 `StatusWidgetProvider` / `StatusWidgetService` 等 v0.1.18–v0.1.36 的 2x3 卡片 widget 死代码

### 注意事项（给团队成员）

- widget 轮播是前台服务（`UsageWidgetCarouselService`），通知栏会显示常驻通知「桌面小组件正在每 5 秒轮播供应商」
- Android 13+ 需要授予通知权限，否则 service 会被立即 kill
- 卸载 widget 后服务自动停止
