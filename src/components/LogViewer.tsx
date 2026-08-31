import { useState, useEffect, useMemo, useRef, useCallback, memo } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Search, ScrollText, X, Pause, Play, Trash2, ChevronDown, ChevronUp,
  AlertTriangle, Info, AlertCircle, Bug,
} from 'lucide-react';
import { listen } from '@tauri-apps/api/event';
import { api, type LogEntry, type LogLevel, type LogCategory, type LogQuery } from '../api';
import { ModalBackdrop } from './ModalBackdrop';
import { isCoarsePointer } from '../lib/device';

interface Props {
  open: boolean;
  onClose: () => void;
}

const LEVELS: LogLevel[] = ['debug', 'info', 'warn', 'error'];
const CATEGORIES: LogCategory[] = [
  'http', 'provider', 'poller', 'database', 'command', 'setup', 'tray', 'system',
];

// 时间范围单选
const TIME_RANGES = [
  { key: '1h', seconds: 3600 },
  { key: '24h', seconds: 86400 },
  { key: '7d', seconds: 604800 },
  { key: 'all', seconds: 0 },
] as const;
type TimeRangeKey = typeof TIME_RANGES[number]['key'];

/**
 * 日志查看弹层
 *
 * - 顶部按钮：搜索框 / Level chips / Category chips / Time range chips / Pause+Clear+Follow
 * - 主区：滚动列表，每行 [time][LEVEL][CAT][source] — message（点开看 details）
 * - 实时事件：listen('app-log', ...) 增量追加（暂停时丢弃）
 * - 滚动跟随：滚到底时新日志自动追加；滚走时显示"继续跟踪"按钮
 */
