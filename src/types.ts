export interface ProviderConfig {
  id: string;
  name: string;
  provider: 'openai' | 'anthropic' | 'deepseek' | 'gemini' | 'custom'
          | 'minimax_coding' | 'minimax_token' | 'volcengine_coding';
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

  // 多周期额度（MiniMax / Coding Plan）
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