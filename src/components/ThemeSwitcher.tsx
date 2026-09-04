import { useState } from 'react';
import { themeManager, THEMES, type ThemeId } from '../themes/ThemeManager';
import { ChevronDown, Palette } from 'lucide-react';

export function ThemeSwitcher() {
  const [open, setOpen] = useState(false);
  const [current, setCurrent] = useState<ThemeId>(themeManager.getCurrent());

  const handleSelect = (id: ThemeId) => {
    themeManager.setTheme(id);
    setCurrent(id);
    setOpen(false);
  };

  const currentTheme = THEMES[current];

  return (
    <div className="relative">
      <button
        onClick={() => setOpen(!open)}
        className="flex items-center gap-2 px-4 py-2 rounded-lg 
                   bg-[var(--bg-card)] border border-[var(--border-color)]
                   text-[var(--text-primary)] 
                   hover:shadow-[var(--glow-primary)] transition-all whitespace-nowrap"
      >
        <Palette className="w-4 h-4" />
        <span>{currentTheme.icon}</span>
        <span className="text-sm font-medium">{currentTheme.name}</span>
        <ChevronDown className={`w-4 h-4 transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>

      {open && (
        <div className="absolute right-0 mt-2 w-56 rounded-xl
                        bg-[var(--bg-card)] border border-[var(--border-color)]
                        shadow-xl z-50
                        overflow-hidden">
          {Object.values(THEMES).map(theme => (
            <button
              key={theme.id}
              onClick={() => handleSelect(theme.id)}
              className={`w-full flex items-center gap-3 px-4 py-3 
                         hover:bg-[var(--bg-overlay)] transition-colors
                         ${current === theme.id ? 'bg-[var(--bg-overlay)]' : ''}`}
            >
              <span className="text-2xl">{theme.icon}</span>
              <div className="flex-1 text-left">
                <div className="font-medium text-[var(--text-primary)]">{theme.name}</div>
                <div className="text-xs text-[var(--text-secondary)]">{theme.fontFamily.split(',')[0]}</div>
              </div>
              {current === theme.id && (
                <div className="w-2 h-2 rounded-full bg-[var(--color-primary)] 
                              shadow-[var(--glow-primary)]" />
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