export const LogViewer = memo(function LogViewer({ open, onClose }: Props) {
  const { t } = useTranslation();

  // ===== State =====
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  // 过滤条件
  const [keyword, setKeyword] = useState('');
  const [debouncedKeyword, setDebouncedKeyword] = useState('');
  const [levels, setLevels] = useState<Set<LogLevel>>(new Set());
  const [categories, setCategories] = useState<Set<LogCategory>>(new Set());
  const [timeRange, setTimeRange] = useState<TimeRangeKey>('all');

  // 实时 + 滚动
  const [paused, setPaused] = useState(false);
  const [autoFollow, setAutoFollow] = useState(true);
  const [latestId, setLatestId] = useState<number | null>(null);

  // UI
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [confirmingClear, setConfirmingClear] = useState(false);

  const scrollRef = useRef<HTMLDivElement>(null);

  // 关键字防抖（300ms）
  useEffect(() => {
    const t = setTimeout(() => setDebouncedKeyword(keyword), 300);
    return () => clearTimeout(t);
  }, [keyword]);

  // 计算 since 时间戳
  const since = useMemo(() => {
    const r = TIME_RANGES.find(r => r.key === timeRange);
    if (!r || r.seconds === 0) return undefined;
    return Math.floor(Date.now() / 1000) - r.seconds;
  }, [timeRange]);

  // ===== 查询 =====
  const runQuery = useCallback(async () => {
    if (!open) return;
    setLoading(true);
    setErrorMsg(null);
    try {
      const q: LogQuery = {
        keyword: debouncedKeyword || undefined,
        levels: levels.size > 0 ? Array.from(levels) : undefined,
        categories: categories.size > 0 ? Array.from(categories) : undefined,
        since,
        limit: 200,
      };
      const list = await api.queryLogs(q);
      // 后端按 DESC 返回，前端 reverse 为 ASC（时间从上到下递增）
      const sorted = [...list].sort((a, b) => a.timestamp - b.timestamp);
      setLogs(sorted);
      setLatestId(sorted.length > 0 ? sorted[sorted.length - 1].id : null);
      // 重新打开时强制滚到底
      requestAnimationFrame(() => scrollToBottom('auto'));
    } catch (e: any) {
      setErrorMsg(String(e));
      setLogs([]);
    } finally {
      setLoading(false);
    }
  }, [open, debouncedKeyword, levels, categories, since]);

  useEffect(() => {
    runQuery();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, debouncedKeyword, levels, categories, since]);

  // ===== 实时事件订阅（批量合并：每 100ms 最多 flush 一次） =====
  const pendingRef = useRef<LogEntry[]>([]);
  const flushTimerRef = useRef<number | null>(null);
  useEffect(() => {
    if (!open) return;
    const un = listen<LogEntry>('app-log', (e) => {
      if (paused) return;
      const entry = e.payload;
      if (!matchesFiltersLocal(entry, debouncedKeyword, levels, categories, since)) {
        return;
      }
      pendingRef.current.push(entry);
      if (flushTimerRef.current != null) return;
      flushTimerRef.current = window.setTimeout(() => {
        flushTimerRef.current = null;
        const batch = pendingRef.current;
        pendingRef.current = [];
        if (batch.length === 0) return;
        setLogs(prev => {
          const next = prev.concat(batch);
          return next.length > 500 ? next.slice(-500) : next;
        });
        setLatestId(batch[batch.length - 1].id);
      }, 100);
    });
    return () => {
      un.then(fn => fn());
      if (flushTimerRef.current != null) {
        clearTimeout(flushTimerRef.current);
        flushTimerRef.current = null;
      }
      pendingRef.current = [];
    };
  }, [open, paused, debouncedKeyword, levels, categories, since]);

  // ===== 滚动行为 =====
  const scrollToBottom = (behavior: ScrollBehavior = 'smooth') => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior });
  };

  const handleScroll = () => {
    const el = scrollRef.current;
    if (!el) return;
    const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    const atBottom = distanceToBottom < 4;
    setAutoFollow(atBottom);
  };

  // 新日志追加时：若 autoFollow，滚到底
  useEffect(() => {
    if (autoFollow && !paused) {
      scrollToBottom('smooth');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [logs.length]);

  // ===== Actions =====
  const toggleLevel = (lv: LogLevel) => {
    setLevels(prev => {
      const next = new Set(prev);
      if (next.has(lv)) next.delete(lv);
      else next.add(lv);
      return next;
    });
  };

  const toggleCategory = (cat: LogCategory) => {
    setCategories(prev => {
      const next = new Set(prev);
      if (next.has(cat)) next.delete(cat);
      else next.add(cat);
      return next;
    });
  };

  const toggleExpanded = useCallback((id: number) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const handleClear = async () => {
    try {
      await api.clearLogs();
      setLogs([]);
      setLatestId(null);
      setConfirmingClear(false);
    } catch (e: any) {
      setErrorMsg(String(e));
    }
  };

  // 重置所有过滤
  const resetFilters = () => {
    setKeyword('');
    setLevels(new Set());
    setCategories(new Set());
    setTimeRange('all');
  };

  if (!open) return null;

  return (
    <ModalBackdrop level="base">
      <div className="relative w-full max-w-6xl max-h-[90vh] overflow-hidden
                      bg-[var(--bg-card)] border border-[var(--border-color)]
                      rounded-2xl shadow-2xl flex flex-col">

        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b border-[var(--border-color)]">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-gradient-to-br from-[var(--color-primary)]/20 to-[var(--color-secondary)]/20">
              <ScrollText className="w-5 h-5 text-[var(--color-primary)]" />
            </div>
            <div>
              <h2 className="text-base sm:text-lg font-bold text-[var(--text-primary)]">
                {t('logs.title', '日志')}
              </h2>
              <p className="text-xs text-[var(--text-secondary)] hidden sm:block">
                {t('logs.subtitle', '查看应用运行日志，支持关键词与事件范围过滤')}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-1">
            <IconAction
              title={paused ? t('logs.actions.resume', '继续') : t('logs.actions.pause', '暂停')}
              onClick={() => setPaused(!paused)}
              active={!paused}
              icon={paused ? <Play className="w-4 h-4" /> : <Pause className="w-4 h-4" />}
            />
            <IconAction
              title={t('logs.actions.clear', '清空')}
              onClick={() => setConfirmingClear(true)}
              icon={<Trash2 className="w-4 h-4" />}
              danger
            />
            <button
              onClick={onClose}
              className="p-2 rounded-lg hover:bg-[var(--bg-overlay)] text-[var(--text-secondary)]"
              title={t('logs.actions.close', '关闭')}
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Filter bar */}
        <div className="p-4 border-b border-[var(--border-color)] space-y-3">
          {/* Search */}
          <div className="flex items-center gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--text-muted)]" />
              <input
                type="text"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                placeholder={t('logs.search', '搜索日志...')}
                className="w-full pl-10 pr-4 py-2 bg-[var(--bg-secondary)]
                         border border-[var(--border-color)] rounded-lg
                         text-[var(--text-primary)] placeholder:text-[var(--text-muted)]
                         focus:outline-none focus:border-[var(--color-primary)] text-sm"
              />
            </div>
            <button
              onClick={resetFilters}
              className="px-3 py-2 text-xs text-[var(--text-secondary)]
                       hover:text-[var(--text-primary)]
                       border border-[var(--border-color)] rounded-lg"
            >
              {t('logs.reset', '重置')}
            </button>
          </div>

          {/* Level chips */}
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-xs text-[var(--text-muted)] mr-1">
              {t('logs.level', '级别')}
            </span>
            {LEVELS.map(lv => (
              <Chip
                key={lv}
                active={levels.has(lv)}
                onClick={() => toggleLevel(lv)}
              >
                {levelIcon(lv)}
                <span className="ml-1">{levelLabel(lv)}</span>
              </Chip>
            ))}
          </div>

          {/* Category chips */}
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-xs text-[var(--text-muted)] mr-1">
              {t('logs.category', '类别')}
            </span>
            {CATEGORIES.map(cat => (
              <Chip
                key={cat}
                active={categories.has(cat)}
                onClick={() => toggleCategory(cat)}
              >
                <span>{cat}</span>
              </Chip>
            ))}
          </div>

          {/* Time range chips */}
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-xs text-[var(--text-muted)] mr-1">
              {t('logs.timeRange', '时间范围')}
            </span>
            {TIME_RANGES.map(r => (
              <Chip
                key={r.key}
                active={timeRange === r.key}
                onClick={() => setTimeRange(r.key)}
              >
                <span>{timeLabel(r.key)}</span>
              </Chip>
            ))}
          </div>
        </div>

        {/* Log list */}
        <div
          ref={scrollRef}
          onScroll={handleScroll}
          className="flex-1 overflow-y-auto p-2 sm:p-3 font-mono text-xs sm:text-sm
                   bg-[var(--bg-secondary)]/20"
        >
          {loading && logs.length === 0 && (
            <div className="text-center text-[var(--text-muted)] py-8">
              {t('logs.loading', '加载中...')}
            </div>
          )}
          {!loading && logs.length === 0 && (
            <div className="text-center text-[var(--text-muted)] py-8">
              {errorMsg
                ? `${t('logs.empty', '暂无日志')} (${errorMsg})`
                : t('logs.empty', '暂无日志')}
            </div>
          )}
          {logs.map(entry => (
            <LogRow
              key={entry.id}
              entry={entry}
              expanded={expanded.has(entry.id)}
              onToggleExpand={toggleExpanded}
            />
          ))}
        </div>

        {/* Follow latest float button */}
        {!autoFollow && logs.length > 0 && !paused && (
          <button
            onClick={() => {
              setAutoFollow(true);
              scrollToBottom('smooth');
            }}
            className="absolute bottom-20 right-6 px-3 py-1.5 rounded-full
                     bg-[var(--color-primary)] text-white text-xs shadow-lg
                     hover:bg-[var(--color-secondary)] transition-colors
                     flex items-center gap-1"
          >
            <ChevronDown className="w-3 h-3" />
            <span>{t('logs.actions.followLatest', '继续跟踪')}</span>
          </button>
        )}

        {/* Footer */}
        <div className="flex items-center justify-between p-3 border-t border-[var(--border-color)]
                        bg-[var(--bg-secondary)]/30 text-xs">
          <div className="flex items-center gap-3 text-[var(--text-secondary)]">
            <span>
              {t('logs.count', { count: logs.length })}
            </span>
            {paused && (
              <span className="flex items-center gap-1 text-amber-400">
                <Pause className="w-3 h-3" />
                <span>{t('logs.pausedHint', '已暂停，实时日志暂不追加')}</span>
              </span>
            )}
            {latestId !== null && (
              <span className="text-[var(--text-muted)]">#{latestId}</span>
            )}
          </div>
          <button
            onClick={onClose}
            className="px-3 py-1.5 rounded-lg bg-[var(--bg-card)]
                     border border-[var(--border-color)] text-[var(--text-primary)]
                     hover:border-[var(--color-primary)] text-xs"
          >
            {t('logs.actions.close', '关闭')}
          </button>
        </div>

        {/* Clear confirm overlay */}
        {confirmingClear && (
          <div className={`absolute inset-0 z-[70] bg-black/60 ${isCoarsePointer() ? '' : 'backdrop-blur-sm'}
                          flex items-center justify-center p-4`}>
            <div className="bg-[var(--bg-card)] border border-[var(--border-color)]
                            rounded-xl p-6 max-w-sm w-full shadow-2xl">
              <h3 className="text-base font-bold text-[var(--text-primary)] mb-2">
                {t('logs.confirm.clear', '确认清空')}
              </h3>
              <p className="text-sm text-[var(--text-secondary)] mb-4">
                {t('logs.confirm.clearMessage', '确定清空所有日志？此操作不可撤销。')}
              </p>
              <div className="flex gap-2">
                <button
                  onClick={() => setConfirmingClear(false)}
                  className="flex-1 px-3 py-2 rounded-lg border border-[var(--border-color)]
                           text-[var(--text-primary)] hover:bg-[var(--bg-overlay)] text-sm"
                >
                  {t('logs.cancel', '取消')}
                </button>
                <button
                  onClick={handleClear}
                  className="flex-1 px-3 py-2 rounded-lg bg-red-500 text-white
                           hover:bg-red-600 text-sm font-medium"
                >
                  {t('logs.actions.clear', '清空')}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </ModalBackdrop>
  );
});

// ===== 子组件与辅助 =====

function Chip({
  active, onClick, children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  const activeClass = active
    ? 'bg-gradient-to-r from-[var(--color-primary)] to-[var(--color-secondary)] text-white shadow-[var(--glow-primary)]'
    : 'bg-[var(--bg-secondary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]';
  return (
    <button
      onClick={onClick}
      className={`px-2.5 py-1 text-xs rounded-md transition-all flex items-center ${activeClass}`}
    >
      {children}
    </button>
  );
}

function IconAction({
  title, onClick, icon, danger, active,
}: {
  title: string;
  onClick: () => void;
  icon: React.ReactNode;
  danger?: boolean;
  active?: boolean;
}) {
  const baseClass = 'p-2 rounded-lg transition-colors';
  const variantClass = danger
    ? 'text-[var(--text-secondary)] hover:text-red-400 hover:bg-[var(--bg-overlay)]'
    : active
      ? 'text-[var(--color-primary)] bg-[var(--bg-overlay)]'
      : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-overlay)]';
  return (
    <button onClick={onClick} className={`${baseClass} ${variantClass}`} title={title}>
      {icon}
    </button>
  );
}

const LogRow = memo(function LogRow({
  entry, expanded, onToggleExpand,
}: {
  entry: LogEntry;
  expanded: boolean;
  onToggleExpand: (id: number) => void;
}) {
  const time = new Date(entry.timestamp * 1000).toLocaleTimeString();
  const handleClick = useCallback(() => onToggleExpand(entry.id), [onToggleExpand, entry.id]);
  return (
    <div className="border-b border-[var(--border-color)]/30 px-2 py-1.5
                    hover:bg-[var(--bg-overlay)]/40 transition-colors">
      <button
        onClick={handleClick}
        className="w-full flex items-start gap-2 text-left"
      >
        <span className="text-[var(--text-muted)] shrink-0">{time}</span>
        <LevelBadge level={entry.level} />
        <CategoryBadge category={entry.category} />
        {entry.source && (
          <span className="text-[var(--text-muted)] shrink-0 max-w-[120px] truncate">
            [{entry.source}]
          </span>
        )}
        <span className="flex-1 min-w-0 break-words text-[var(--text-primary)]">
          {entry.message}
        </span>
        {entry.details && (
          <span className="text-[var(--text-muted)]">
            {expanded ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
          </span>
        )}
      </button>
      {expanded && entry.details && (
        <pre className="mt-1 ml-1 p-2 bg-black/20 rounded text-xs whitespace-pre-wrap
                       break-words text-[var(--text-secondary)] overflow-x-auto">
          {tryFormatJson(entry.details)}
        </pre>
      )}
    </div>
  );
});

function LevelBadge({ level }: { level: LogLevel }) {
  return (
    <span className={`shrink-0 inline-flex items-center gap-0.5 px-1.5 py-0.5
                     rounded text-[10px] font-bold uppercase
                     ${levelBg(level)}`}
    >
      {levelIcon(level)}
      <span>{level}</span>
    </span>
  );
}

function CategoryBadge({ category }: { category: LogCategory }) {
  return (
    <span className="shrink-0 px-1.5 py-0.5 rounded text-[10px] font-medium
                     bg-[var(--bg-overlay)] text-[var(--text-secondary)]
                     border border-[var(--border-color)]/50">
      {category}
    </span>
  );
}

// ===== 辅助函数 =====

function levelIcon(lv: LogLevel) {
  switch (lv) {
    case 'debug': return <Bug className="w-3 h-3" />;
    case 'info': return <Info className="w-3 h-3" />;
    case 'warn': return <AlertTriangle className="w-3 h-3" />;
    case 'error': return <AlertCircle className="w-3 h-3" />;
  }
}

function levelBg(lv: LogLevel): string {
  switch (lv) {
    case 'debug': return 'bg-gray-500/20 text-gray-300';
    case 'info': return 'bg-blue-500/20 text-blue-300';
    case 'warn': return 'bg-amber-500/20 text-amber-300';
    case 'error': return 'bg-red-500/20 text-red-300';
  }
}

// 直接使用本地字典，i18n 文件里的 keys 已经声明，但为了避免 t() 多参数签名问题，直接 lookup
const LEVEL_LABELS: Record<LogLevel, string> = {
  debug: '调试',
  info: '信息',
  warn: '警告',
  error: '错误',
};

const TIME_RANGE_LABELS: Record<TimeRangeKey, string> = {
  '1h': '1 小时',
  '24h': '24 小时',
  '7d': '7 天',
  all: '全部',
};

function levelLabel(lv: LogLevel): string {
  return LEVEL_LABELS[lv];
}

function timeLabel(key: TimeRangeKey): string {
  return TIME_RANGE_LABELS[key];
}

function tryFormatJson(s: string): string {
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}

/**
 * 本地过滤（实时事件用）。
 * 复刻后端 WHERE 条件，避免把被过滤掉的条目加入列表
 */
function matchesFiltersLocal(
  entry: LogEntry,
  keyword: string,
  levels: Set<LogLevel>,
  categories: Set<LogCategory>,
  since: number | undefined,
): boolean {
  if (since !== undefined && entry.timestamp < since) return false;
  if (levels.size > 0 && !levels.has(entry.level)) return false;
  if (categories.size > 0 && !categories.has(entry.category)) return false;
  if (keyword) {
    const k = keyword.toLowerCase();
    const inMsg = entry.message.toLowerCase().includes(k);
    const inDet = entry.details?.toLowerCase().includes(k) ?? false;
    if (!inMsg && !inDet) return false;
  }
  return true;
}
