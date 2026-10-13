import { App as AntApp, ConfigProvider } from 'antd';
import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { ThemeContext } from '../../hooks/useTheme';
import { darkTheme, lightTheme, type ThemeMode } from '../../theme';

const STORAGE_KEY = 'pipefiscal.theme';

const initialMode = (): ThemeMode => {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'light' || stored === 'dark') return stored;
  } catch {
    // localStorage indisponível: segue para a preferência do sistema
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
};

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<ThemeMode>(initialMode);

  const toggle = useCallback(() => {
    setMode((current) => {
      const next = current === 'dark' ? 'light' : 'dark';
      try {
        localStorage.setItem(STORAGE_KEY, next);
      } catch (error) {
        console.warn('Não foi possível salvar o tema', error);
      }
      return next;
    });
  }, []);

  const value = useMemo(() => ({ mode, toggle }), [mode, toggle]);

  return (
    <ThemeContext.Provider value={value}>
      <ConfigProvider theme={mode === 'dark' ? darkTheme : lightTheme}>
        <AntApp>{children}</AntApp>
      </ConfigProvider>
    </ThemeContext.Provider>
  );
}
