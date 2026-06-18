import { invoke } from '@tauri-apps/api/core';
import { useState, useEffect } from 'react';
import { CheckCircle, XCircle, RefreshCw, Settings } from 'lucide-react';
import type { ProviderConfig, UsageStatus } from '../types';

interface Props {
  provider: ProviderConfig;
  onEdit: (p: ProviderConfig) => void;
}

export function ProviderCard({ provider, onEdit }: Props) {
  const [status, setStatus] = useState<UsageStatus | null>(null);
  const [loading, setLoading] = useState(false);

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

  useEffect(() => {
    if (provider.isEnabled) {
      fetchStatus();
      const timer = setInterval(fetchStatus, provider.refreshInterval * 1000);
      return () => clearInterval(timer);
    }
  }, [provider.id, provider.refreshInterval, provider.isEnabled]);

  const getStatusIcon = () => {
    if (loading) return <RefreshCw className="w-5 h-5 text-[var(--color-warning)] animate-spin" />;
    if (status?.lastError) return <XCircle className="w-5 h-5 text-[var(--color-danger)]" />;
    return <CheckCircle className="w-5 h-5 text-[var(--color-success)]" />;
  };

  const usagePercent = status?.balanceLimit && status.balanceUsed
    ? (status.balanceUsed / status.balanceLimit) * 100
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
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          </button>
          <button
            onClick={() => onEdit(provider)}
            className="p-2 rounded-lg hover:bg-[var(--bg-overlay)] 
                       text-[var(--text-secondary)] hover:text-[var(--color-primary)]
                       transition-colors"
          >
            <Settings className="w-4 h-4" />
          </button>
        </div>
      </div>

      <div className="mb-4">
        <div className="flex items-baseline justify-between mb-2">
          <span className="text-sm text-[var(--text-secondary)]">余额</span>
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

      <div className="grid grid-cols-2 gap-3">
        <div className="bg-[var(--bg-secondary)]/50 rounded-lg p-2">
          <div className="text-xs text-[var(--text-muted)]">延迟</div>
          <div className="text-sm font-semibold text-[var(--text-primary)]">
            {status?.avgLatency ? `${status.avgLatency}ms` : '--'}
          </div>
        </div>
        <div className="bg-[var(--bg-secondary)]/50 rounded-lg p-2">
          <div className="text-xs text-[var(--text-muted)]">今日请求</div>
          <div className="text-sm font-semibold text-[var(--text-primary)]">
            {status?.requestsToday ?? '--'}
          </div>
        </div>
      </div>
    </div>
  );
}
