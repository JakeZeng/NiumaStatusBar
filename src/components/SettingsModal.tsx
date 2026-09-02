import { memo, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { X, Minimize2, LogOut, HelpCircle, Eye, Power, Smartphone } from 'lucide-react';
import { api, WidgetStatus } from '../api';
import { ModalBackdrop } from './ModalBackdrop';
import { isDesktop } from '../lib/device';

interface Props {
  open: boolean;
  onDismiss: () => void;
}

type CloseAction = 'minimize_to_tray' | 'exit' | 'ask';

/**
 * 设置弹窗：
 * - 软件信息（关于）
 * - 后台托管：关闭行为 / 托盘图标可见 / 开机自启
 * - 桌面小组件自检（v0.1.47+）：显示 widget 进程写回的最近状态
 */
export const SettingsModal = memo(function SettingsModal({ open, onDismiss }: Props) {
  const { t } = useTranslation();
  const [closeAction, setCloseActionState] = useState<CloseAction>('ask');
  const [trayVisible, setTrayVisibleState] = useState(true);
  const [autostart, setAutostartState] = useState(false);
  const [widgetStatus, setWidgetStatus] = useState<WidgetStatus | null | undefined>(undefined);
  const [widgetNow, setWidgetNow] = useState(() => Date.now());

  useEffect(() => {
    if (!open) return;
    (async () => {
      try {
        const ca = await api.getCloseAction();
        if (ca === 'minimize_to_tray' || ca === 'exit') {
          setCloseActionState(ca);
        } else {
          setCloseActionState('ask');
        }
      } catch { /* ignore */ }
      try {
        setTrayVisibleState(await api.getTrayVisible());
      } catch { /* ignore */ }
      try {
        setAutostartState(await api.getAutostart());
      } catch { /* ignore */ }
      try {
        setWidgetStatus(await api.getWidgetStatus());
      } catch {
        setWidgetStatus(null);
      }
    })();
  }, [open]);

  // v0.1.47+: 每秒刷新一次「距 lastTickAt 多久」,让用户能直观看到 carousel 是不是真在 5s tick
  useEffect(() => {
    if (!open) return;
    const timer = window.setInterval(() => setWidgetNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [open]);

  const ageSec = (ts: number | undefined) => {
    if (!ts) return null;
    return Math.max(0, Math.floor((widgetNow - ts) / 1000));
  };
  const formatAgo = (sec: number) => {
    if (sec < 60) return `${sec} 秒前`;
    if (sec < 3600) return `${Math.floor(sec / 60)} 分 ${sec % 60} 秒前`;
    return `${Math.floor(sec / 3600)} 时 ${Math.floor((sec % 3600) / 60)} 分前`;
  };

  if (!open) return null;

  const changeCloseAction = async (a: CloseAction) => {
    setCloseActionState(a);
    try {
      if (a === 'ask') await api.resetCloseAction();
      else await api.setCloseAction(a);
    } catch (err) {
      console.error(err);
    }
  };

  const changeTray = async (v: boolean) => {
    setTrayVisibleState(v);
    try {
      await api.setTrayVisible(v);
    } catch (err) {
      console.error(err);
    }
  };

  const changeAutostart = async (v: boolean) => {
    setAutostartState(v);
    try {
      await api.setAutostart(v);
    } catch (err) {
      console.error(err);
    }
  };

  return (
    <ModalBackdrop onClick={onDismiss}>
      <div
        className="w-full max-w-md max-h-[85vh] overflow-y-auto rounded-2xl border
                   border-[var(--border-color)] bg-[var(--bg-card)] shadow-2xl
                   p-5 sm:p-6 space-y-6"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between gap-3">
          <h3 className="text-lg font-bold text-[var(--text-primary)]">
            {t('settings.title')}
          </h3>
          <button
            onClick={onDismiss}
            className="p-1 rounded hover:bg-[var(--bg-overlay)] text-[var(--text-secondary)]"
            aria-label={t('settings.close')}
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* 软件信息 */}
        <section className="space-y-2">
          <h4 className="text-xs uppercase tracking-wider text-[var(--text-muted)]">
            {t('settings.about')}
          </h4>
          <div className="rounded-xl border border-[var(--border-color)] bg-[var(--bg-overlay)]/40 p-4 space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-sm text-[var(--text-secondary)]">
                {t('settings.appName')}
              </span>
              <span className="text-sm font-medium text-[var(--text-primary)]">
                粮草用量
              </span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm text-[var(--text-secondary)]">
                {t('settings.version')}
              </span>
              <span className="text-sm font-medium text-[var(--text-primary)]">
                v{__APP_VERSION__}
              </span>
            </div>
          </div>
        </section>

        {/* v0.1.47+: 桌面小组件自检 */}
        <section className="space-y-2">
          <h4 className="flex items-center gap-2 text-xs uppercase tracking-wider text-[var(--text-muted)]">
            <Smartphone size={12} />
            {t('settings.widgetDiagnostics', '桌面组件自检')}
          </h4>
          <div className="rounded border border-[var(--border-color)] bg-[var(--bg-card)] p-3 text-xs space-y-1">
            {widgetStatus === undefined ? (
              <span className="text-[var(--text-muted)]">读取中…</span>
            ) : widgetStatus === null ? (
              <span className="text-[var(--text-muted)]">
                桌面尚未添加小组件，或 widget 进程还没写过状态。
              </span>
            ) : (
              <>
                <div className="flex justify-between">
                  <span className="text-[var(--text-muted)]">最后事件</span>
                  <span>{widgetStatus.lastEventTag} · {ageSec(widgetStatus.lastEventAt) !== null ? formatAgo(ageSec(widgetStatus.lastEventAt)!) : '—'}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-[var(--text-muted)]">Service 启动次数</span>
                  <span>{widgetStatus.serviceStartCount ?? 0}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-[var(--text-muted)]">最后 tick</span>
                  <span>
                    {widgetStatus.lastTickAt
                      ? `${ageSec(widgetStatus.lastTickAt)}s 前`
                      : '—'}
                    {widgetStatus.lastTickAt && (widgetNow - widgetStatus.lastTickAt) > 30000 && (
                      <span className="ml-1 text-amber-400">(30s 未 tick — service 可能被系统冻结)</span>
                    )}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-[var(--text-muted)]">最后 onUpdate</span>
                  <span>
                    {widgetStatus.lastOnUpdateAt
                      ? formatAgo(ageSec(widgetStatus.lastOnUpdateAt)!)
                      : '—'}
                  </span>
                </div>
                {widgetStatus.lastError && (
                  <div className="rounded bg-red-500/10 px-2 py-1 text-red-400">
                    <span className="text-[var(--text-muted)]">最后错误：</span>
                    {widgetStatus.lastError}
                  </div>
                )}
                {widgetStatus.lastSnapshotCount !== undefined && (
                  <div className="flex justify-between">
                    <span className="text-[var(--text-muted)]">最后读盘 provider 数</span>
                    <span>{widgetStatus.lastSnapshotCount}</span>
                  </div>
                )}
              </>
            )}
            <button
              type="button"
              className="mt-2 w-full rounded border border-[var(--border-color)] py-1 text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)]"
              onClick={async () => {
                try { setWidgetStatus(await api.getWidgetStatus()); } catch { setWidgetStatus(null); }
              }}
            >
              刷新
            </button>
          </div>
        </section>

        {/* 后台托管 */}
        <section className="space-y-3">
          <h4 className="text-xs uppercase tracking-wider text-[var(--text-muted)]">
            {t('settings.hosting')}
          </h4>

          {/* 关闭行为 */}
          <div className="rounded-xl border border-[var(--border-color)] p-4 space-y-3">
            <div className="flex items-center gap-2 text-sm text-[var(--text-primary)]">
              <HelpCircle className="w-4 h-4 text-[var(--color-primary)]" />
              {t('settings.closeAction')}
            </div>
            <div className="grid grid-cols-1 gap-2">
              <CloseActionOption
                active={closeAction === 'minimize_to_tray'}
                icon={<Minimize2 className="w-4 h-4" />}
                label={t('settings.closeMinimize')}
                onClick={() => changeCloseAction('minimize_to_tray')}
              />
              <CloseActionOption
                active={closeAction === 'exit'}
                icon={<LogOut className="w-4 h-4" />}
                label={t('settings.closeExit')}
                onClick={() => changeCloseAction('exit')}
              />
              <CloseActionOption
                active={closeAction === 'ask'}
                icon={<HelpCircle className="w-4 h-4" />}
                label={t('settings.closeAsk')}
                onClick={() => changeCloseAction('ask')}
              />
            </div>
          </div>

          {/* 托盘图标可见 — 仅桌面端 */}
          {isDesktop() && (
            <ToggleRow
              icon={<Eye className="w-4 h-4 text-[var(--color-primary)]" />}
              label={t('settings.trayVisible')}
              hint={t('settings.trayVisibleHint')}
              checked={trayVisible}
              onChange={changeTray}
            />
          )}

          {/* 开机自启 — 仅桌面端 */}
          {isDesktop() && (
            <ToggleRow
              icon={<Power className="w-4 h-4 text-[var(--color-primary)]" />}
              label={t('settings.autostart')}
              hint={t('settings.autostartHint')}
              checked={autostart}
              onChange={changeAutostart}
            />
          )}
        </section>
      </div>
    </ModalBackdrop>
  );
});

function CloseActionOption({
  active,
  icon,
  label,
  onClick,
}: {
  active: boolean;
  icon: React.ReactNode;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className={`flex items-center gap-3 px-3 py-2.5 rounded-lg border text-sm transition-colors text-left
        ${active
          ? 'border-[var(--color-primary)] bg-[var(--color-primary)]/10 text-[var(--text-primary)]'
          : 'border-[var(--border-color)] text-[var(--text-secondary)] hover:bg-[var(--bg-overlay)]'}`}
    >
      {icon}
      <span className="flex-1">{label}</span>
      {active && <div className="w-2 h-2 rounded-full bg-[var(--color-primary)]" />}
    </button>
  );
}

function ToggleRow({
  icon,
  label,
  hint,
  checked,
  onChange,
}: {
  icon: React.ReactNode;
  label: string;
  hint?: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div className="flex items-center gap-3 rounded-xl border border-[var(--border-color)] p-4">
      {icon}
      <div className="flex-1 min-w-0">
        <div className="text-sm text-[var(--text-primary)]">{label}</div>
        {hint && (
          <div className="text-xs text-[var(--text-muted)] mt-0.5 leading-snug">
            {hint}
          </div>
        )}
      </div>
      <button
        role="switch"
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={`relative w-11 h-6 rounded-full transition-colors flex-shrink-0
          ${checked ? 'bg-[var(--color-primary)]' : 'bg-[var(--border-color)]'}`}
      >
        <span
          className={`absolute top-0.5 left-0.5 w-5 h-5 rounded-full bg-white transition-transform
            ${checked ? 'translate-x-5' : 'translate-x-0'}`}
        />
      </button>
    </div>
  );
}
