import { useEffect, useState } from 'react';
import { X, AlertTriangle } from 'lucide-react';
import { ModalBackdrop } from './ModalBackdrop';

interface Props {
  open: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  cancelLabel?: string;
  danger?: boolean;
  onConfirm: () => Promise<void> | void;
  onDismiss: () => void;
}

export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel,
  cancelLabel,
  danger,
  onConfirm,
  onDismiss,
}: Props) {
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (open) setBusy(false);
  }, [open]);

  if (!open) return null;

  const handleConfirm = async () => {
    if (busy) return;
    setBusy(true);
    try {
      await onConfirm();
    } catch (err) {
      console.error(err);
      setBusy(false);
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
          <div className="flex items-center gap-3">
            {danger && (
              <div className="p-2 rounded-lg bg-[var(--color-danger)]/15 text-[var(--color-danger)]">
                <AlertTriangle className="w-5 h-5" />
              </div>
            )}
            <h3 className="text-base sm:text-lg font-bold text-[var(--text-primary)]">{title}</h3>
          </div>
          <button
            onClick={onDismiss}
            disabled={busy}
            className="p-1 rounded hover:bg-[var(--bg-overlay)]
                       text-[var(--text-secondary)] disabled:opacity-50"
            aria-label={cancelLabel}
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <p className="text-sm leading-relaxed text-[var(--text-secondary)]">
          {message}
        </p>

        <div className="flex flex-col-reverse sm:flex-row gap-2 pt-1">
          <button
            onClick={onDismiss}
            disabled={busy}
            className="flex-1 px-4 py-2.5 rounded-lg border border-[var(--border-color)]
                       text-[var(--text-primary)] hover:bg-[var(--bg-overlay)]
                       disabled:opacity-50 transition-colors"
          >
            {cancelLabel}
          </button>
          <button
            onClick={handleConfirm}
            disabled={busy}
            className={danger
              ? "flex-1 px-4 py-2.5 rounded-lg font-medium text-white " +
                "bg-[var(--color-danger)] hover:brightness-110 " +
                "disabled:opacity-50 transition-all inline-flex items-center justify-center gap-1.5"
              : "flex-1 px-4 py-2.5 rounded-lg font-medium text-white " +
                "bg-gradient-to-r from-[var(--color-primary)] to-[var(--color-secondary)] " +
                "hover:shadow-[var(--glow-primary)] disabled:opacity-50 " +
                "transition-all inline-flex items-center justify-center gap-1.5"
            }
          >
            {busy ? '...' : confirmLabel}
          </button>
        </div>
      </div>
    </ModalBackdrop>
  );
}
