import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { X } from 'lucide-react';
import type { ProviderConfig } from '../api';
import { ModalBackdrop } from './ModalBackdrop';

const PROVIDER_PRESETS = [
  {
    type: 'openai',
    name: 'OpenAI',
    baseUrl: 'https://api.openai.com',
    endpoint: '/v1/usage',
    method: 'GET',
  },
  {
    type: 'anthropic',
    name: 'Anthropic',
    baseUrl: 'https://api.anthropic.com',
    endpoint: '/v1/organizations/self/subscription',
    method: 'GET',
  },
  {
    type: 'deepseek',
    name: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com',
    endpoint: '/v1/user/balance',
    method: 'GET',
  },
  {
    type: 'minimax_coding',
    name: 'MiniMax Token Plan (国内)',
    baseUrl: 'https://www.minimaxi.com',
    endpoint: '/v1/token_plan/remains',
    method: 'GET',
  },
  {
    type: 'minimax_token',
    name: 'MiniMax Coding Plan (海外)',
    baseUrl: 'https://api.minimax.io',
    endpoint: '/v1/api/openplatform/coding_plan/remains',
    method: 'GET',
  },
  {
    type: 'volcengine_coding',
    name: '火山方舟 Coding Plan',
    baseUrl: 'https://ark.cn-beijing.volces.com/api/coding/v3',
    endpoint: '/chat/completions',
    method: 'POST',
  },
  {
    type: 'volcengine_token',
    name: '火山方舟 Token Plan',
    baseUrl: 'https://ark.cn-beijing.volces.com/api/coding/v3',
    endpoint: '/chat/completions',
    method: 'POST',
  },
];

interface Props {
  isOpen: boolean;
  provider: ProviderConfig | null;
  onClose: () => void;
  onSave: (provider: ProviderConfig) => void;
}

