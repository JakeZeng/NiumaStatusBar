import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { listen } from '@tauri-apps/api/event';
import { themeManager, type ThemeId } from './themes/ThemeManager';
import { ThemeSwitcher } from './components/ThemeSwitcher';
import { LanguageSwitcher } from './components/LanguageSwitcher';
import { ProviderCard } from './components/ProviderCard';
import { ConfigModal } from './components/ConfigModal';
import { ThemedBackground } from './components/ThemedBackground';
import { HistoryChart } from './components/HistoryChart';
import { ImportExport } from './components/ImportExport';
import { ProviderHub } from './components/ProviderHub';
import { MobileMenu } from './components/MobileMenu';
import { CloseConfirmDialog } from './components/CloseConfirmDialog';
import { ConfirmDialog } from './components/ConfirmDialog';
import { LogViewer } from './components/LogViewer';
import { Library, ScrollText } from 'lucide-react';
import { api, type ProviderConfig } from './api';
import { useStatusStore } from './store/statusStore';

export default function App() {
  const { t } = useTranslation();
  const [theme] = useState<ThemeId>(themeManager.getCurrent());
  const [providers, setProviders] = useState<ProviderConfig[]>([]);
  const [editing, setEditing] = useState<ProviderConfig | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [hubOpen, setHubOpen] = useState(false);
  const [logViewerOpen, setLogViewerOpen] = useState(false);
  const [selectedProvider, setSelectedProvider] = useState<string | null>(null);
  const [closeDialogOpen, setCloseDialogOpen] = useState(false);
  const [deletingTarget, setDeletingTarget] = useState<ProviderConfig | null>(null);

  const initStatusStore = useStatusStore((s) => s.init);
  const setStatusProviders = useStatusStore((s) => s.setProviders);

  // 启动：拉 providers + 注册全局 status-update 监听（一次性，App 卸载时清理）
  useEffect(() => {
    let cancelled = false;
    let dispose: (() => void) | undefined;

    (async () => {
      dispose = await initStatusStore();
      if (cancelled) dispose?.();
    })();

    loadProviders();

    return () => {
      cancelled = true;
      dispose?.();
    };
    // loadProviders 是稳定实现，不放依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // providers 变化时同步给 status store，回收已删除 provider 的状态缓存
  useEffect(() => {
    setStatusProviders(providers);
  }, [providers, setStatusProviders]);

  // 暴露给 Android 桌面组件点击跳转调用
  useEffect(() => {
    (window as unknown as {
      __NIUMA_SELECT_PROVIDER__?: (id: string) => void;
    }).__NIUMA_SELECT_PROVIDER__ = (id: string) => {
      setSelectedProvider(id);
    };
    return () => {
      delete (window as unknown as {
        __NIUMA_SELECT_PROVIDER__?: (id: string) => void;
      }).__NIUMA_SELECT_PROVIDER__;
    };
  }, []);

  // Android 桌面组件检测到 DB 陈旧/空时会 startActivity 唤起 MainActivity，
  // 唤起时带 EXTRA_FROM_WIDGET=true，MainActivity 通过 WebView 调这个函数。
  // 立即并行 fetch 所有 enabled provider 一次（绕过 poller 的 interval 节流），
  // 让 widget 在 5s 内读到新数据。
  useEffect(() => {
    (window as unknown as {
      __NIUMA_WIDGET_WAKE__?: () => Promise<void>;
    }).__NIUMA_WIDGET_WAKE__ = async () => {
      try {
        const list = await api.getProviders();
        const enabled = list.filter(p => p.isEnabled);
        if (enabled.length === 0) return;
        // 并行 fetch；单个失败不影响其他
        await Promise.allSettled(
          enabled.map(p => api.fetchProviderStatus(p.id))
        );
      } catch (err) {
        console.error('__NIUMA_WIDGET_WAKE__ failed', err);
      }
    };
    return () => {
      delete (window as unknown as {
        __NIUMA_WIDGET_WAKE__?: () => Promise<void>;
      }).__NIUMA_WIDGET_WAKE__;
    };
  }, []);

  useEffect(() => {
    const unlisten = listen('close-requested', () => {
      setCloseDialogOpen(true);
    });
    return () => {
      unlisten.then(fn => fn());
    };
  }, []);

  const loadProviders = useCallback(async () => {
    try {
      const list = await api.getProviders();
      setProviders(list);
      setSelectedProvider((cur) => {
        if (list.some(p => p.id === cur)) return cur;
        return list[0]?.id ?? null;
      });
    } catch (err) {
      console.error(err);
    }
  }, []);

  const handleSaveProvider = useCallback(async (provider: ProviderConfig) => {
    try {
      const existing = providers.find(p => p.id === provider.id);
      if (existing) {
        await api.updateProvider(provider.id, provider);
      } else {
        await api.addProvider(provider);
      }
      await loadProviders();
      setModalOpen(false);
      setEditing(null);
    } catch (err) {
      console.error(err);
    }
  }, [providers, loadProviders]);

  const handleProvidersUpdated = useCallback((newProviders: ProviderConfig[]) => {
    setProviders(newProviders);
    setSelectedProvider((cur) => {
      if (newProviders.some(p => p.id === cur)) return cur;
      return newProviders[0]?.id ?? null;
    });
  }, []);

  const handleDelete = useCallback(async (p: ProviderConfig) => {
    try {
      await api.deleteProvider(p.id);
      await loadProviders();
    } catch (err) {
      console.error(err);
    } finally {
      setDeletingTarget(null);
    }
  }, [loadProviders]);

  // 稳定引用：给 ProviderCard 用，避免破坏 memo
  const handleCardDelete = useCallback((p: ProviderConfig) => {
    setDeletingTarget(p);
  }, []);

  const openHub = useCallback(() => setHubOpen(true), []);
  const closeHub = useCallback(() => setHubOpen(false), []);
  const openLogs = useCallback(() => setLogViewerOpen(true), []);
  const closeLogs = useCallback(() => setLogViewerOpen(false), []);

  const openAddCustom = useCallback(() => {
    setEditing(null);
    setModalOpen(true);
    setHubOpen(false);
  }, []);

  const closeConfigModal = useCallback(() => {
    setModalOpen(false);
    setEditing(null);
  }, []);

  const handleEditFromHub = useCallback((p: ProviderConfig) => {
    setEditing(p);
    setModalOpen(true);
    setHubOpen(false);
  }, []);

  const selectedProviderObj = providers.find(p => p.id === selectedProvider);
  const selectedProviderName = selectedProviderObj?.name || '';
  const selectedProviderType = selectedProviderObj?.provider;
  const selectedRefreshInterval = selectedProviderObj?.refreshInterval;

  return (
    <div className="min-h-screen text-[var(--text-primary)]">
      <ThemedBackground theme={theme} />

      <header className="sticky top-0 z-40
                        bg-[var(--bg-card)]/95 border-b border-[var(--border-color)]
                        safe-top">
        <div className="mx-auto w-full max-w-screen-2xl px-3 sm:px-6 lg:px-10 py-2 sm:py-3
                        flex items-center justify-between gap-2">
          <h1 className="text-sm sm:text-base md:text-lg lg:text-xl font-bold bg-gradient-to-r
                        from-[var(--color-primary)] to-[var(--color-secondary)]
                        bg-clip-text text-transparent truncate min-w-0 flex-1">
            粮草用量-v{__APP_VERSION__}
          </h1>
          <div className="flex items-center gap-1 sm:gap-2 flex-shrink-0">
            {/* 桌面端：完整功能按钮 */}
            <div className="hidden sm:block">
              <ImportExport onProvidersUpdated={handleProvidersUpdated} />
            </div>
            <button
              onClick={openHub}
              className="hidden sm:flex items-center gap-1.5 px-3 py-2 rounded-lg
                       bg-[var(--bg-card)] border border-[var(--border-color)]
                       text-[var(--text-primary)]
                       hover:border-[var(--color-primary)] hover:shadow-[var(--glow-primary)]
                       transition-all text-sm"
              title="从预置目录添加"
            >
              <Library className="w-4 h-4" />
              <span>供应商中心</span>
            </button>
            <button
              onClick={openLogs}
              className="hidden sm:flex items-center gap-1.5 px-3 py-2 rounded-lg
                       bg-[var(--bg-card)] border border-[var(--border-color)]
                       text-[var(--text-primary)]
                       hover:border-[var(--color-primary)] hover:shadow-[var(--glow-primary)]
                       transition-all text-sm"
              title={t('logs.entry', '查看应用日志')}
            >
              <ScrollText className="w-4 h-4" />
              <span>{t('logs.title', '日志')}</span>
            </button>
            <div className="hidden md:block">
              <LanguageSwitcher />
            </div>
            <div className="hidden md:block">
              <ThemeSwitcher />
            </div>
            {/* 移动端：收纳"供应商中心/导入导出/语言/主题/日志" */}
            <MobileMenu
              onOpenHub={openHub}
              onOpenLogs={openLogs}
              onImportExport={() => { /* 由 hub 间接管理 */ }}
            />
          </div>
        </div>
      </header>

      <main className="mx-auto w-full max-w-screen-2xl px-3 sm:px-6 lg:px-10 py-4 sm:py-8 space-y-4 sm:space-y-8">
        {providers.length > 0 && (
          <div className="flex gap-1.5 sm:gap-2 overflow-x-auto pb-2 -mx-1 px-1
                          scrollbar-thin">
            {providers.map(provider => (
              <button
                key={provider.id}
                onClick={() => setSelectedProvider(provider.id)}
                className={`px-3 sm:px-4 py-1.5 sm:py-2 rounded-lg text-xs sm:text-sm font-medium transition-all whitespace-nowrap flex-shrink-0
                  ${selectedProvider === provider.id
                    ? 'bg-gradient-to-r from-[var(--color-primary)] to-[var(--color-secondary)] text-white shadow-[var(--glow-primary)]'
                    : 'bg-[var(--bg-card)] text-[var(--text-secondary)] border border-[var(--border-color)] hover:border-[var(--color-primary)]'
                  }`}
              >
                {provider.name}
              </button>
            ))}
          </div>
        )}

        {selectedProvider && (
          <HistoryChart
            providerId={selectedProvider}
            providerName={selectedProviderName}
            providerType={selectedProviderType}
            refreshInterval={selectedRefreshInterval}
          />
        )}

        {providers.length === 0 ? (
          <div className="text-center py-12 sm:py-20 px-4">
            <div className="text-5xl sm:text-6xl mb-4 opacity-50">📡</div>
            <p className="text-[var(--text-secondary)] text-base sm:text-lg">{t('app.noProviders')}</p>
            <p className="text-[var(--text-muted)] mt-2 text-sm">
              点击"供应商中心"选择预设并填入 API Key 开始配置
            </p>
            <button
              onClick={openHub}
              className="mt-6 inline-flex items-center gap-2 px-5 sm:px-6 py-2.5 sm:py-3 rounded-lg
                       bg-gradient-to-r from-[var(--color-primary)] to-[var(--color-secondary)]
                       text-white font-medium hover:shadow-[var(--glow-primary)] transition-all text-sm sm:text-base"
            >
              <Library className="w-4 h-4" />
              打开供应商中心
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4 gap-3 sm:gap-4 lg:gap-5">
            {providers.map(provider => (
              <ProviderCard
                key={provider.id}
                provider={provider}
                onDelete={handleCardDelete}
              />
            ))}
          </div>
        )}
      </main>

      <ConfigModal
        isOpen={modalOpen}
        provider={editing}
        onClose={closeConfigModal}
        onSave={handleSaveProvider}
      />

      {hubOpen && (
        <ProviderHub
          myProviders={providers}
          onProvidersUpdated={handleProvidersUpdated}
          onClose={closeHub}
          onAddCustom={openAddCustom}
          onEdit={handleEditFromHub}
        />
      )}

      <LogViewer
        open={logViewerOpen}
        onClose={closeLogs}
      />

      <CloseConfirmDialog
        open={closeDialogOpen}
        onDismiss={() => setCloseDialogOpen(false)}
      />

      <ConfirmDialog
        open={!!deletingTarget}
        title={deletingTarget
          ? t('provider.deleteConfirmTitle', { name: deletingTarget.name })
          : ''}
        message={t('provider.deleteConfirmMessage')}
        confirmLabel={t('provider.deleteConfirm')}
        cancelLabel={t('provider.cancel')}
        danger
        onConfirm={() => deletingTarget ? handleDelete(deletingTarget) : Promise.resolve()}
        onDismiss={() => setDeletingTarget(null)}
      />
    </div>
  );
}
