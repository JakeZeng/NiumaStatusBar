import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { themeManager, type ThemeId } from './themes/ThemeManager';
import { ThemeSwitcher } from './components/ThemeSwitcher';
import { LanguageSwitcher } from './components/LanguageSwitcher';
import { ProviderCard } from './components/ProviderCard';
import { ConfigModal } from './components/ConfigModal';
import { ThemedBackground } from './components/ThemedBackground';
import { HistoryChart } from './components/HistoryChart';
import { ImportExport } from './components/ImportExport';
import { invoke } from '@tauri-apps/api/core';
import { Plus } from 'lucide-react';
import type { ProviderConfig } from './types';

export default function App() {
  const { t } = useTranslation();
  const [theme] = useState<ThemeId>(themeManager.getCurrent());
  const [providers, setProviders] = useState<ProviderConfig[]>([]);
  const [editing, setEditing] = useState<ProviderConfig | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [selectedProvider, setSelectedProvider] = useState<string | null>(null);

  useEffect(() => {
    loadProviders();
  }, []);

  const loadProviders = async () => {
    try {
      const list = await invoke<ProviderConfig[]>('get_providers');
      setProviders(list);
      if (list.length > 0 && !selectedProvider) {
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
        await invoke('update_provider', { id: provider.id, provider });
      } else {
        await invoke('add_provider', { provider });
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
    if (newProviders.length > 0) {
      setSelectedProvider(newProviders[0].id);
    }
  };

  const selectedProviderName = providers.find(p => p.id === selectedProvider)?.name || '';

  return (
    <div className="min-h-screen text-[var(--text-primary)]">
      <ThemedBackground theme={theme} />

      <header className="sticky top-0 z-40 backdrop-blur-md 
                        bg-[var(--bg-card)]/80 border-b border-[var(--border-color)]">
        <div className="max-w-7xl mx-auto px-6 py-4 flex items-center justify-between">
          <h1 className="text-2xl font-bold bg-gradient-to-r 
                        from-[var(--color-primary)] to-[var(--color-secondary)]
                        bg-clip-text text-transparent">
            {t('app.title')}
          </h1>
          <div className="flex items-center gap-3">
            <ImportExport onProvidersUpdated={handleProvidersUpdated} />
            <button
              onClick={() => { setEditing(null); setModalOpen(true); }}
              className="flex items-center gap-2 px-4 py-2 rounded-lg
                       bg-gradient-to-r from-[var(--color-primary)] to-[var(--color-secondary)]
                       text-white font-medium hover:shadow-[var(--glow-primary)] transition-all"
            >
              <Plus className="w-4 h-4" />
              {t('app.addProvider')}
            </button>
            <LanguageSwitcher />
            <ThemeSwitcher />
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-6 py-8 space-y-8">
        {providers.length > 0 && (
          <div className="flex gap-2 overflow-x-auto pb-2">
            {providers.map(provider => (
              <button
                key={provider.id}
                onClick={() => setSelectedProvider(provider.id)}
                className={`px-4 py-2 rounded-lg text-sm font-medium transition-all whitespace-nowrap
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
          />
        )}

        {providers.length === 0 ? (
          <div className="text-center py-20">
            <div className="text-6xl mb-4 opacity-50">📡</div>
            <p className="text-[var(--text-secondary)] text-lg">{t('app.noProviders')}</p>
            <p className="text-[var(--text-muted)] mt-2">{t('app.noProvidersHint')}</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
            {providers.map(provider => (
              <ProviderCard
                key={provider.id}
                provider={provider}
                onEdit={(p) => { setEditing(p); setModalOpen(true); }}
              />
            ))}
          </div>
        )}
      </main>

      <ConfigModal
        isOpen={modalOpen}
        provider={editing}
        onClose={() => { setModalOpen(false); setEditing(null); }}
        onSave={handleSaveProvider}
      />
    </div>
  );
}