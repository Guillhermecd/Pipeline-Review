import type { CSSProperties, ReactNode } from 'react';
import { color, mono } from '../../theme';

type TagProps = { children: ReactNode; style?: CSSProperties };

export function Tag({ children, style }: TagProps) {
  return (
    <span
      style={{
        ...mono(11),
        background: color.bg,
        padding: '2px 7px',
        borderRadius: 4,
        whiteSpace: 'nowrap',
        ...style,
      }}
    >
      {children}
    </span>
  );
}

type ProgressBarProps = {
  ratio: number;
  fill: string;
  height?: number;
  track?: string;
  style?: CSSProperties;
};

export function ProgressBar({
  ratio,
  fill,
  height = 4,
  track = color.line2,
  style,
}: ProgressBarProps) {
  const width = `${Math.round(Math.max(0, Math.min(1, ratio)) * 100)}%`;
  return (
    <div
      style={{
        height,
        background: track,
        borderRadius: height / 2,
        overflow: 'hidden',
        ...style,
      }}
    >
      <div style={{ height: '100%', width, background: fill }} />
    </div>
  );
}

type DotProps = { fill: string; size?: number; square?: boolean; style?: CSSProperties };

export function Dot({ fill, size = 8, square = false, style }: DotProps) {
  return (
    <span
      style={{
        width: size,
        height: size,
        borderRadius: square ? 2 : '50%',
        background: fill,
        flexShrink: 0,
        display: 'inline-block',
        ...style,
      }}
    />
  );
}
