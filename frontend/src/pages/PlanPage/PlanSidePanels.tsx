import type { IntegrationTest, TestRun, TestStatus } from '../../api/modules/plan.service';
import { Button } from '../../components/ui/Button';
import { Card, DarkCard } from '../../components/ui/Card';
import { Dot } from '../../components/ui/Primitives';
import { color, fixed, mono } from '../../theme';
import type { RequirementProgress } from './usePlan';

export function RequirementsCard({ requirements }: { requirements: RequirementProgress[] }) {
  return (
    <DarkCard title="REQUISITOS DE NÍVEL PLENO">
      {requirements.map((requirement) => (
        <div key={requirement.text} style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
          <Dot fill={requirement.dot} size={9} style={{ marginTop: 5 }} />
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 3 }}>
            <span style={{ textWrap: 'pretty', lineHeight: 1.4 }}>{requirement.text}</span>
            <span style={{ ...mono(11), color: fixed.sidebarTextMuted }}>{requirement.status}</span>
          </div>
        </div>
      ))}
    </DarkCard>
  );
}

const STATUS_STYLE: Record<TestStatus, { label: string; background: string; foreground: string }> =
  {
    idle: { label: 'não executado', background: color.bg, foreground: color.muted },
    queued: { label: 'na fila', background: color.bg, foreground: color.muted },
    running: { label: 'rodando…', background: color.warnbg, foreground: color.warn },
    passed: { label: 'passou', background: color.okbg, foreground: color.ok },
  };

type TestsCardProps = {
  tests: (IntegrationTest & { run: TestRun })[];
  summary: string;
  running: boolean;
  onRun: () => void;
};

export function TestsCard({ tests, summary, running, onRun }: TestsCardProps) {
  return (
    <Card style={{ padding: 18, display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2, flex: 1 }}>
          <span style={{ fontWeight: 600, fontSize: 15 }}>Testes de integração</span>
          <span style={{ fontSize: 12, color: color.muted }}>Testcontainers · {summary}</span>
        </div>
        <Button onClick={onRun} disabled={running} style={{ fontSize: 12.5 }}>
          {running ? 'Rodando…' : 'Rodar suíte'}
        </Button>
      </div>
      {tests.map((test) => {
        const style = STATUS_STYLE[test.run.status];
        const label =
          test.run.status === 'passed' ? `${style.label} ${test.run.duration}` : style.label;
        return (
          <div
            key={test.name}
            style={{
              display: 'grid',
              gridTemplateColumns: 'minmax(0,1fr) auto',
              gap: 10,
              alignItems: 'center',
              padding: '8px 0',
              borderTop: `1px solid ${color.line2}`,
            }}
          >
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
              <span style={mono(12.5)}>{test.name}</span>
              <span style={{ fontSize: 12, color: color.muted }}>{test.description}</span>
            </div>
            <span
              style={{
                ...mono(11),
                padding: '3px 8px',
                borderRadius: 4,
                background: style.background,
                color: style.foreground,
              }}
            >
              {label}
            </span>
          </div>
        );
      })}
    </Card>
  );
}
