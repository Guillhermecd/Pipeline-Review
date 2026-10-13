import type { CSSProperties, ReactNode } from 'react';
import { color, eyebrow, fixed } from '../../theme';

type CardProps = { children: ReactNode; style?: CSSProperties };

export function Card({ children, style }: CardProps) {
  return (
    <div
      style={{
        background: color.panel,
        border: `1px solid ${color.line}`,
        borderRadius: 10,
        padding: '14px 18px',
        boxSizing: 'border-box',
        ...style,
      }}
    >
      {children}
    </div>
  );
}

type DarkCardProps = { title: string; children: ReactNode; style?: CSSProperties };

// Card escuro: igual nos dois temas.
export function DarkCard({ title, children, style }: DarkCardProps) {
  return (
    <div
      style={{
        background: fixed.sidebarBg,
        color: fixed.sidebarText,
        borderRadius: 10,
        padding: 18,
        display: 'flex',
        flexDirection: 'column',
        gap: 14,
        ...style,
      }}
    >
      <div style={{ ...eyebrow, color: fixed.accent }}>{title}</div>
      {children}
    </div>
  );
}
