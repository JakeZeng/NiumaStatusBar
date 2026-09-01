import { api } from '../api';

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
    this.syncToBackend(this.current);
  }

  setTheme(themeId: ThemeId): void {
    this.current = themeId;
    this.apply(themeId);
    this.save();
    this.syncToBackend(themeId);
  }

  /**
   * 把主题 id 持久化到 Rust settings 表——Android 桌面组件进程不加载
   * WebView，读不到 localStorage，只能从 SQLite 拿主题给卡片配色。
   * 纯浏览器 dev 模式（无 Tauri IPC）下 invoke 会 reject，静默忽略。
   */
  private syncToBackend(themeId: ThemeId): void {
    api.setAppTheme(themeId).catch(() => {});
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
