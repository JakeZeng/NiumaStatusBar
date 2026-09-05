import { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { MoreVertical, Globe, Palette, Download, X, Library, ScrollText, Settings } from 'lucide-react';
import { themeManager, THEMES, type ThemeId } from '../themes/ThemeManager';
import { ImportExport } from './ImportExport';

interface Props {
  onOpenHub?: () => void;
  onImportExport?: () => void;
  onOpenLogs?: () => void;
  onOpenSettings?: () => void;
}

/**
 * 移动端右上角"更多"菜单
 * 收纳：供应商中心、自定义、导入导出、日志、语言、主题
 * 仅在 < sm 屏幕下显示
 */
export function MobileMenu({ onOpenHub, onImportExport, onOpenLogs, onOpenSettings }: Props) {
  const { t, i18n } = useTranslation();
  const [open, setOpen] = useState(false);
  const [subPage, setSubPage] = useState<'main' | 'theme' | 'language' | 'io'>('main');
  const [currentTheme, setCurrentTheme] = useState<ThemeId>(themeManager.getCurrent());
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
        setSubPage('main');
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [open]);

  const close = () => {
    setOpen(false);
    setSubPage('main');
  };

  const handleLanguageSelect = (code: string) => {
    i18n.changeLanguage(code);
    localStorage.setItem('app_language', code);
    close();
  };

  const handleThemeSelect = (id: ThemeId) => {
    themeManager.setTheme(id);
    setCurrentTheme(id);
    close();
  };

  const handleHub = () => {
    onOpenHub?.();
    close();
  };

  const handleLogs = () => {
    onOpenLogs?.();
    close();
  };

  const handleSettings = () => {
    onOpenSettings?.();
    close();
  };

  const currentLang = i18n.language || 'zh';

  return (
    <div className="sm:hidden relative" ref={ref}>
      <button
        onClick={() => setOpen(!open)}
        className="flex items-center justify-center w-9 h-9 rounded-lg
                 bg-[var(--bg-card)] border border-[var(--border-color)]
                 text-[var(--text-primary)]
                 hover:border-[var(--color-primary)] active:scale-95 transition-all"
        title="更多"
        aria-label="更多"
      >
        {open ? <X className="w-4 h-4" /> : <MoreVertical className="w-4 h-4" />}
      </button>

      {open && (
        <div className="absolute right-0 mt-2 w-64 rounded-xl
                        bg-[var(--bg-card)] border border-[var(--border-color)]
                        shadow-xl z-50 overflow-hidden
                        max-h-[80vh] overflow-y-auto">
          {subPage === 'main' && (
            <div className="py-1">
              <button
                onClick={handleHub}
                className="w-full flex items-center gap-3 px-4 py-3
                         hover:bg-[var(--bg-overlay)] active:bg-[var(--bg-overlay)]
                         transition-colors text-left
                         text-[var(--text-primary)]"
              >
                <Library className="w-4 h-4 text-[var(--color-primary)]" />
                <span className="text-sm flex-1">供应商中心</span>
                <span className="text-xs text-[var(--text-muted)]">含自定义</span>
              </button>
              <button
                onClick={handleLogs}
                className="w-full flex items-center gap-3 px-4 py-3
                         hover:bg-[var(--bg-overlay)] active:bg-[var(--bg-overlay)]
                         transition-colors text-left
                         text-[var(--text-primary)]"
              >
                <ScrollText className="w-4 h-4 text-[var(--color-primary)]" />
                <span className="text-sm flex-1">日志</span>
              </button>
              <button
                onClick={handleSettings}
                className="w-full flex items-center gap-3 px-4 py-3
                         hover:bg-[var(--bg-overlay)] active:bg-[var(--bg-overlay)]
                         transition-colors text-left
                         text-[var(--text-primary)]"
              >
                <Settings className="w-4 h-4 text-[var(--color-primary)]" />
                <span className="text-sm flex-1">{t('settings.gear', '设置')}</span>
              </button>
              <div className="my-1 border-t border-[var(--border-color)]/50" />
              <button
                onClick={() => setSubPage('language')}
                className="w-full flex items-center gap-3 px-4 py-3
                         hover:bg-[var(--bg-overlay)] active:bg-[var(--bg-overlay)]
                         transition-colors text-left
                         text-[var(--text-primary)]"
              >
                <Globe className="w-4 h-4 text-[var(--color-primary)]" />
                <span className="text-sm flex-1">语言</span>
                <span className="text-xs text-[var(--text-muted)]">
                  {currentLang === 'zh' ? '中文' : 'English'}
                </span>
              </button>
              <button
                onClick={() => setSubPage('theme')}
                className="w-full flex items-center gap-3 px-4 py-3
                         hover:bg-[var(--bg-overlay)] active:bg-[var(--bg-overlay)]
                         transition-colors text-left
                         text-[var(--text-primary)]"
              >
                <Palette className="w-4 h-4 text-[var(--color-primary)]" />
                <span className="text-sm flex-1">主题</span>
                <span className="text-xs text-[var(--text-muted)]">
                  {THEMES[currentTheme].icon} {THEMES[currentTheme].name}
                </span>
              </button>
              <button
                onClick={() => setSubPage('io')}
                className="w-full flex items-center gap-3 px-4 py-3
                         hover:bg-[var(--bg-overlay)] active:bg-[var(--bg-overlay)]
                         transition-colors text-left
                         text-[var(--text-primary)]"
              >
                <Download className="w-4 h-4 text-[var(--color-primary)]" />
                <span className="text-sm flex-1">导入 / 导出</span>
              </button>
            </div>
          )}

          {subPage === 'language' && (
            <SubPage title="语言" onBack={() => setSubPage('main')}>
              <button
                onClick={() => handleLanguageSelect('zh')}
                className={`w-full flex items-center gap-3 px-4 py-3
                          hover:bg-[var(--bg-overlay)] active:bg-[var(--bg-overlay)]
                          transition-colors text-left
                          ${currentLang === 'zh' ? 'bg-[var(--bg-overlay)]' : ''}
                          text-[var(--text-primary)]`}
              >
                <span className="text-xl">🇨🇳</span>
                <span className="text-sm flex-1">中文</span>
                {currentLang === 'zh' && (
                  <div className="w-2 h-2 rounded-full bg-[var(--color-primary)]" />
                )}
              </button>
              <button
                onClick={() => handleLanguageSelect('en')}
                className={`w-full flex items-center gap-3 px-4 py-3
                          hover:bg-[var(--bg-overlay)] active:bg-[var(--bg-overlay)]
                          transition-colors text-left
                          ${currentLang === 'en' ? 'bg-[var(--bg-overlay)]' : ''}
                          text-[var(--text-primary)]`}
              >
                <span className="text-xl">🇺🇸</span>
                <span className="text-sm flex-1">English</span>
                {currentLang === 'en' && (
                  <div className="w-2 h-2 rounded-full bg-[var(--color-primary)]" />
                )}
              </button>
            </SubPage>
          )}

          {subPage === 'theme' && (
            <SubPage title="主题" onBack={() => setSubPage('main')}>
              {Object.values(THEMES).map(theme => (
                <button
                  key={theme.id}
                  onClick={() => handleThemeSelect(theme.id)}
                  className={`w-full flex items-center gap-3 px-4 py-3
                            hover:bg-[var(--bg-overlay)] active:bg-[var(--bg-overlay)]
                            transition-colors text-left
                            ${currentTheme === theme.id ? 'bg-[var(--bg-overlay)]' : ''}
                            text-[var(--text-primary)]`}
                >
                  <span className="text-2xl">{theme.icon}</span>
                  <span className="text-sm flex-1">{theme.name}</span>
                  {currentTheme === theme.id && (
                    <div className="w-2 h-2 rounded-full bg-[var(--color-primary)]" />
                  )}
                </button>
              ))}
            </SubPage>
          )}

          {subPage === 'io' && (
            <SubPage title="导入 / 导出" onBack={() => setSubPage('main')}>
              <div className="p-2">
                <ImportExport
                  onProvidersUpdated={() => {
                    close();
                    onImportExport?.();
                  }}
                />
              </div>
            </SubPage>
          )}
        </div>
      )}
    </div>
  );
}

function SubPage({ title, onBack, children }: { title: string; onBack: () => void; children: React.ReactNode }) {
  return (
    <div>
      <div className="flex items-center gap-2 px-4 py-3 border-b border-[var(--border-color)]">
        <button
          onClick={onBack}
          className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] active:text-[var(--text-primary)] text-sm px-2 py-1 -ml-2 rounded"
        >
          ← 返回
        </button>
        <span className="text-sm font-medium text-[var(--text-primary)] ml-auto">{title}</span>
      </div>
      <div className="py-1">{children}</div>
    </div>
  );
}
