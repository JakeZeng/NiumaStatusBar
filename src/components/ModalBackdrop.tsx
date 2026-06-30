import { type ReactNode, type MouseEvent } from 'react';

interface ModalBackdropProps {
  level?: 'base' | 'nested';
  onClick?: () => void;
  children: ReactNode;
}

const LEVEL_CLASSES: Record<NonNullable<ModalBackdropProps['level']>, string> = {
  base: 'z-50 bg-black/70',
  nested: 'z-[60] bg-black/80',
};

export function ModalBackdrop({ level = 'base', onClick, children }: ModalBackdropProps) {
  const handleBackdropClick = (e: MouseEvent<HTMLDivElement>) => {
    if (onClick && e.target === e.currentTarget) onClick();
  };

  return (
    <div
      className={`fixed inset-0 ${LEVEL_CLASSES[level]} backdrop-blur-md flex items-center justify-center p-4`}
      onClick={handleBackdropClick}
    >
      {children}
    </div>
  );
}
