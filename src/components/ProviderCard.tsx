import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { CheckCircle, XCircle, RefreshCw, Settings } from 'lucide-react';
import type { ProviderConfig, UsageStatus } from '../api';

interface Props {
  provider: ProviderConfig;
  onEdit: (p: ProviderConfig) => void;
}

interface QuotaRowProps {
  label: string;
  remaining: number | null | undefined;
  total: number | null | undefined;
  used: number | null | undefined;
}

function QuotaRow({ label, remaining, total, used }: QuotaRowProps) {
  const hasData = remaining !== null && remaining !== undefined;
  const percent = total && used !== null && used !== undefined && total > 0
    ? (used / total) * 100
    : 0;

  return (
    <div className="space-y-1">
      <div className="flex items-baseline justify-between">
        <span className="text-xs text-[var(--text-secondary)]">{label}</span>
        <span className="text-sm font-semibold text-[var(--text-primary)]">
          {hasData ? (
            <>
              <span className="text-[var(--color-primary)]">{Math.floor(remaining!)}</span>
              <span className="text-[var(--text-muted)] text-xs"> / {Math.floor(total || 0)}</span>
            </>
          ) : '--'}
        </span>
      </div>
      <div className="relative h-1.5 bg-[var(--bg-secondary)] rounded-full overflow-hidden">
        {hasData && (
          <div
            className="absolute inset-y-0 left-0 bg-gradient-to-r 
                       from-[var(--color-primary)] to-[var(--color-secondary)]
                       rounded-full transition-all duration-500"
            style={{ width: `${Math.min(percent, 100)}%` }}
          />
        )}
      </div>
    </div>
  );
}

