/**
 * 平台 / 设备能力判断。
 *
 * - isCoarsePointer: 触屏为主（手机/平板/折叠屏）— 用于关闭持续动画、降低 backdrop-blur 等。
 *   用 `pointer: coarse` 媒体查询 + `navigator.maxTouchPoints` 双保险，覆盖 Android WebView
 *   不响应 media query 的情况。
 * - isDesktop: 与之相反，鼠标/键盘为主。
 *
 * 服务端渲染或测试环境下默认走桌面分支（安全降级）。
 */
export function isCoarsePointer(): boolean {
  if (typeof window === 'undefined' || typeof navigator === 'undefined') return false;
  if (window.matchMedia?.('(pointer: coarse)').matches) return true;
  // Android WebView 兜底：UA 含 Android + maxTouchPoints > 0 视为触屏
  if (/Android/i.test(navigator.userAgent) && (navigator.maxTouchPoints ?? 0) > 0) return true;
  if (/iPhone|iPad|iPod/i.test(navigator.userAgent)) return true;
  return false;
}

export function isDesktop(): boolean {
  return !isCoarsePointer();
}
