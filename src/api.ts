import { invoke } from '@tauri-apps/api/core';

/**
 * 桌面端判定：托盘 / 开机自启 等仅在桌面端有意义，移动端调用后端会找不到 handler。
 * 用 `__TAURI_INTERNALS__?.platform` 区分（"macos" / "windows" / "linux" 视为桌面，
 * "android" / "ios" 视为移动）；同时兜底 navigator.userAgent。
 */
function isDesktopPlatform(): boolean {
  if (typeof window === 'undefined') return false;
  const w = window as unknown as {
    __TAURI_INTERNALS__?: { platform?: string };
  };
  const platform = w.__TAURI_INTERNALS__?.platform;
  if (platform) {
    return platform === 'macos' || platform === 'windows' || platform === 'linux';
  }
  const ua = (typeof navigator !== 'undefined' ? navigator.userAgent : '') || '';
  return !/Android|iPhone|iPad|iPod/i.test(ua);
}

export interface ProviderPreset {
  id: string;
  name: string;
  provider_type: string;
  base_url: string;
  query_endpoint: string;
  query_method: string;
  default_headers: Record<string, string>;
  default_refresh_interval: number;
  category: 'domestic' | 'overseas' | 'coding_plan' | 'custom';
  description: string;
  docs_url: string;
  requires_body: boolean;
  default_model: string | null;
}

export interface ProviderConfig {
  id: string;
  name: string;
  provider: string;
  baseUrl: string;
  apiKey: string;
  queryEndpoint: string;
  queryMethod: 'GET' | 'POST';
  queryHeaders: Record<string, string>;
  queryParams: Record<string, any>;
  refreshInterval: number;
  isEnabled: boolean;
  status: 'active' | 'error' | 'checking' | 'disabled';
}

export interface UsageStatus {
  provider_id: string;
  timestamp: number;
  balance: number | null;
  balance_used: number | null;
  balance_limit: number | null;
  requests_today: number | null;
  error_rate: number | null;
  avg_latency: number | null;
  last_error: string | null;
  quota_5h_remaining: number | null;
  quota_5h_remaining_percent: number | null;
  quota_5h_total: number | null;
  quota_5h_used: number | null;
  quota_week_remaining: number | null;
  quota_week_remaining_percent: number | null;
  quota_week_total: number | null;
  quota_week_used: number | null;
  quota_month_remaining: number | null;
  quota_month_total: number | null;
  quota_month_used: number | null;
  /** 余额币种（ISO 4217）。未设置时前端按 provider 类型兜底 */
  currency?: string | null;
  /** 5h 窗口下次重置时间（unix 秒）。未设置时前端不显示重置提示 */
  quota_5h_reset_at?: number | null;
  /** 周窗口下次重置时间（unix 秒） */
  quota_week_reset_at?: number | null;
  /** 月窗口下次重置时间（unix 秒）。当前无 Provider 填充 */
  quota_month_reset_at?: number | null;
}

// ===== 应用日志 =====
// 与 Rust 端 LogEntry 字段对齐（snake_case）
export type LogLevel = 'debug' | 'info' | 'warn' | 'error';
export type LogCategory =
  | 'http' | 'provider' | 'poller' | 'database'
  | 'command' | 'setup' | 'tray' | 'system';

export interface LogEntry {
  id: number;
  timestamp: number;
  level: LogLevel;
  category: LogCategory;
  source: string | null;
  message: string;
  details: string | null;
}

export interface LogQuery {
  keyword?: string;
  levels?: LogLevel[];
  categories?: LogCategory[];
  since?: number;
  until?: number;
  limit?: number;
}

// API 封装
export const api = {
  getProviders: () => invoke<ProviderConfig[]>('get_providers'),
  addProvider: (provider: ProviderConfig) => 
    invoke('add_provider', { provider }),
  updateProvider: (id: string, provider: ProviderConfig) => 
    invoke('update_provider', { id, provider }),
  deleteProvider: (id: string) => 
    invoke('delete_provider', { id }),
  fetchProviderStatus: (id: string) => 
    invoke<UsageStatus>('fetch_provider_status', { id }),
  getProviderStatus: (id: string) => 
    invoke<UsageStatus | null>('get_provider_status', { id }),
  getUsageHistory: (providerId: string, limit = 50, since?: number) => 
    invoke<UsageStatus[]>('get_usage_history', { providerId, limit, since }),
  exportConfig: () => invoke<string>('export_config'),
  importConfig: (json: string) => invoke<ProviderConfig[]>('import_config', { json }),
  
  // ccSwitch 风格
  getProviderCatalog: () => invoke<ProviderPreset[]>('get_provider_catalog'),
  enablePreset: (presetId: string, apiKey: string, customName?: string, refreshInterval?: number) =>
    invoke<ProviderConfig>('enable_preset', {
      presetId,
      apiKey,
      customName,
      refreshInterval
    }),
  toggleProvider: (id: string, enabled: boolean) =>
    invoke('toggle_provider', { id, enabled }),
  getActivePresetIds: () => invoke<string[]>('get_active_preset_ids'),

  // 关闭行为偏好 + 窗口控制
  getCloseAction: () => invoke<string | null>('get_close_action'),
  setCloseAction: (action: 'minimize_to_tray' | 'exit') =>
    invoke('set_close_action', { action }),
  resetCloseAction: () => invoke('reset_close_action'),
  windowHideToTray: () => invoke('window_hide_to_tray'),
  appQuit: () => invoke('app_quit'),

  // 主题偏好（持久化到后端 settings 表，供 Android 桌面组件读取配色）
  setAppTheme: (theme: 'cyberpunk' | 'wuxia' | 'guoman') =>
    invoke('set_app_theme', { theme }),

  // 托盘可见性 / 开机自启（仅桌面端有意义；移动端为 no-op）
  getTrayVisible: () => isDesktopPlatform()
    ? invoke<boolean>('get_tray_visible')
    : Promise.resolve(true),
  setTrayVisible: (visible: boolean) => isDesktopPlatform()
    ? invoke('set_tray_visible', { visible })
    : Promise.resolve(),
  getAutostart: () => isDesktopPlatform()
    ? invoke<boolean>('get_autostart')
    : Promise.resolve(false),
  setAutostart: (enabled: boolean) => isDesktopPlatform()
    ? invoke('set_autostart', { enabled })
    : Promise.resolve(),

  // 应用日志
  queryLogs: (q: LogQuery) => invoke<LogEntry[]>('query_logs', { q }),
  clearLogs: () => invoke<void>('clear_logs'),
};
