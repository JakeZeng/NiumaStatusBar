import { useEffect, useState } from 'react';
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
import { Plus, Library, ScrollText } from 'lucide-react';
import { api, type ProviderConfig } from './api';

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

  useEffect(() => {
    loadProviders();
  }, []);

  useEffect(() => {
    const unlisten = listen('close-requested', () => {
      setCloseDialogOpen(true);
    });
    return () => {
      unlisten.then(fn => fn());
    };
  }, []);

  const loadProviders = async () => {
    try {
      const list = await api.getProviders();
      setProviders(list);
      if (!list.some(p => p.id === selectedProvider)) {
        setSelectedProvider(list[0]?.id ?? null);
      } else if (list.length > 0 && !selectedProvider) {
        setSelectedProvider(list[0].id);
      }
    } catch (err) {
      console.error(err);
    }
  };

  const handleSaveProvider = async (provider: ProviderConfig) => {
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
  };

  const handleProvidersUpdated = (newProviders: ProviderConfig[]) => {
    setProviders(newProviders);
    if (!newProviders.some(p => p.id === selectedProvider)) {
      setSelectedProvider(newProviders[0]?.id ?? null);
    } else if (newProviders.length > 0 && !selectedProvider) {
      setSelectedProvider(newProviders[0].id);
    }
  };

  const handleDelete = async (p: ProviderConfig) => {
    try {
      await api.deleteProvider(p.id);
      await loadProviders();
    } catch (err) {
      console.error(err);
    } finally {
      setDeletingTarget(null);
    }
  };

  const selectedProviderName = providers.find(p => p.id === selectedProvider)?.name || '';
  const selectedProviderType = providers.find(p => p.id === selectedProvider)?.provider;

  // 供 MobileMenu 调用的统一打开入口
  const openHub = () => setHubOpen(true);
  const openAddCustom = () => {
    setEditing(null);
    setModalOpen(true);
  };

  return (
    <div className="min-h-screen text-[var(--text-primary)]">
      <ThemedBackground theme={theme} />

      <header className="sticky top-0 z-40 backdrop-blur-md
                        bg-[var(--bg-card)]/80 border-b border-[var(--border-color)]
                        safe-top">
        <div className="mx-auto w-full max-w-screen-2xl px-3 sm:px-6 lg:px-10 py-2 sm:py-3
                        flex items-center justify-between gap-2">
          <h1 className="text-sm sm:text-xl md:text-2xl font-bold bg-gradient-to-r
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
              onClick={() => setLogViewerOpen(true)}
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
            <button
              onClick={openAddCustom}
              className="hidden sm:flex items-center gap-1.5 px-4 py-2 rounded-lg
                       bg-gradient-to-r from-[var(--color-primary)] to-[var(--color-secondary)]
                       text-white font-medium hover:shadow-[var(--glow-primary)] transition-all text-sm"
            >
              <Plus className="w-4 h-4" />
              <span>自定义</span>
            </button>
            <div className="hidden md:block">
              <LanguageSwitcher />
            </div>
            <div className="hidden md:block">
              <ThemeSwitcher />
            </div>
            {/* 移动端：收纳"供应商中心/自定义/导入导出/语言/主题/日志" */}
            <MobileMenu
              onOpenHub={openHub}
              onOpenAddCustom={openAddCustom}
              onOpenLogs={() => setLogViewerOpen(true)}
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
                onEdit={(p) => { setEditing(p); setModalOpen(true); }}
                onDelete={(p) => setDeletingTarget(p)}
              />
            ))}
          </div>
        )}
      </main>

      {/* 移动端悬浮按钮：仅在有 provider 时显示 "添加"，避免遮盖"打开供应商中心"引导 */}
      {providers.length > 0 && (
        <button
          onClick={openAddCustom}
          className="sm:hidden fixed bottom-5 right-5 z-30
                   w-14 h-14 rounded-full
                   bg-gradient-to-br from-[var(--color-primary)] to-[var(--color-secondary)]
                   text-white shadow-[var(--glow-primary)]
                   flex items-center justify-center
                   active:scale-95 transition-transform
                   safe-bottom"
          title="自定义 Provider"
          aria-label="添加自定义 Provider"
        >
          <Plus className="w-6 h-6" />
        </button>
      )}

      <ConfigModal
        isOpen={modalOpen}
        provider={editing}
        onClose={() => { setModalOpen(false); setEditing(null); }}
        onSave={handleSaveProvider}
      />

      {hubOpen && (
        <ProviderHub
          myProviders={providers}
          onProvidersUpdated={handleProvidersUpdated}
          onClose={() => setHubOpen(false)}
        />
      )}

      <LogViewer
        open={logViewerOpen}
        onClose={() => setLogViewerOpen(false)}
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
