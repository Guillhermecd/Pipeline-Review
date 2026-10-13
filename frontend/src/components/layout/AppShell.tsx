import { Outlet } from 'react-router-dom';
import { usePipeline } from '../../hooks/usePipeline';
import { useTheme } from '../../hooks/useTheme';
import { color, cssVariables, fixed, font, mono } from '../../theme';
import { isInDlq, isPendingReview } from '../../utils/pipeline';
import { SidebarNav } from './SidebarNav';
import { SimulationControls } from './SimulationControls';
import { ThemeToggle } from './ThemeToggle';

export function AppShell() {
  const { mode } = useTheme();
  const { documents } = usePipeline();
  const pending = documents.filter(isPendingReview).length;
  const inDlq = documents.filter(isInDlq).length;

  return (
    <div
      style={{
        ...cssVariables(mode),
        position: 'fixed',
        inset: 0,
        display: 'flex',
        background: color.bg,
        color: color.ink,
        fontFamily: font.sans,
        fontSize: 14,
        overflow: 'hidden',
        colorScheme: mode,
      }}
    >
      <aside
        style={{
          width: 232,
          flexShrink: 0,
          background: fixed.sidebarBg,
          color: fixed.sidebarText,
          display: 'flex',
          flexDirection: 'column',
          padding: '22px 14px 18px',
          gap: 26,
          boxSizing: 'border-box',
          overflowY: 'auto',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, paddingLeft: 8 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 3, flex: 1 }}>
            <div style={{ ...mono(11), color: fixed.accent, letterSpacing: '.08em' }}>
              PROJETO 05
            </div>
            <div style={{ fontWeight: 600, fontSize: 18, letterSpacing: '-.01em' }}>
              Pipeline Fiscal
            </div>
            <div style={{ fontSize: 12, color: fixed.sidebarTextMuted }}>
              Java + Python · event-driven
            </div>
          </div>
          <ThemeToggle />
        </div>
        <SidebarNav
          execution={[{ to: '/plano', label: 'Plano e tarefas' }]}
          product={[
            { to: '/upload', label: 'Upload' },
            { to: '/pipeline', label: 'Pipeline ao vivo' },
            { to: '/revisao', label: 'Revisão', badge: pending },
            { to: '/traces', label: 'Traces' },
            { to: '/dlq', label: 'DLQ', badge: inDlq },
          ]}
        />
        <SimulationControls />
      </aside>
      <main
        style={{
          flex: 1,
          minWidth: 0,
          overflow: 'auto',
          padding: 'clamp(16px,2.2vw,28px) clamp(16px,2.5vw,32px) 40px',
          boxSizing: 'border-box',
        }}
      >
        <Outlet />
      </main>
    </div>
  );
}
