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
  providerId: string;
  timestamp: number;
  balance: number | null;
  balanceUsed: number | null;
  balanceLimit: number | null;
  requestsToday: number | null;
  errorRate: number | null;
  avgLatency: number | null;
  lastError: string | null;
}
