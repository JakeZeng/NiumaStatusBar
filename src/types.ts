export interface ProviderConfig {
  id: string;
  name: string;
  provider: 'openai' | 'anthropic' | 'deepseek' | 'gemini' | 'custom';
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
}
