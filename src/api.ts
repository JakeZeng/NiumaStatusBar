import { invoke } from '@tauri-apps/api/core';

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
  quota_5h_total: number | null;
  quota_5h_used: number | null;
  quota_week_remaining: number | null;
  quota_week_total: number | null;
  quota_week_used: number | null;
  quota_month_remaining: number | null;
  quota_month_total: number | null;
  quota_month_used: number | null;
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
};
