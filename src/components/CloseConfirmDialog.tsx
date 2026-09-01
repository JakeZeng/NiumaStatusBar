import { useState, useEffect } from 'react';
import { Minimize2, LogOut, X } from 'lucide-react';
import { api } from '../api';
import { ModalBackdrop } from './ModalBackdrop';

interface Props {
  open: boolean;
  onDismiss: () => void;
}

type Choice = 'minimize_to_tray' | 'exit';

export function CloseConfirmDialog({ open, onDismiss }: Props) {
  const [remember, setRemember] = useState(false);
  const [busy, setBusy] = useState<Choice | null>(null);

  // 每次对话框重新打开时重置交互状态。
  // 否则上一次选择"最小化到托盘"后 busy 会残留，
  // 导致下一次打开时三个按钮（取消/直接退出/最小化到托盘）全部被禁用。
  useEffect(() => {
    if (open) {
      setBusy(null);
      setRemember(false);
    }
  }, [open]);

  if (!open) return null;

  const handleChoose = async (choice: Choice) => {
    if (busy) return;
    setBusy(choice);
    try {
      if (remember) await api.setCloseAction(choice);
      if (choice === 'minimize_to_tray') {
        await api.windowHideToTray();
        onDismiss();
      } else {
        await api.appQuit();
      }
    } catch (err) {
      console.error(err);
      setBusy(null);
    }
  };

  return (
    <ModalBackdrop level="nested" onClick={onDismiss}>
      <div
        className="w-full max-w-md rounded-2xl border border-[var(--border-color)]
                   bg-[var(--bg-card)] shadow-2xl p-6 space-y-5"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3">
          <h3 className="text-base sm:text-lg font-bold text-[var(--text-primary)]">
            关闭粮草用量？
          </h3>
          <button
            onClick={onDismiss}
            className="p-1 rounded hover:bg-[var(--bg-overlay)]
                       text-[var(--text-secondary)]"
            aria-label="取消"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <p className="text-sm leading-relaxed text-[var(--text-secondary)]">
          最小化到系统托盘可以让粮草在后台继续跟踪各供应商的用量；
          直接退出则停止所有轮询，不再消耗任何额度。
        </p>

        <label className="flex items-center gap-2 text-sm text-[var(--text-secondary)] cursor-pointer">
          <input
            type="checkbox"
            checked={remember}
            onChange={(e) => setRemember(e.target.checked)}
            className="w-4 h-4 accent-[var(--color-primary)]"
          />
          记住我的选择（可在设置中重置）
        </label>

        <div className="flex flex-col-reverse sm:flex-row gap-2 pt-1">
          <button
            onClick={onDismiss}
            disabled={busy !== null}
            className="flex-1 px-4 py-2.5 rounded-lg border border-[var(--border-color)]
                       text-[var(--text-primary)] hover:bg-[var(--bg-overlay)]
                       disabled:opacity-50 transition-colors"
          >
            取消
          </button>
          <button
            onClick={() => handleChoose('exit')}
            disabled={busy !== null}
            className="flex-1 px-4 py-2.5 rounded-lg
                       border border-[var(--border-color)]
                       text-[var(--text-primary)]
                       hover:bg-[var(--bg-overlay)]
                       disabled:opacity-50
                       inline-flex items-center justify-center gap-1.5 transition-colors"
          >
            <LogOut className="w-4 h-4" />
            直接退出
          </button>
          <button
            onClick={() => handleChoose('minimize_to_tray')}
            disabled={busy !== null}
            className="flex-1 px-4 py-2.5 rounded-lg font-medium text-white
                       bg-gradient-to-r from-[var(--color-primary)] to-[var(--color-secondary)]
                       hover:shadow-[var(--glow-primary)]
                       disabled:opacity-50
                       inline-flex items-center justify-center gap-1.5 transition-all"
          >
            <Minimize2 className="w-4 h-4" />
            最小化到托盘
          </button>
        </div>
      </div>
    </ModalBackdrop>
  );
}
