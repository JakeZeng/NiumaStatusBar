// 重置时间格式化工具——前端 ProviderCard 和托盘显示都用
// 输入 unix 秒，输出人类可读的 "Xh Ym 后重置" / "Xm 后重置" / "已重置"。

export function formatRelativeReset(resetAtUnixSec: number | null | undefined): string {
  if (resetAtUnixSec == null) return '';
  const nowSec = Math.floor(Date.now() / 1000);
  const diffSec = resetAtUnixSec - nowSec;
  if (diffSec <= 0) return '已重置';

  const days = Math.floor(diffSec / 86400);
  const hours = Math.floor((diffSec % 86400) / 3600);
  const minutes = Math.floor((diffSec % 3600) / 60);

  if (days > 0) {
    return hours > 0 ? `${days}d${hours}h 后重置` : `${days}d 后重置`;
  }
  if (hours > 0) {
    return minutes > 0 ? `${hours}h${minutes}m 后重置` : `${hours}h 后重置`;
  }
  return `${minutes}m 后重置`;
}
