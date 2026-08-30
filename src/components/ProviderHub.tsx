import { memo, useCallback, useEffect, useMemo, useState } from 'react';
import { Search, Check, Plus, ExternalLink, Power, PowerOff, X, Key, Sparkles, Settings } from 'lucide-react';
import { api, type ProviderPreset, type ProviderConfig } from '../api';
import { ModalBackdrop } from './ModalBackdrop';

interface Props {
  myProviders: ProviderConfig[];
  onProvidersUpdated: (providers: ProviderConfig[]) => void;
  onClose: () => void;
  /** 打开"自定义 Provider"配置弹窗（从供应商中心统一发起） */
  onAddCustom: () => void;
  /** 完整编辑某个已添加的 Provider（含全部字段） */
  onEdit: (p: ProviderConfig) => void;
}

const CATEGORY_INFO: Record<string, { label: string; icon: string; color: string }> = {
  domestic: { label: '国内服务', icon: '🇨🇳', color: 'text-red-400' },
  overseas: { label: '海外服务', icon: '🌍', color: 'text-blue-400' },
  coding_plan: { label: '订阅套餐', icon: '⚡', color: 'text-amber-400' },
  custom: { label: '自定义', icon: '🛠️', color: 'text-purple-400' },
};

export const ProviderHub = memo(function ProviderHub({ myProviders, onProvidersUpdated, onClose, onAddCustom, onEdit }: Props) {
  const [catalog, setCatalog] = useState<ProviderPreset[]>([]);
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState<string>('all');
  const [selectedPreset, setSelectedPreset] = useState<ProviderPreset | null>(null);
  const [apiKey, setApiKey] = useState('');
  const [customName, setCustomName] = useState('');
  const [refreshInterval, setRefreshInterval] = useState<number>(60);
  const [showKey, setShowKey] = useState(false);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  useEffect(() => {
    loadCatalog();
  }, []);

  const loadCatalog = async () => {
    try {
      const list = await api.getProviderCatalog();
      setCatalog(list);
    } catch (err) {
      console.error(err);
    }
  };

  // 已启用的 Provider type 集合
  const myProviderTypes = useMemo(() => 
    new Set(myProviders.map(p => p.provider))
  , [myProviders]);

  // 过滤
  const filteredCatalog = useMemo(() => {
    return catalog.filter(p => {
      const matchSearch = search === '' || 
        p.name.toLowerCase().includes(search.toLowerCase()) ||
        p.description.toLowerCase().includes(search.toLowerCase());
      const matchCategory = category === 'all' || p.category === category;
      return matchSearch && matchCategory;
    });
  }, [catalog, search, category]);

  // 启用预置
  const handleEnable = useCallback(async () => {
    if (!selectedPreset || !apiKey.trim()) {
      setMessage({ type: 'error', text: '请填写 API Key' });
      return;
    }

    setLoading(true);
    try {
      const newProvider = await api.enablePreset(
        selectedPreset.id,
        apiKey.trim(),
        customName.trim() || undefined,
        refreshInterval
      );
      onProvidersUpdated([...myProviders, newProvider]);
      setMessage({ type: 'success', text: `${selectedPreset.name} 已添加` });

      // 关闭弹框并重置
      setTimeout(() => {
        setSelectedPreset(null);
        setApiKey('');
        setCustomName('');
        setMessage(null);
      }, 1200);
    } catch (err) {
      setMessage({ type: 'error', text: '启用失败: ' + err });
    } finally {
      setLoading(false);
    }
  }, [selectedPreset, apiKey, customName, refreshInterval, myProviders, onProvidersUpdated]);

  // 切换启用/禁用
  const handleToggle = useCallback(async (provider: ProviderConfig) => {
    try {
      await api.toggleProvider(provider.id, !provider.isEnabled);
      const updated = myProviders.map(p =>
        p.id === provider.id ? { ...p, isEnabled: !p.isEnabled } : p
      );
      onProvidersUpdated(updated);
    } catch (err) {
      console.error(err);
    }
  }, [myProviders, onProvidersUpdated]);

  const getCategoryInfo = (cat: string) => CATEGORY_INFO[cat] || CATEGORY_INFO.custom;

  return (
    <ModalBackdrop level="base">
      <div className="relative w-full max-w-5xl max-h-[90vh] overflow-hidden
                      bg-[var(--bg-card)] border border-[var(--border-color)]
                      rounded-2xl shadow-2xl flex flex-col">
        
        {/* Header */}
        <div className="flex items-center justify-between p-6 border-b border-[var(--border-color)]">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-gradient-to-br from-[var(--color-primary)]/20 to-[var(--color-secondary)]/20">
              <Sparkles className="w-5 h-5 text-[var(--color-primary)]" />
            </div>
            <div>
              <h2 className="text-base sm:text-lg font-bold text-[var(--text-primary)]">供应商中心</h2>
              <p className="text-xs sm:text-sm text-[var(--text-secondary)]">
                选择你需要的 AI 服务并配置 API Key
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 rounded-lg hover:bg-[var(--bg-overlay)] text-[var(--text-secondary)]"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Search & Filter */}
        <div className="p-4 border-b border-[var(--border-color)] space-y-3">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--text-muted)]" />
            <input
              type="text"
              placeholder="搜索供应商..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="w-full pl-10 pr-4 py-2.5 bg-[var(--bg-secondary)] 
                       border border-[var(--border-color)] rounded-lg
                       text-[var(--text-primary)] placeholder:text-[var(--text-muted)]
                       focus:outline-none focus:border-[var(--color-primary)]"
            />
          </div>
          <div className="flex gap-2 flex-wrap">
            {['all', 'domestic', 'overseas', 'coding_plan', 'custom'].map(cat => {
              const info = cat === 'all' 
                ? { label: '全部', icon: '📦', color: 'text-white' }
                : getCategoryInfo(cat);
              return (
                <button
                  key={cat}
                  onClick={() => setCategory(cat)}
                  className={`px-3 py-1.5 text-sm rounded-lg transition-all
                    ${category === cat
                      ? 'bg-gradient-to-r from-[var(--color-primary)] to-[var(--color-secondary)] text-white shadow-[var(--glow-primary)]'
                      : 'bg-[var(--bg-secondary)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]'
                    }`}
                >
                  {info.icon} {info.label}
                </button>
              );
            })}
          </div>
        </div>

        {/* Provider List */}
        <div className="flex-1 overflow-y-auto p-4">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            {filteredCatalog.map(preset => {
              const isAdded = myProviderTypes.has(preset.provider_type);
              const myInstance = myProviders.find(p => p.provider === preset.provider_type);
              const info = getCategoryInfo(preset.category);
              return (
                <div
                  key={preset.id}
                  className="p-4 rounded-xl border border-[var(--border-color)]
                           bg-[var(--bg-secondary)]/30
                           hover:border-[var(--color-primary)] hover:shadow-[var(--glow-primary)]
                           transition-all group"
                >
                  <div className="flex items-start justify-between mb-2">
                    <div className="flex items-center gap-2 min-w-0 flex-1">
                      <span className="text-xl sm:text-2xl shrink-0">{info.icon}</span>
                      <div className="min-w-0 flex-1">
                        <h3 className="font-semibold text-[var(--text-primary)] text-sm truncate">
                          {preset.name}
                        </h3>
                        <span className={`text-xs ${info.color}`}>{info.label}</span>
                      </div>
                    </div>
                    {isAdded && (
                      <div className="flex items-center gap-1 px-2 py-0.5 
                                    bg-[var(--color-success)]/20 text-[var(--color-success)]
                                    rounded text-xs">
                        <Check className="w-3 h-3" />
                        已添加
                      </div>
                    )}
                  </div>
                  
                  <p className="text-xs text-[var(--text-secondary)] mb-3 line-clamp-2 min-h-[2rem]">
                    {preset.description}
                  </p>
                  
                  <div className="flex items-center justify-between gap-2">
                    {preset.docs_url && (
                      <a
                        href={preset.docs_url}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-xs text-[var(--text-muted)] hover:text-[var(--color-primary)]
                                 flex items-center gap-1"
                        onClick={(e) => e.stopPropagation()}
                      >
                        <ExternalLink className="w-3 h-3" />
                        文档
                      </a>
                    )}
                    <div className="flex gap-1 ml-auto">
                      {isAdded && myInstance && (
                        <>
                          <button
                            onClick={() => onEdit(myInstance)}
                            className="p-1.5 rounded-md text-xs transition-colors
                                       bg-blue-500/20 text-blue-400 hover:bg-blue-500/30"
                            title="编辑"
                          >
                            <Settings className="w-3.5 h-3.5" />
                          </button>
                          <button
                            onClick={() => handleToggle(myInstance)}
                            className={`p-1.5 rounded-md text-xs transition-colors ${
                              myInstance.isEnabled
                                ? 'bg-green-500/20 text-green-400 hover:bg-green-500/30'
                                : 'bg-gray-500/20 text-gray-400 hover:bg-gray-500/30'
                            }`}
                            title={myInstance.isEnabled ? '点击禁用' : '点击启用'}
                          >
                            {myInstance.isEnabled ? <Power className="w-3.5 h-3.5" /> : <PowerOff className="w-3.5 h-3.5" />}
                          </button>
                        </>
                      )}
                      <button
                        onClick={() => {
                          setSelectedPreset(preset);
                          setApiKey('');
                          setCustomName('');
                          setRefreshInterval(preset.default_refresh_interval);
                          setMessage(null);
                        }}
                        className="px-3 py-1.5 text-xs rounded-md
                                 bg-gradient-to-r from-[var(--color-primary)] to-[var(--color-secondary)]
                                 text-white hover:shadow-[var(--glow-primary)] transition-all
                                 flex items-center gap-1"
                      >
                        {isAdded ? <><Key className="w-3 h-3" />更新 Key</> : <><Plus className="w-3 h-3" />添加</>}
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* Footer Stats */}
        <div className="flex items-center justify-between gap-3 p-4 border-t border-[var(--border-color)] 
                       bg-[var(--bg-secondary)]/30 text-sm">
          <div className="flex items-center gap-4 text-[var(--text-secondary)]">
            <span>共 {catalog.length} 个供应商</span>
            <span>·</span>
            <span>已添加 {myProviders.length}</span>
            <span>·</span>
            <span>启用 {myProviders.filter(p => p.isEnabled).length}</span>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={onAddCustom}
              className="flex items-center gap-1.5 px-4 py-1.5 rounded-lg
                       bg-gradient-to-r from-[var(--color-primary)] to-[var(--color-secondary)]
                       text-white font-medium hover:shadow-[var(--glow-primary)]
                       transition-all"
            >
              <Plus className="w-4 h-4" />
              自定义 Provider
            </button>
            <button
              onClick={onClose}
              className="px-4 py-1.5 rounded-lg bg-[var(--bg-card)] 
                       border border-[var(--border-color)] text-[var(--text-primary)]
                       hover:border-[var(--color-primary)]"
            >
              完成
            </button>
          </div>
        </div>
      </div>

      {/* 添加/更新 Key 弹框 */}
      {selectedPreset && (
        <ModalBackdrop level="nested">
          <div className="w-full max-w-md bg-[var(--bg-card)] border border-[var(--border-color)]
                        rounded-2xl shadow-2xl p-6 space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-base sm:text-lg font-bold text-[var(--text-primary)] truncate pr-2">
                配置 {selectedPreset.name}
              </h3>
              <button
                onClick={() => setSelectedPreset(null)}
                className="p-1 rounded hover:bg-[var(--bg-overlay)]"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div>
              <label className="block text-sm font-medium mb-1 text-[var(--text-secondary)]">
                API Key <span className="text-[var(--color-danger)]">*</span>
              </label>
              <div className="relative">
                <Key className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--text-muted)]" />
                <input
                  type={showKey ? 'text' : 'password'}
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                  placeholder="sk-..."
                  className="w-full pl-10 pr-20 py-2.5 bg-[var(--bg-secondary)]
                           border border-[var(--border-color)] rounded-lg
                           text-[var(--text-primary)] placeholder:text-[var(--text-muted)]
                           focus:outline-none focus:border-[var(--color-primary)]"
                />
                <button
                  type="button"
                  onClick={() => setShowKey(!showKey)}
                  className="absolute right-2 top-1/2 -translate-y-1/2 px-2 py-1 
                           text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)]"
                >
                  {showKey ? '隐藏' : '显示'}
                </button>
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium mb-1 text-[var(--text-secondary)]">
                自定义名称（可选）
              </label>
              <input
                type="text"
                value={customName}
                onChange={(e) => setCustomName(e.target.value)}
                placeholder={selectedPreset.name}
                className="w-full px-3 py-2.5 bg-[var(--bg-secondary)]
                         border border-[var(--border-color)] rounded-lg
                         text-[var(--text-primary)] placeholder:text-[var(--text-muted)]
                         focus:outline-none focus:border-[var(--color-primary)]"
              />
            </div>

            <div>
              <label className="block text-sm font-medium mb-1 text-[var(--text-secondary)]">
                刷新间隔（秒，最少 10）
              </label>
              <input
                type="number"
                min="10"
                value={refreshInterval}
                onChange={(e) => setRefreshInterval(Math.max(10, parseInt(e.target.value) || 60))}
                className="w-full px-3 py-2.5 bg-[var(--bg-secondary)]
                         border border-[var(--border-color)] rounded-lg
                         text-[var(--text-primary)]
                         focus:outline-none focus:border-[var(--color-primary)]"
              />
            </div>

            {message && (
              <div className={`p-2 text-sm rounded ${
                message.type === 'success'
                  ? 'bg-green-500/20 text-green-400'
                  : 'bg-red-500/20 text-red-400'
              }`}>
                {message.text}
              </div>
            )}

            <div className="flex gap-2 pt-2">
              <button
                onClick={() => setSelectedPreset(null)}
                className="flex-1 px-4 py-2.5 border border-[var(--border-color)] rounded-lg
                         text-[var(--text-primary)] hover:bg-[var(--bg-overlay)]"
              >
                取消
              </button>
              <button
                onClick={handleEnable}
                disabled={loading || !apiKey.trim()}
                className="flex-1 px-4 py-2.5 rounded-lg
                         bg-gradient-to-r from-[var(--color-primary)] to-[var(--color-secondary)]
                         text-white font-medium hover:shadow-[var(--glow-primary)]
                         disabled:opacity-50 disabled:cursor-not-allowed
                         transition-all"
              >
                {loading ? '保存中...' : '保存'}
              </button>
            </div>
          </div>
        </ModalBackdrop>
      )}
    </ModalBackdrop>
  );
});