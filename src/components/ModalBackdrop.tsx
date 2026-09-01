import { type ReactNode, type MouseEvent } from 'react';
import { isCoarsePointer } from '../lib/device';

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

  // 触屏设备（Android）跳过 backdrop-blur：WebView 上 backdrop-filter 性能极差，
  // 弹层打开/关闭会触发整页合成层重建，是点击卡顿的主因之一。
  // 桌面端保留 backdrop-blur 维持玻璃拟态视觉。
  const isTouch = isCoarsePointer();

  return (
    <div
      className={`fixed inset-0 ${LEVEL_CLASSES[level]} ${isTouch ? '' : 'backdrop-blur-md'} flex items-center justify-center p-4`}
      onClick={handleBackdropClick}
    >
      {children}
    </div>
  );
}
