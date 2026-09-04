# Changelog

All notable changes to NiumaStatusBar are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.55] - 2026-xx-xx

### Added
- **Android 2x2 环图 widget**：在 launcher 长按菜单中新增「AI Monitor · 2x2 Ring」选项，与既有 1x2 横条 widget 并存。
  - **Coding Plan provider**：3 个空心环并排显示 5H / 1W / 1M 剩余额度，环心是 "5H" "1W" "1M" 标签，环下显示剩余百分比，第 4 行展示三段相对重置时间。
  - **余额型 provider**：单大环布局，环心显示余额金额，环下显示已用百分比，第 4 行展示「余额 / 总额」。
  - 阈值色（基于剩余 %）：>50% 主题 accent / 20-50% 警告黄 `#FFB020` / ≤20% 危险红 `#F04438`。
  - 沿用既有 30 秒轮播机制（独立前台服务 FGS，与 1x2 互不干扰），30 秒切档所有 enabled provider。
  - 数据陈旧（>15 分钟无新写入）时降为 pending 灰环 + 第 4 行改显示「更新于 N 分钟前」。
  - 1M 月度环：原 `usage_history` 表里没有 `quota_month_remaining_percent` 字段，新增 `UsageSnapshot.monthRemainingPercent()` helper 从 `total/used` 反算。
  - 三套主题（cyberpunk / wuxia / guoman）适配，accent 跟随主题；告警黄 / 告警红跨主题统一。

### Technical Details

#### 新增文件（7）
- `src-tauri/gen/android/app/src/main/res/xml/widget_provider_info_2x2.xml`
- `src-tauri/gen/android/app/src/main/res/layout/widget_2x2_coding.xml`
- `src-tauri/gen/android/app/src/main/res/layout/widget_2x2_balance.xml`
- `src-tauri/gen/android/app/src/main/java/com/aimonitor/app/widget/RingRenderer.kt` (~120 行)
- `src-tauri/gen/android/app/src/main/java/com/aimonitor/app/widget/RingTheme.kt` (~80 行)
- `src-tauri/gen/android/app/src/main/java/com/aimonitor/app/widget/RingWidgetLayoutBuilder.kt` (~250 行)
- `src-tauri/gen/android/app/src/main/java/com/aimonitor/app/widget/RingWidgetProvider.kt` (~150 行)
- `src-tauri/gen/android/app/src/main/java/com/aimonitor/app/widget/RingWidgetCarouselService.kt` (~250 行)

#### 修改文件（3）
- `src-tauri/gen/android/app/src/main/java/com/aimonitor/app/widget/UsageSnapshot.kt` — 新增 `monthRemainingPercent()` + `relativeResetLabel(period)` 2 个 helper
- `src-tauri/gen/android/app/src/main/AndroidManifest.xml` — 新增 1 个 receiver（2x2）+ 1 个 service（2x2 carousel FGS）
- `src-tauri/gen/android/app/src/main/res/values/widget_strings.xml` — 新增 2x2 widget 标签/描述、轮播服务频道、余额型副文本模板

#### 1x2 widget 行为
**完全不变**。`UsageWidgetProvider` / `UsageWidgetCarouselService` / `WidgetLayoutBuilder` / `WidgetDataReader` / `WidgetTheme` 全部未触碰；只是新增了与之并行的「2x2」组件。

#### RemoteViews 约束
- 环图通过 `RingRenderer` 在每次轮播 tick 时构造 `Bitmap`，用 `setImageViewBitmap` 喂给 `ImageView`。
- LRU 缓存 200 entry（按规格 hash 复用），3 主题 × 100 量化档 × 几组中心文字，命中率足够。
- 单 widget 实例内存：3 × 44×44 RGBA = 18KB（Coding Plan）或 96×96 RGBA = 36KB（余额型），远低于 RemoteViews 1MB/widget 限制。

### Known Caveats / Notes
- **1M 重置时间字段缺失**：`usage_history` 表未持久化 `quota_month_reset_at`，第 4 行 1M 位置显示「—」。如未来需要展示，可让 Rust 端补写 `quota_month_reset_at` 字段。
- **不同 launcher cell 尺寸差异**：固定 44dp / 96dp 环大小，未做 `onAppWidgetOptionsChanged` 自适应。主流 launcher（Pixel / 小米 / 华为 / 三星）抽样后再决定是否上自适应。
- **Bitmap 重画时机**：每次轮播 tick（30 秒）重画一次；widget 进程被杀后系统会重新拉起并按 RemoteViews 重新渲染，Bitmap 自动恢复。
- **FGS subtype property 解释**：manifest 文本声明 widget 轮播「无网络传输、用户数据不离设备」，用于通过 Play Store / 国内应用市场审核。

### Verification Checklist（用户 / 团队 review 用）
- [ ] 2x2 picker 在 4 套主流 launcher 都能选到
- [ ] Coding Plan 3 环：5H / 1W / 1M 中心标签 + 环下剩余% 正确
- [ ] 1M 百分比从 `(total - used) / total` 反算正确（手算对照）
- [ ] 余额型单大环：环内余额数字 + 环下已用% + 第 4 行余额/总额
- [ ] 阈值变色 3 档（>50% / 20-50% / ≤20%）在 3 套主题下都对
- [ ] 第 4 行重置时间：5H 用「5H 0:23 后」、week 用「周 5d 后」、month 显示「—」
- [ ] 1M 数据缺失（DB 列 null）时该环画 pending 灰，不崩
- [ ] 数据陈旧（>15min）时 3 环降为灰环 + 第 4 行「更新于 N 分钟前」
- [ ] 多 provider 30s 轮播正常
- [ ] 单 provider 不显示页码 `(1/1)`
- [ ] **1x2 widget 行为完全不变**（回归测试）
- [ ] 两个 FGS（1x2 carousel + 2x2 carousel）共存互不干扰
- [ ] widget 进程被杀后能恢复（Bitmap 重新绘制）
- [ ] 切换主题（cyberpunk ↔ wuxia ↔ guoman）后环图色与 App 同步（最大延迟 30s）

### Release Note（团队 / 用户公告文案）

> **v0.1.55 新增 2x2 环图 widget**
>
> 桌面长按菜单现在多了一个「AI Monitor · 2x2 Ring」选项，可以放 2x2 大小的环图 widget：
> - **Coding Plan 用户**（minimax / 火山方舟 Coding Plan）：3 个空心环并排，分别展示 5H / 1W / 1M 三档剩余额度，环心是时段标签，环下是百分比，第 4 行是相对重置时间。
> - **余额型用户**（deepseek / openai / anthropic 等）：单大环，环心显示余额金额，环下显示已用百分比，第 4 行展示「余额 / 总额」。
> - 颜色按剩余额度自动切换：>50% 用主题色、20-50% 警告黄、≤20% 危险红。
> - 30 秒自动轮播所有已启用的 provider，单 provider 时不显示页码。
>
> 既有 1x2 横条 widget 行为完全不变，可以两种 widget 同时存在。
>
> 注意：1M（月度）重置时间本版暂未展示（数据源未存），第 4 行 1M 位置显示「—」。
