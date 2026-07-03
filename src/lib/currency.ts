// 余额币种解析工具——ProviderCard 和 HistoryChart 共享。

// ISO 4217 货币代码 → 显示符号
export const CURRENCY_SYMBOLS: Record<string, string> = {
  CNY: '¥',
  RMB: '¥',
  USD: '$',
  EUR: '€',
  JPY: '¥',
  KRW: '₩',
  HKD: 'HK$',
  GBP: '£',
};

// provider 类型 → 兜底币种（后端未返回 currency 字段时使用）
export const PROVIDER_DEFAULT_CURRENCY: Record<string, string> = {
  deepseek: 'CNY',
  zhipu: 'CNY',
  qwen: 'CNY',
  minimax_coding: 'CNY',
  minimax_token: 'USD',
  volcengine_coding: 'CNY',
  volcengine_token: 'CNY',
  openai: 'USD',
  anthropic: 'USD',
  gemini: 'USD',
  moonshot: 'CNY',
  custom: 'USD',
};

/**
 * 解析余额应展示的币种符号。
 * 优先级：后端 status.currency → provider 类型兜底 → USD
 */
export function getCurrencySymbol(
  statusCurrency: string | null | undefined,
  providerType: string | null | undefined
): string {
  const code = statusCurrency || PROVIDER_DEFAULT_CURRENCY[providerType || ''] || 'USD';
  return CURRENCY_SYMBOLS[code.toUpperCase()] || `${code} `;
}
