import { useMemo } from 'react';
import { THEMES, type ThemeId } from '../themes/ThemeManager';
import { isCoarsePointer } from '../lib/device';

interface Props {
  theme: ThemeId;
}

export function ThemedBackground({ theme }: Props) {
  const config = THEMES[theme];
  // 触屏设备（手机/平板/折叠屏）上彻底关掉持续动画（guoman 花瓣/cyberpunk 扫描线+脉冲），
  // 显著降低 GPU 合成开销。静态 SVG 山脉 / 纯色背景保留，桌面端不变。
  const disableAnim = useMemo(() => isCoarsePointer(), []);
  // 触屏上进一步关掉 blur 大圆（filter 是 Android WebView 上最贵的合成层），
  // 用纯色径向渐变替代，视觉接近但完全无 filter 合成开销。
  const disableBlur = useMemo(() => isCoarsePointer(), []);

  return (
    <div className="fixed inset-0 -z-10 overflow-hidden pointer-events-none">
      <div className="absolute inset-0 bg-gradient-to-br
                      from-[var(--bg-primary)] to-[var(--bg-secondary)]" />

      {config.backgroundPattern === 'grid' && (
        <CyberpunkGrid animated={!disableAnim} disableBlur={disableBlur} />
      )}
      {config.backgroundPattern === 'mountains' && <WuxiaMountains />}
      {config.backgroundPattern === 'clouds' && (
        <GuomanClouds animated={!disableAnim} />
      )}
    </div>
  );
}

function CyberpunkGrid({ animated, disableBlur }: { animated: boolean; disableBlur: boolean }) {
  return (
    <>
      <div className="absolute inset-0"
        style={{
          backgroundImage: `
            linear-gradient(var(--grid-line) 1px, transparent 1px),
            linear-gradient(90deg, var(--grid-line) 1px, transparent 1px)
          `,
          backgroundSize: '40px 40px',
        }}
      />
      {animated && (
        <>
          <div className="absolute inset-0
                          bg-gradient-to-b from-transparent via-[var(--scan-line)] to-transparent
                          bg-[length:100%_4px] animate-scan" />
          <div className="absolute top-1/4 left-1/4 w-96 h-96
                          bg-[var(--color-primary)]/10 rounded-full blur-3xl animate-pulse" />
          <div className="absolute bottom-1/4 right-1/4 w-96 h-96
                          bg-[var(--color-secondary)]/10 rounded-full blur-3xl animate-pulse" />
        </>
      )}
      {!animated && !disableBlur && (
        <>
          <div className="absolute top-1/4 left-1/4 w-96 h-96
                          bg-[var(--color-primary)]/8 rounded-full blur-3xl" />
          <div className="absolute bottom-1/4 right-1/4 w-96 h-96
                          bg-[var(--color-secondary)]/8 rounded-full blur-3xl" />
        </>
      )}
      {disableBlur && (
        <>
          {/* 触屏 + 进一步省 GPU：用纯色径向渐变叠层替代 blur-3xl 大圆，
              视觉接近但完全走 GPU 无 filter 合成开销。 */}
          <div className="absolute top-1/4 left-1/4 w-96 h-96
                          bg-[radial-gradient(circle,var(--color-primary)/12,transparent_70%)]" />
          <div className="absolute bottom-1/4 right-1/4 w-96 h-96
                          bg-[radial-gradient(circle,var(--color-secondary)/12,transparent_70%)]" />
        </>
      )}
    </>
  );
}

function WuxiaMountains() {
  return (
    <>
      <svg className="absolute bottom-0 left-0 w-full h-1/2 opacity-30" viewBox="0 0 1440 320" preserveAspectRatio="none">
        <path d="M0,160L120,180L240,140L360,200L480,160L600,220L720,180L840,240L960,200L1080,260L1200,220L1320,280L1440,240L1440,320L0,320Z"
          fill="var(--color-primary)" opacity="0.4" />
        <path d="M0,200L120,220L240,180L360,240L480,200L600,260L720,220L840,280L960,240L1080,300L1200,260L1320,320L1440,280L1440,320L0,320Z"
          fill="var(--color-primary)" opacity="0.6" />
        <path d="M0,240L120,260L240,220L360,280L480,240L600,300L720,260L840,320L960,280L1080,320L1200,300L1320,320L1440,320L1440,320L0,320Z"
          fill="var(--color-primary)" opacity="0.8" />
      </svg>
      <div className="absolute inset-0 bg-gradient-to-t from-black/40 to-transparent" />
    </>
  );
}

function GuomanClouds({ animated }: { animated: boolean }) {
  // 触屏设备上（disableBlur=true）完全跳过 blur 大圆，零 filter 合成开销；
  // 桌面端保留静态模糊效果。
  const isTouch = typeof window !== 'undefined' && isCoarsePointer();
  if (isTouch) return null;

  return (
    <>
      <div className={`absolute top-10 left-10 w-32 h-16
                      bg-white/30 rounded-full blur-2xl ${animated ? 'animate-float' : ''}`} />
      <div className={`absolute top-32 right-20 w-40 h-20
                      bg-[var(--color-primary)]/20 rounded-full blur-2xl
                      ${animated ? 'animate-float' : ''}`} />
      <div className={`absolute top-1/2 left-1/3 w-48 h-24
                      bg-[var(--color-secondary)]/15 rounded-full blur-3xl
                      ${animated ? 'animate-float' : ''}`} />

      {animated && [...Array(15)].map((_, i) => (
        <div key={i}
          className="absolute text-2xl animate-fall"
          style={{
            left: `${(i * 7) % 100}%`,
            animationDelay: `${i * 0.8}s`,
            opacity: 0.6,
          }}>
          🌸
        </div>
      ))}
    </>
  );
}
