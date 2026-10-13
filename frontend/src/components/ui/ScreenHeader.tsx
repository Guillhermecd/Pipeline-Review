import type { ReactNode } from 'react';
import { color, eyebrow } from '../../theme';

type ScreenHeaderProps = {
  eyebrow: string;
  title: string;
  description?: string;
  aside?: ReactNode;
};

export function ScreenHeader({ eyebrow: label, title, description, aside }: ScreenHeaderProps) {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 20, flexWrap: 'wrap' }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6, flex: 1, minWidth: 280 }}>
        <div style={{ ...eyebrow, color: color.muted }}>{label}</div>
        <h1 style={{ margin: 0, fontSize: 28, fontWeight: 600, letterSpacing: '-.02em' }}>
          {title}
        </h1>
        {description && (
          <p style={{ margin: 0, color: color.muted, maxWidth: 640, textWrap: 'pretty' }}>
            {description}
          </p>
        )}
      </div>
      {aside}
    </div>
  );
}
