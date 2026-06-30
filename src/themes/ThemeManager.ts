export type ThemeId = 'cyberpunk' | 'wuxia' | 'guoman';

export interface ThemeConfig {
  id: ThemeId;
  name: string;
  icon: string;
  fontFamily: string;
  backgroundPattern: 'grid' | 'mountains' | 'clouds' | 'none';
}

export const THEMES: Record<ThemeId, ThemeConfig> = {
  cyberpunk: {
    id: 'cyberpunk',
    name: '赛博朋克',
    icon: '⚡',
    fontFamily: 'Orbitron, Rajdhani, monospace',
    backgroundPattern: 'grid',
  },
  wuxia: {
    id: 'wuxia',
    name: '武侠江湖',
    icon: '🗡️',
    fontFamily: 'Ma Shan Zheng, ZCOOL XiaoWei, serif',
    backgroundPattern: 'mountains',
  },
  guoman: {
    id: 'guoman',
    name: '国漫',
    icon: '✨',
    fontFamily: 'ZCOOL KuaiLe, Long Cang, sans-serif',
    backgroundPattern: 'clouds',
  },
};

export class ThemeManager {
  private current: ThemeId = 'cyberpunk';
  private storageKey = 'app_theme';

  constructor() {
    this.load();
    this.apply(this.current);
  }

  setTheme(themeId: ThemeId): void {
    this.current = themeId;
    this.apply(themeId);
    this.save();
  }

  getCurrent(): ThemeId {
    return this.current;
  }

  private apply(themeId: ThemeId): void {
    document.documentElement.setAttribute('data-theme', themeId);
    const theme = THEMES[themeId];
    document.documentElement.style.setProperty('--font-family', theme.fontFamily);
    document.body.style.fontFamily = theme.fontFamily;
  }

  private save(): void {
    localStorage.setItem(this.storageKey, this.current);
  }

  private load(): void {
    const saved = localStorage.getItem(this.storageKey) as ThemeId | null;
    if (saved && THEMES[saved]) {
      this.current = saved;
    }
  }
}

export const themeManager = new ThemeManager();
