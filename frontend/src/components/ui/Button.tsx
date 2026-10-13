import type { ButtonHTMLAttributes, CSSProperties } from 'react';
import { color, fixed } from '../../theme';

type Variant = 'primary' | 'accent' | 'outline' | 'secondary';

const variants: Record<Variant, CSSProperties> = {
  primary: { border: 0, background: color.btn, color: color.btnfg },
  accent: { border: 0, background: fixed.accent, color: fixed.white, fontWeight: 500 },
  outline: { border: `1px solid ${color.ink}`, background: 'transparent', color: color.ink },
  secondary: { border: `1px solid ${color.line}`, background: color.panel, color: color.ink },
};

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant };

export function Button({ variant = 'primary', style, type = 'button', ...props }: ButtonProps) {
  return (
    <button
      type={type}
      style={{
        cursor: 'pointer',
        font: 'inherit',
        fontSize: 13,
        padding: '7px 12px',
        borderRadius: 6,
        ...variants[variant],
        ...style,
      }}
      {...props}
    />
  );
}
