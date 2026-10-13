import { createContext, useContext } from 'react';
import type { ThemeMode } from '../theme';

export type ThemeContextValue = { mode: ThemeMode; toggle: () => void };

export const ThemeContext = createContext<ThemeContextValue | null>(null);

export function useTheme() {
  const value = useContext(ThemeContext);
  if (!value) throw new Error('useTheme precisa estar dentro do ThemeProvider');
  return value;
}
