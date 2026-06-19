import { useState, useEffect } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Area, AreaChart } from 'recharts';
import { TrendingUp, Clock, ChevronDown } from 'lucide-react';
import type { UsageStatus } from '../types';

interface Props {
  providerId: string;
  providerName: string;
}

type TimeRange = '1h' | '6h' | '24h' | '7d';

const TIME_RANGES: { key: TimeRange; label: string; seconds: number }[] = [
  { key: '1h', label: '1小时', seconds: 3600 },
  { key: '6h', label: '6小时', seconds: 21600 },
  { key: '24h', label: '24小时', seconds: 86400 },
  { key: '7d', label: '7天', seconds: 604800 },
];

export function HistoryChart({ providerId, providerName }: Props) {
  const [data, setData] = useState<UsageStatus[]>([]);
  const [range, setRange] = useState<TimeRange>('24h');
  const [rangeOpen, setRangeOpen] = useState(false);

  useEffect(() => {
    loadHistory();
  }, [providerId, range]);

  const loadHistory = async () => {
    try {
      const rangeConfig = TIME_RANGES.find(r => r.key === range)!;
      const since = Math.floor(Date.now() / 1000) - rangeConfig.seconds;
      const history = await invoke<UsageStatus[]>('get_usage_history', {
        providerId,
        limit: 200,
        since,
      });
      // 反转以按时间正序显示
      setData(history.reverse());
    } catch (err) {
      console.error('Failed to load history:', err);
    }
  };

  const formatTime = (ts: number) => {
    const d = new Date(ts * 1000);
    if (range === '1h' || range === '6h') {
      return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    }
    return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' }) + 
           ' ' + d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  };

  const formatBalance = (value: number) => `$${value.toFixed(2)}`;

  const currentRange = TIME_RANGES.find(r => r.key === range)!;

  return (
    <div className="bg-[var(--bg-card)] rounded-xl border border-[var(--border-color)] 
                    shadow-[var(--shadow-card)] p-5">
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-2">
          <TrendingUp className="w-5 h-5 text-[var(--color-primary)]" />
          <h3 className="font-semibold text-[var(--text-primary)]">
            {providerName} · 余额趋势
          </h3>
        </div>
        
        {/* Time Range Selector */}
        <div className="relative">
          <button
            onClick={() => setRangeOpen(!rangeOpen)}
            className="flex items-center gap-1 px-3 py-1.5 text-sm rounded-lg
                       bg-[var(--bg-secondary)] text-[var(--text-secondary)]
                       hover:text-[var(--text-primary)] transition-colors"
          >
            <Clock className="w-3.5 h-3.5" />
            {currentRange.label}
            <ChevronDown className={`w-3.5 h-3.5 transition-transform ${rangeOpen ? 'rotate-180' : ''}`} />
          </button>
          
          {rangeOpen && (
            <div className="absolute right-0 mt-1 w-28 rounded-lg
                            bg-[var(--bg-card)] border border-[var(--border-color)]
                            shadow-[var(--shadow-card)] overflow-hidden z-50">
              {TIME_RANGES.map(r => (
                <button
                  key={r.key}
                  onClick={() => { setRange(r.key); setRangeOpen(false); }}
                  className={`w-full px-3 py-2 text-sm text-left transition-colors
                            ${range === r.key 
                              ? 'bg-[var(--bg-overlay)] text-[var(--color-primary)]' 
                              : 'text-[var(--text-secondary)] hover:bg-[var(--bg-overlay)]'}`}
                >
                  {r.label}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Chart */}
      {data.length === 0 ? (
        <div className="h-48 flex items-center justify-center text-[var(--text-muted)]">
          <p>暂无历史数据，等待轮询采集...</p>
        </div>
      ) : (
        <div className="h-64">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={data} margin={{ top: 5, right: 5, left: 5, bottom: 5 }}>
              <defs>
                <linearGradient id="balanceGradient" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="var(--color-primary)" stopOpacity={0.3} />
                  <stop offset="95%" stopColor="var(--color-primary)" stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid 
                strokeDasharray="3 3" 
                stroke="var(--border-color)" 
                opacity={0.3} 
              />
              <XAxis 
                dataKey="timestamp" 
                tickFormatter={formatTime}
                stroke="var(--text-muted)"
                fontSize={11}
                tickLine={false}
              />
              <YAxis 
                tickFormatter={formatBalance}
                stroke="var(--text-muted)"
                fontSize={11}
                tickLine={false}
                axisLine={false}
                width={70}
              />
              <Tooltip
                contentStyle={{
                  backgroundColor: 'var(--bg-card)',
                  border: '1px solid var(--border-color)',
                  borderRadius: '8px',
                  color: 'var(--text-primary)',
                }}
                labelFormatter={(ts) => formatTime(ts as number)}
                formatter={(value: unknown) => [`$${(value as number).toFixed(4)}`, '余额']}
              />
              <Area 
                type="monotone" 
                dataKey="balance" 
                stroke="var(--color-primary)" 
                fill="url(#balanceGradient)"
                strokeWidth={2}
                dot={false}
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}