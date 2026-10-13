import { theme as antdTheme, type ThemeConfig } from 'antd';
import type { CSSProperties } from 'react';

export type ThemeMode = 'light' | 'dark';

// Valores que não mudam com o tema: acento da marca, sidebar e cards escuros.
export const fixed = {
  accent: '#E2572B',
  sidebarBg: '#1B1B18',
  sidebarHover: '#2A2925',
  sidebarActive: '#34332E',
  sidebarBorder: '#45443E',
  sidebarText: '#F3F1EC',
  sidebarTextSoft: '#C9C4B9',
  sidebarTextMuted: '#A9A49A',
  sidebarLabel: '#8C877D',
  darkCardText: '#E8E4DA',
  white: '#FFFFFF',
  dotOk: '#6FBF8A',
  dotPartial: '#E0B060',
  dotNone: '#55534C',
} as const;

const palette = {
  light: {
    bg: '#F3F1EC',
    panel: '#FFFFFF',
    line: '#E2DED5',
    line2: '#EEEBE4',
    line3: '#C9C3B6',
    ink: '#1B1B18',
    ink2: '#3A3832',
    muted: '#6B675E',
    track: '#F6F4EF',
    soft: '#FBF9F5',
    stripe: '#ECE8E0',
    ok: '#2E7D4F',
    warn: '#B7791F',
    err: '#C0392B',
    okbg: '#E3F1E8',
    warnbg: '#FBEBD8',
    errbg: '#FBECEA',
    'c-api': '#3F7D3A',
    'c-broker': '#9A6B1F',
    'c-worker': '#2F5DA8',
    'c-db': '#6B4FA0',
    waitbar: '#E8D3AE',
    btn: '#1B1B18',
    btnfg: '#F3F1EC',
  },
  dark: {
    bg: '#121210',
    panel: '#1C1B18',
    line: '#2E2C28',
    line2: '#262522',
    line3: '#4A4740',
    ink: '#ECE8DF',
    ink2: '#C9C4B9',
    muted: '#9A958A',
    track: '#25241F',
    soft: '#24231F',
    stripe: '#1F1E1B',
    ok: '#6FBF8A',
    warn: '#E0B060',
    err: '#F08A7E',
    okbg: 'rgba(111,191,138,.14)',
    warnbg: 'rgba(224,176,96,.15)',
    errbg: 'rgba(240,138,126,.14)',
    'c-api': '#8FC27F',
    'c-broker': '#E0B060',
    'c-worker': '#86A9E6',
    'c-db': '#B9A2E3',
    waitbar: '#5A4A2C',
    btn: '#ECE8DF',
    btnfg: '#1B1B18',
  },
} as const;

type TokenName = keyof (typeof palette)['light'];

const tokenRef = (name: TokenName) => `var(--${name})`;

// Referências às CSS variables — use `color.ink` em vez de hexadecimais.
export const color = {
  bg: tokenRef('bg'),
  panel: tokenRef('panel'),
  line: tokenRef('line'),
  line2: tokenRef('line2'),
  line3: tokenRef('line3'),
  ink: tokenRef('ink'),
  ink2: tokenRef('ink2'),
  muted: tokenRef('muted'),
  track: tokenRef('track'),
  soft: tokenRef('soft'),
  stripe: tokenRef('stripe'),
  ok: tokenRef('ok'),
  warn: tokenRef('warn'),
  err: tokenRef('err'),
  okbg: tokenRef('okbg'),
  warnbg: tokenRef('warnbg'),
  errbg: tokenRef('errbg'),
  waitbar: tokenRef('waitbar'),
  btn: tokenRef('btn'),
  btnfg: tokenRef('btnfg'),
} as const;

// Cor de cada serviço do pipeline (Java, broker, Python, Postgres).
export const serviceColor = {
  api: tokenRef('c-api'),
  broker: tokenRef('c-broker'),
  worker: tokenRef('c-worker'),
  db: tokenRef('c-db'),
} as const;

export const font = {
  sans: "'Instrument Sans', system-ui, sans-serif",
  mono: "'JetBrains Mono', ui-monospace, monospace",
} as const;

export const mono = (fontSize: number): CSSProperties => ({ fontFamily: font.mono, fontSize });

export const eyebrow: CSSProperties = { ...mono(11), letterSpacing: '.08em' };

// Cores semânticas — nunca usar o acento da marca para significado.
export const semantic = {
  light: { ok: palette.light.ok, warn: palette.light.warn, err: palette.light.err },
  dark: { ok: palette.dark.ok, warn: palette.dark.warn, err: palette.dark.err },
} as const;

export const cssVariables = (mode: ThemeMode): CSSProperties => {
  const entries = Object.entries(palette[mode]).map(([name, value]) => [`--${name}`, value]);
  return Object.fromEntries(entries) as CSSProperties;
};

export const lightTheme: ThemeConfig = {
  algorithm: antdTheme.defaultAlgorithm,
  token: {
    colorPrimary: fixed.accent,
    colorLink: fixed.accent,
    colorText: palette.light.ink,
    colorBgBase: palette.light.panel,
    colorBgLayout: palette.light.bg,
    colorBorder: palette.light.line,
    fontFamily: font.sans,
    fontSize: 14,
    borderRadius: 6,
  },
};

export const darkTheme: ThemeConfig = {
  algorithm: antdTheme.darkAlgorithm,
  token: {
    colorPrimary: fixed.accent,
    colorLink: fixed.accent,
    colorText: palette.dark.ink,
    colorBgBase: palette.dark.panel,
    colorBgLayout: palette.dark.bg,
    colorBorder: palette.dark.line,
    fontFamily: font.sans,
    fontSize: 14,
    borderRadius: 6,
  },
};