export function ConfigModal({ isOpen, provider, onClose, onSave }: Props) {
  const { t } = useTranslation();
  const [form, setForm] = useState<Partial<ProviderConfig>>({});

  useEffect(() => {
    if (provider) {
      setForm(provider);
    } else {
      setForm({
        queryHeaders: { 'Content-Type': 'application/json' },
        queryParams: {},
        refreshInterval: 60,
        isEnabled: true,
      });
    }
  }, [provider]);

  const applyPreset = (preset: typeof PROVIDER_PRESETS[0]) => {
    setForm(f => {
      const newForm: Partial<ProviderConfig> = {
        ...f,
        provider: preset.type as ProviderConfig['provider'],
        baseUrl: preset.baseUrl,
        queryEndpoint: preset.endpoint,
        queryMethod: preset.method as 'GET' | 'POST',
      };

      // 火山方舟 Coding Plan / Token Plan 需要 body 发起一次最小请求以触发响应头
      if (preset.type === 'volcengine_coding' || preset.type === 'volcengine_token') {
        newForm.queryHeaders = {
          'Content-Type': 'application/json',
          'x-ark-customer': 'monitor-usage',
        };
        // 默认模型；用户可在下方"Model"输入框修改
        newForm.queryParams = { model: 'ark-code-latest' };
      } else {
        newForm.queryHeaders = { 'Content-Type': 'application/json' };
        newForm.queryParams = {};
      }
      return newForm;
    });
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    // 清理 queryParams：去掉空字符串字段
    const cleanQueryParams: Record<string, string> = {};
    if (form.queryParams) {
      for (const [k, v] of Object.entries(form.queryParams)) {
        if (typeof v === 'string' && v.trim() !== '') {
          cleanQueryParams[k] = v;
        }
      }
    }
    onSave({
      id: provider?.id || crypto.randomUUID(),
      name: form.name || '',
      provider: form.provider || 'custom',
      baseUrl: form.baseUrl || '',
      apiKey: form.apiKey || '',
      queryEndpoint: form.queryEndpoint || '',
      queryMethod: form.queryMethod || 'GET',
      queryHeaders: form.queryHeaders || {},
      queryParams: cleanQueryParams,
      refreshInterval: form.refreshInterval || 60,
      isEnabled: form.isEnabled ?? true,
      status: 'active',
    });
  };

  const isArkProvider = form.provider === 'volcengine_coding' || form.provider === 'volcengine_token';

  if (!isOpen) return null;

  return (
    <ModalBackdrop level="base">
      <div className="bg-[var(--bg-card)] rounded-xl w-full max-w-lg mx-4 max-h-[90vh] overflow-y-auto
                      border border-[var(--border-color)] shadow-[var(--shadow-card)]">
        <div className="flex items-center justify-between p-4 border-b border-[var(--border-color)]">
          <h2 className="text-lg font-semibold text-[var(--text-primary)]">
            {provider ? t('provider.edit') : t('provider.add')}
          </h2>
          <button onClick={onClose} className="p-1 hover:bg-[var(--bg-overlay)] rounded">
            <X className="w-5 h-5 text-[var(--text-primary)]" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-4 space-y-4">
          <div>
            <label className="block text-sm font-medium mb-2 text-[var(--text-secondary)]">{t('provider.presets')}</label>
              <div className="flex gap-2">
                {PROVIDER_PRESETS.map(preset => (
                  <button type="button" key={preset.type}
                    onClick={() => applyPreset(preset)}
                    className="px-3 py-1.5 text-sm bg-[var(--bg-secondary)] rounded-lg 
                             hover:bg-[var(--bg-overlay)] transition-colors
                             text-[var(--text-primary)]">
                    {preset.name}
                  </button>
                ))}
              </div>
            </div>

          <div>
            <label className="block text-sm font-medium mb-1 text-[var(--text-secondary)]">{t('provider.name')}</label>
            <input type="text" value={form.name || ''} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
              className="w-full px-3 py-2 rounded-lg border border-[var(--border-color)] 
                       bg-[var(--bg-secondary)] text-[var(--text-primary)]" />
          </div>

          <div>
            <label className="block text-sm font-medium mb-1 text-[var(--text-secondary)]">{t('provider.apiKey')}</label>
            <input type="password" value={form.apiKey || ''} onChange={e => setForm(f => ({ ...f, apiKey: e.target.value }))}
              className="w-full px-3 py-2 rounded-lg border border-[var(--border-color)] 
                       bg-[var(--bg-secondary)] text-[var(--text-primary)]" />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-sm font-medium mb-1 text-[var(--text-secondary)]">{t('provider.baseUrl')}</label>
                <input type="text" value={form.baseUrl || ''} onChange={e => setForm(f => ({ ...f, baseUrl: e.target.value }))}
                  className="w-full px-3 py-2 rounded-lg border border-[var(--border-color)] 
                           bg-[var(--bg-secondary)] text-[var(--text-primary)]" />
              </div>
              <div>
                <label className="block text-sm font-medium mb-1 text-[var(--text-secondary)]">{t('provider.queryEndpoint')}</label>
                <input type="text" value={form.queryEndpoint || ''} onChange={e => setForm(f => ({ ...f, queryEndpoint: e.target.value }))}
                  className="w-full px-3 py-2 rounded-lg border border-[var(--border-color)] 
                           bg-[var(--bg-secondary)] text-[var(--text-primary)]" />
              </div>
            </div>

          <div>
            <label className="block text-sm font-medium mb-1 text-[var(--text-secondary)]">{t('provider.method')}</label>
              <select value={form.queryMethod || 'GET'} onChange={e => setForm(f => ({ ...f, queryMethod: e.target.value as 'GET' | 'POST' }))}
                className="w-full px-3 py-2 rounded-lg border border-[var(--border-color)] 
                         bg-[var(--bg-secondary)] text-[var(--text-primary)]">
                <option value="GET">GET</option>
                <option value="POST">POST</option>
              </select>
            </div>

          {/* 火山方舟：新增或编辑时均可手动调整 model */}
          {isArkProvider && (
            <div>
              <label className="block text-sm font-medium mb-1 text-[var(--text-secondary)]">
                Model
                <span className="ml-2 text-xs text-[var(--text-muted)]">
                  留空使用默认 ark-code-latest
                </span>
              </label>
              <input
                type="text"
                value={(form.queryParams?.model as string) || ''}
                onChange={e => setForm(f => ({
                  ...f,
                  queryParams: { ...(f.queryParams || {}), model: e.target.value }
                }))}
                placeholder="ark-code-latest"
                className="w-full px-3 py-2 rounded-lg border border-[var(--border-color)] 
                         bg-[var(--bg-secondary)] text-[var(--text-primary)]
                         placeholder:text-[var(--text-muted)]"
              />
            </div>
          )}

          <div>
            <label className="block text-sm font-medium mb-1 text-[var(--text-secondary)]">{t('provider.refreshInterval')}</label>
            <input type="number" value={form.refreshInterval || 60} onChange={e => setForm(f => ({ ...f, refreshInterval: parseInt(e.target.value) }))}
              className="w-full px-3 py-2 rounded-lg border border-[var(--border-color)] 
                       bg-[var(--bg-secondary)] text-[var(--text-primary)]" />
          </div>

          <div className="flex gap-3 pt-2">
            <button type="button" onClick={onClose}
              className="flex-1 px-4 py-2 border border-[var(--border-color)] rounded-lg 
                       hover:bg-[var(--bg-overlay)] text-[var(--text-primary)]">
              {t('provider.cancel')}
            </button>
            <button type="submit"
              className="flex-1 px-4 py-2 bg-gradient-to-r from-[var(--color-primary)] to-[var(--color-secondary)]
                       text-white rounded-lg hover:shadow-[var(--glow-primary)] transition-all">
              {t('provider.save')}
            </button>
          </div>
        </form>
      </div>
    </ModalBackdrop>
  );
}