export function ProviderCard({ provider, onEdit }: Props) {
  const { t } = useTranslation();
  const [status, setStatus] = useState<UsageStatus | null>(null);
  const [loading, setLoading] = useState(false);

  // 监听后端推送的状态更新
  useEffect(() => {
    const unlisten = listen<UsageStatus>('status-update', (event) => {
      if (event.payload.provider_id === provider.id) {
        setStatus(event.payload);
        setLoading(false);
      }
    });

    return () => {
      unlisten.then(fn => fn());
    };
  }, [provider.id]);

  // 初始加载状态
  useEffect(() => {
    const loadStatus = async () => {
      try {
        const result = await invoke<UsageStatus | null>('get_provider_status', { id: provider.id });
        if (result) {
          setStatus(result);
        }
      } catch (err) {
        console.error(err);
      }
    };
    loadStatus();
  }, [provider.id]);

  const fetchStatus = async () => {
    setLoading(true);
    try {
      const result = await invoke<UsageStatus>('fetch_provider_status', { id: provider.id });
      setStatus(result);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const getStatusIcon = () => {
    if (loading) return <RefreshCw className="w-5 h-5 text-[var(--color-warning)] animate-spin" />;
    if (status?.last_error) return <XCircle className="w-5 h-5 text-[var(--color-danger)]" />;
    return <CheckCircle className="w-5 h-5 text-[var(--color-success)]" />;
  };

  // 判断是否使用 Coding Plan 多维度展示
  const isCodingPlan = 
    provider.provider === 'minimax_coding' ||
    provider.provider === 'minimax_token' ||
    provider.provider === 'volcengine_coding' ||
    provider.provider === 'volcengine_token';

  const hasQuota5h = status?.quota_5h_remaining !== null && status?.quota_5h_remaining !== undefined;
  const hasQuotaWeek = status?.quota_week_remaining !== null && status?.quota_week_remaining !== undefined;
  const hasQuotaMonth = status?.quota_month_remaining !== null && status?.quota_month_remaining !== undefined;

  // 通用余额展示
  const usagePercent = status?.balance_limit && status.balance_used
    ? (status.balance_used / status.balance_limit) * 100
    : 0;

  return (
    <div className="relative overflow-hidden rounded-xl p-5 
                    bg-[var(--bg-card)] border border-[var(--border-color)]
                    shadow-[var(--shadow-card)]
                    hover:shadow-[var(--glow-primary)] transition-all duration-300
                    backdrop-blur-md">

      <div className="absolute top-0 right-0 w-16 h-16 
                      bg-gradient-to-bl from-[var(--color-primary)]/20 to-transparent
                      rounded-bl-full" />
      
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-3">
          {getStatusIcon()}
          <div>
            <h3 className="font-bold text-lg text-[var(--text-primary)]">{provider.name}</h3>
            <p className="text-xs text-[var(--text-secondary)] uppercase tracking-wider">
              {provider.provider}
            </p>
          </div>
        </div>
        <div className="flex gap-2">
          <button
            onClick={fetchStatus}
            disabled={loading}
            className="p-2 rounded-lg hover:bg-[var(--bg-overlay)] 
                       text-[var(--text-secondary)] hover:text-[var(--color-primary)]
                       transition-colors"
            title="刷新"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          </button>
          <button
            onClick={() => onEdit(provider)}
            className="p-2 rounded-lg hover:bg-[var(--bg-overlay)] 
                       text-[var(--text-secondary)] hover:text-[var(--color-primary)]
                       transition-colors"
            title="编辑"
          >
            <Settings className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Coding Plan：多维度额度展示 */}
      {isCodingPlan ? (
        <div className="space-y-3 mb-3">
          {hasQuota5h && (
            <QuotaRow 
              label="⏱ 5小时额度" 
              remaining={status?.quota_5h_remaining}
              total={status?.quota_5h_total}
              used={status?.quota_5h_used}
            />
          )}
          {hasQuotaWeek && (
            <QuotaRow 
              label="📅 周额度" 
              remaining={status?.quota_week_remaining}
              total={status?.quota_week_total}
              used={status?.quota_week_used}
            />
          )}
          {hasQuotaMonth && (
            <QuotaRow 
              label="📆 月额度" 
              remaining={status?.quota_month_remaining}
              total={status?.quota_month_total}
              used={status?.quota_month_used}
            />
          )}
          {!hasQuota5h && !hasQuotaWeek && !hasQuotaMonth && (
            <div className="text-center py-4 text-sm text-[var(--text-muted)]">
              等待额度数据返回...
            </div>
          )}
        </div>
      ) : (
        /* 通用余额展示 */
        <div className="mb-4">
          <div className="flex items-baseline justify-between mb-2">
            <span className="text-sm text-[var(--text-secondary)]">{t('provider.balance')}</span>
            <span className="text-2xl font-bold text-[var(--color-primary)] 
                            drop-shadow-[var(--glow-primary)]">
              {status?.balance !== null && status?.balance !== undefined
                ? `$${status.balance.toFixed(2)}`
                : '--'}
            </span>
          </div>

          <div className="relative h-2 bg-[var(--bg-secondary)] rounded-full overflow-hidden">
            <div
              className="absolute inset-y-0 left-0 bg-gradient-to-r 
                         from-[var(--color-primary)] to-[var(--color-secondary)]
                         rounded-full transition-all duration-500"
              style={{ width: `${Math.min(usagePercent, 100)}%` }}
            >
              <div className="absolute inset-0 bg-white/20 animate-pulse" />
            </div>
          </div>
        </div>
      )}

      {/* 底部信息行 */}
      <div className="grid grid-cols-2 gap-3 pt-3 border-t border-[var(--border-color)]/50">
        <div className="bg-[var(--bg-secondary)]/50 rounded-lg p-2">
          <div className="text-xs text-[var(--text-muted)]">{t('provider.latency')}</div>
          <div className="text-sm font-semibold text-[var(--text-primary)]">
            {status?.avg_latency ? `${status.avg_latency}ms` : '--'}
          </div>
        </div>
        <div className="bg-[var(--bg-secondary)]/50 rounded-lg p-2">
          <div className="text-xs text-[var(--text-muted)]">
            {isCodingPlan ? '总剩余' : t('provider.requestsToday')}
          </div>
          <div className="text-sm font-semibold text-[var(--text-primary)]">
            {isCodingPlan 
              ? Math.floor((status?.quota_5h_remaining || 0) + (status?.quota_week_remaining || 0))
              : (status?.requests_today ?? '--')}
          </div>
        </div>
      </div>

      {/* 错误提示 */}
      {status?.last_error && (
        <div className="mt-2 p-2 bg-red-50/10 text-[var(--color-danger)] text-xs rounded
                       truncate" title={status.last_error}>
          ⚠ {status.last_error}
        </div>
      )}
    </div>
  );
}