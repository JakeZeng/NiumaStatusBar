import { memo, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { CheckCircle, XCircle, RefreshCw, Trash2 } from 'lucide-react';
import type { ProviderConfig } from '../api';
import { getCurrencySymbol } from '../lib/currency';
import { formatRelativeReset, quotaBarColorClass } from '../lib/format';
import { useStatusStore } from '../store/statusStore';

interface Props {
  provider: ProviderConfig;
  onDelete?: (p: ProviderConfig) => void;
}

interface QuotaRowProps {
  label: string;
  remaining: number | null | undefined;
  total: number | null | undefined;
  used: number | null | undefined;
  remainingPercent?: number | null;
  resetAt?: number | null;
}

const QuotaRow = memo(function QuotaRow({
  label, remaining, total, used, remainingPercent, resetAt,
}: QuotaRowProps) {
  const hasPercent = remainingPercent != null;
  const hasTotalUsed =
    total != null && total !== undefined && total > 0 &&
    used != null && used !== undefined;
  const hasRemaining =
    !hasPercent && !hasTotalUsed &&
    remaining != null && remaining !== undefined;
  // 只有 total、没有 remaining/used/percent（比如火山方舟刚开通、未产生调用时仅返回 limit 头）
  const hasTotalOnly =
    !hasPercent && !hasTotalUsed && !hasRemaining &&
    total != null && total !== undefined && total > 0;
  const hasData = hasPercent || hasTotalUsed || hasRemaining || hasTotalOnly;

  const percent = hasPercent
    ? remainingPercent!
    : hasTotalUsed
      ? (used! / total!) * 100
      : hasRemaining
        ? Math.min(remaining!, 100)
        : hasTotalOnly
          ? 0
          : 0;

  // 配色按"剩余比例"切档：≥50% 绿，20-50% 黄，<20% 红。
  // 注意 percent 在 hasTotalUsed 分支下是"已用%"，要先换算回"剩余%"。
  const remainingPct = hasPercent
    ? remainingPercent!
    : hasTotalUsed
      ? Math.max(0, 100 - (used! / total!) * 100)
      : hasRemaining
        ? Math.min(remaining!, 100)
        : 100;
  const barColor = quotaBarColorClass(remainingPct);

  const resetText = resetAt ? formatRelativeReset(resetAt) : '';

  return (
    <div className="space-y-1">
      <div className="flex items-baseline justify-between">
        <span className="text-xs text-[var(--text-secondary)]">{label}</span>
        <span className="text-sm font-semibold text-[var(--text-primary)]">
          {hasData ? (
            hasPercent ? (
              <span className="text-[var(--color-primary)]">{remainingPercent!.toFixed(0)}%</span>
            ) : hasTotalOnly ? (
              <>
                <span className="text-[var(--text-muted)]">总量 </span>
                <span className="text-[var(--color-primary)]">{Math.floor(total!)}</span>
              </>
            ) : (
              <>
                <span className="text-[var(--color-primary)]">{Math.floor(remaining ?? 0)}</span>
                <span className="text-[var(--text-muted)] text-xs"> / {Math.floor(total || 0)}</span>
              </>
            )
          ) : '--'}
        </span>
      </div>
      <div className="relative h-1.5 bg-[var(--bg-secondary)] rounded-full overflow-hidden">
        {hasData && (
          <div
            className={`absolute inset-y-0 left-0 rounded-full transition-all duration-500 ${barColor}`}
            style={{ width: `${Math.min(percent, 100)}%` }}
          />
        )}
      </div>
      {resetText && (
        <div className="text-[10px] text-[var(--text-muted)] text-right">
          {resetText}
        </div>
      )}
    </div>
  );
});

function StatusIcon({ loading, hasError }: { loading: boolean; hasError: boolean }) {
  if (loading) return <RefreshCw className="w-5 h-5 text-[var(--color-warning)] animate-spin" />;
  if (hasError) return <XCircle className="w-5 h-5 text-[var(--color-danger)]" />;
  return <CheckCircle className="w-5 h-5 text-[var(--color-success)]" />;
}

// ProviderCard 改为纯展示：状态从全局 store 订阅，刷新动作也走 store.fetchOne。
// 这样 N 张卡只共享一份 status-update 事件，状态变化只触发相关卡片重渲染。
function ProviderCardImpl({ provider, onDelete }: Props) {
  const { t } = useTranslation();
  // selector 精确订阅自身相关字段，避免无关 provider 状态变化导致本卡 re-render
  const status = useStatusStore((s) => s.byId[provider.id]);
  const loading = useStatusStore((s) => s.loadingById[provider.id] ?? false);
  const fetchOne = useStatusStore((s) => s.fetchOne);

  const handleFetch = useCallback(() => {
    fetchOne(provider.id).catch(() => { /* 错误已在 store 内处理 */ });
  }, [fetchOne, provider.id]);

  const handleDelete = useCallback(() => {
    onDelete?.(provider);
  }, [onDelete, provider]);

  // 判断是否使用 Coding Plan 多维度展示
  const isCodingPlan =
    provider.provider === 'minimax_coding' ||
    provider.provider === 'minimax_token' ||
    provider.provider === 'volcengine_coding' ||
    provider.provider === 'volcengine_token';

  const hasQuota5h =
    status?.quota_5h_remaining_percent != null ||
    status?.quota_5h_remaining != null;
  const hasQuotaWeek =
    status?.quota_week_remaining_percent != null ||
    status?.quota_week_remaining != null;
  const hasQuotaMonth = status?.quota_month_remaining != null && status?.quota_month_remaining !== undefined;

  // 通用余额展示
  const usagePercent = status?.balance_limit && status.balance_used
    ? (status.balance_used / status.balance_limit) * 100
    : 0;
  // 已用% 换算成剩余%，与配额条共用同一套阈值配色
  const balanceRemainingPct = status?.balance_limit && status.balance_limit > 0
    ? Math.max(0, 100 - usagePercent)
    : 100;
  const balanceBarColor = quotaBarColorClass(balanceRemainingPct);

  return (
    <div className={`provider-card relative overflow-hidden rounded-xl p-3 sm:p-4 lg:p-5
                    bg-[var(--bg-card)] border transition-colors duration-150
                    ${provider.isEnabled
                      ? 'border-[var(--border-color)] shadow-[var(--shadow-card)]'
                      : 'border-[var(--border-color)]/40 opacity-60 grayscale'}`}>

      {!provider.isEnabled && (
        <div className="absolute top-2 right-2 px-2 py-0.5 text-[10px] sm:text-xs
                        rounded-full bg-[var(--bg-overlay)] text-[var(--text-muted)]
                        border border-[var(--border-color)] z-10">
          已禁用
        </div>
      )}

      <div className="absolute top-0 right-0 w-12 h-12 sm:w-16 sm:h-16
                      bg-gradient-to-bl from-[var(--color-primary)]/20 to-transparent
                      rounded-bl-full" />

      <div className="flex items-center justify-between mb-3 sm:mb-4">
        <div className="flex items-center gap-2 sm:gap-3 min-w-0">
          <StatusIcon loading={loading} hasError={!!status?.last_error} />
          <div className="min-w-0 flex-1">
            <h3 className="font-bold text-sm sm:text-base text-[var(--text-primary)] truncate">{provider.name}</h3>
            <p className="text-xs text-[var(--text-secondary)] uppercase tracking-wider truncate">
              {provider.provider}
            </p>
          </div>
        </div>
        <div className="flex gap-2">
          <button
            onClick={handleFetch}
            disabled={loading}
            className="p-2 rounded-lg hover:bg-[var(--bg-overlay)]
                       text-[var(--text-secondary)] hover:text-[var(--color-primary)]
                       transition-colors"
            title="刷新"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          </button>
          {onDelete && (
            <button
              onClick={handleDelete}
              className="p-2 rounded-lg hover:bg-[var(--bg-overlay)]
                         text-[var(--text-secondary)] hover:text-[var(--color-danger)]
                         transition-colors"
              title={t('provider.delete')}
              aria-label={t('provider.delete')}
            >
              <Trash2 className="w-4 h-4" />
            </button>
          )}
        </div>
      </div>

      {/* Coding Plan：多维度额度展示 */}
      {isCodingPlan ? (
        <div className="space-y-3 mb-3">
          {hasQuota5h && (
            <QuotaRow
              label={t('provider.quotaLabel5h')}
              remaining={status?.quota_5h_remaining}
              total={status?.quota_5h_total}
              used={status?.quota_5h_used}
              remainingPercent={status?.quota_5h_remaining_percent}
              resetAt={status?.quota_5h_reset_at}
            />
          )}
          {hasQuotaWeek && (
            <QuotaRow
              label={t('provider.quotaLabelWeek')}
              remaining={status?.quota_week_remaining}
              total={status?.quota_week_total}
              used={status?.quota_week_used}
              remainingPercent={status?.quota_week_remaining_percent}
              resetAt={status?.quota_week_reset_at}
            />
          )}
          {hasQuotaMonth && (
            <QuotaRow
              label={t('provider.quotaLabelMonth')}
              remaining={status?.quota_month_remaining}
              total={status?.quota_month_total}
              used={status?.quota_month_used}
              remainingPercent={null}
              resetAt={status?.quota_month_reset_at}
            />
          )}
          {!hasQuota5h && !hasQuotaWeek && !hasQuotaMonth && (
            <div className="text-center py-4 text-sm text-[var(--text-muted)]">
              {t('provider.quotaWaiting')}
            </div>
          )}
        </div>
      ) : (
        /* 通用余额展示 */
        <div className="mb-4">
          <div className="flex items-baseline justify-between mb-2 gap-2">
            <span className="text-sm text-[var(--text-secondary)] whitespace-nowrap">{t('provider.balance')}</span>
            <span className="text-lg sm:text-xl font-bold text-[var(--color-primary)]
                            drop-shadow-[var(--glow-primary)] truncate min-w-0">
              {status?.balance !== null && status?.balance !== undefined
                ? `${getCurrencySymbol(status.currency, provider.provider)}${status.balance.toFixed(2)}`
                : '--'}
            </span>
          </div>

          <div className="relative h-2 bg-[var(--bg-secondary)] rounded-full overflow-hidden">
            <div
              className={`absolute inset-y-0 left-0 rounded-full transition-all duration-500 ${balanceBarColor}`}
              style={{ width: `${Math.min(usagePercent, 100)}%` }}
            >
              {/* 余额条内部的 animate-pulse 移除 — 移动端持续动画拖累主线程合成 */}
            </div>
          </div>
        </div>
      )}

      {/* 底部信息行：只保留 latency。
         之前的"总剩余（5h+周剩余求和，跨窗口无意义）"和"今日请求（与额度无关）"
         已移除，避免给出误导性指标。 */}
      <div className="pt-3 border-t border-[var(--border-color)]/50">
        <div className="bg-[var(--bg-secondary)]/50 rounded-lg p-2">
          <div className="text-xs text-[var(--text-muted)]">{t('provider.latency')}</div>
          <div className="text-sm font-semibold text-[var(--text-primary)]">
            {status?.avg_latency ? `${status.avg_latency}ms` : '--'}
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

// 用 React.memo + 自定义比较：只要 provider 引用不变就不重渲染。
// 关键：onDelete 必须稳定（见 App.tsx 改成 useCallback），否则 memo 形同虚设。
export const ProviderCard = memo(
  ProviderCardImpl,
  (prev, next) =>
    prev.provider === next.provider &&
    prev.onDelete === next.onDelete,
);
