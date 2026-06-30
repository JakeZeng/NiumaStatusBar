import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { invoke } from '@tauri-apps/api/core';
import { Download, Upload, Check, AlertCircle } from 'lucide-react';
import type { ProviderConfig } from '../api';

interface Props {
  onProvidersUpdated: (providers: ProviderConfig[]) => void;
}

export function ImportExport({ onProvidersUpdated }: Props) {
  const { t } = useTranslation();
  const [importing, setImporting] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const handleExport = async () => {
    try {
      const json = await invoke<string>('export_config');
      const blob = new Blob([json], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `ai-model-monitor-config-${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(url);
      setMessage({ type: 'success', text: t('config.exportSuccess') });
    } catch (err) {
      setMessage({ type: 'error', text: t('config.exportError') + ': ' + err });
    }
  };

  const handleImport = async () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json';
    input.onchange = async (e) => {
      const file = (e.target as HTMLInputElement).files?.[0];
      if (!file) return;
      
      setImporting(true);
      setMessage(null);
      
      try {
        const text = await file.text();
        const providers = await invoke<ProviderConfig[]>('import_config', { json: text });
        onProvidersUpdated(providers);
        setMessage({ type: 'success', text: t('config.importSuccess', { count: providers.length }) });
      } catch (err) {
        setMessage({ type: 'error', text: t('config.importError') });
      } finally {
        setImporting(false);
      }
    };
    input.click();
  };

  return (
    <div className="flex items-center gap-2">
      <button
        onClick={handleExport}
        className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg
                   bg-[var(--bg-secondary)] text-[var(--text-secondary)]
                   hover:text-[var(--color-primary)] transition-colors"
        title={t('config.export')}
      >
        <Download className="w-3.5 h-3.5" />
        {t('config.export')}
      </button>
      
      <button
        onClick={handleImport}
        disabled={importing}
        className="flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg
                   bg-[var(--bg-secondary)] text-[var(--text-secondary)]
                   hover:text-[var(--color-primary)] transition-colors
                   disabled:opacity-50"
        title={t('config.import')}
      >
        <Upload className="w-3.5 h-3.5" />
        {t('config.import')}
      </button>

      {message && (
        <div className={`flex items-center gap-1.5 text-sm px-2 py-1 rounded-lg ${
          message.type === 'success' 
            ? 'text-[var(--color-success)] bg-[var(--color-success)]/10' 
            : 'text-[var(--color-danger)] bg-[var(--color-danger)]/10'
        }`}>
          {message.type === 'success' ? <Check className="w-3.5 h-3.5" /> : <AlertCircle className="w-3.5 h-3.5" />}
          {message.text}
        </div>
      )}
    </div>
  );
}