import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Globe, ChevronDown } from 'lucide-react';

const LANGUAGES = [
  { code: 'zh', name: '中文', flag: '🇨🇳' },
  { code: 'en', name: 'English', flag: '🇺🇸' },
];

export function LanguageSwitcher() {
  const { i18n } = useTranslation();
  const [open, setOpen] = useState(false);
  const current = LANGUAGES.find(l => l.code === i18n.language) || LANGUAGES[0];

  const handleSelect = (code: string) => {
    i18n.changeLanguage(code);
    localStorage.setItem('app_language', code);
    setOpen(false);
  };

  return (
    <div className="relative">
      <button
        onClick={() => setOpen(!open)}
        className="flex items-center gap-2 px-3 py-2 rounded-lg 
                   bg-[var(--bg-card)] border border-[var(--border-color)]
                   text-[var(--text-primary)] 
                   hover:shadow-[var(--glow-primary)] transition-all"
      >
        <Globe className="w-4 h-4" />
        <span>{current.flag}</span>
        <ChevronDown className={`w-3.5 h-3.5 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>

      {open && (
        <div className="absolute right-0 mt-2 w-36 rounded-xl 
                        bg-[var(--bg-card)] border border-[var(--border-color)]
                        shadow-[var(--shadow-card)] backdrop-blur-lg z-50
                        overflow-hidden">
          {LANGUAGES.map(lang => (
            <button
              key={lang.code}
              onClick={() => handleSelect(lang.code)}
              className={`w-full flex items-center gap-3 px-4 py-2.5 
                         hover:bg-[var(--bg-overlay)] transition-colors
                         ${current.code === lang.code ? 'bg-[var(--bg-overlay)]' : ''}`}
            >
              <span className="text-lg">{lang.flag}</span>
              <span className="text-sm text-[var(--text-primary)]">{lang.name}</span>
              {current.code === lang.code && (
                <div className="ml-auto w-2 h-2 rounded-full bg-[var(--color-primary)] 
                              shadow-[var(--glow-primary)]" />
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
