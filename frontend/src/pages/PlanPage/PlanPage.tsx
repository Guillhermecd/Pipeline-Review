import { ProgressBar } from '../../components/ui/Primitives';
import { ScreenHeader } from '../../components/ui/ScreenHeader';
import { color, fixed, mono } from '../../theme';
import { percent } from '../../utils/format';
import { PhaseCard } from './PhaseCard';
import { RequirementsCard, TestsCard } from './PlanSidePanels';
import { usePlan } from './usePlan';

export function PlanPage() {
  const plan = usePlan();
  const ratio = plan.completed / plan.total;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 22 }}>
      <ScreenHeader
        eyebrow="PLANO DE EXECUÇÃO"
        title="Roadmap, requisitos e testes"
        description="Marque as tarefas conforme avança. Cada requisito de nível pleno acompanha as tarefas ligadas a ele."
        aside={
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: 6,
              width: 260,
              maxWidth: '100%',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13 }}>
              <span style={{ color: color.muted }}>Progresso geral</span>
              <span style={mono(13)}>
                {plan.completed}/{plan.total} · {percent(ratio)}
              </span>
            </div>
            <ProgressBar ratio={ratio} fill={fixed.accent} height={6} track={color.line} />
          </div>
        }
      />
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit,minmax(min(100%,380px),1fr))',
          gap: 18,
          alignItems: 'start',
        }}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {plan.phases.map((phase) => (
            <PhaseCard
              key={phase.id}
              phase={phase}
              isDone={plan.isDone}
              onToggle={plan.toggleTask}
            />
          ))}
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <RequirementsCard requirements={plan.requirements} />
          <TestsCard
            tests={plan.tests}
            summary={plan.testsSummary}
            running={plan.running}
            onRun={plan.runSuite}
          />
        </div>
      </div>
    </div>
  );
}
