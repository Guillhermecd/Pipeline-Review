import type { PlanPhase } from '../../api/modules/plan.service';
import { Card } from '../../components/ui/Card';
import { ProgressBar, Tag } from '../../components/ui/Primitives';
import { color, fixed, mono } from '../../theme';

type PhaseCardProps = {
  phase: PlanPhase & { done: number };
  isDone: (taskId: string) => boolean;
  onToggle: (taskId: string) => void;
};

export function PhaseCard({ phase, isDone, onToggle }: PhaseCardProps) {
  return (
    <Card style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <span style={{ ...mono(12), color: fixed.accent }}>{phase.id}</span>
        <span style={{ fontWeight: 600, fontSize: 15 }}>{phase.title}</span>
        <span style={{ marginLeft: 'auto', ...mono(12), color: color.muted }}>
          {phase.done}/{phase.tasks.length}
        </span>
        <ProgressBar
          ratio={phase.done / phase.tasks.length}
          fill={color.ok}
          style={{ width: 80 }}
        />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        {phase.tasks.map((task) => {
          const checked = isDone(task.id);
          return (
            <label
              key={task.id}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                cursor: 'pointer',
                padding: '2px 0',
              }}
            >
              <input
                type="checkbox"
                checked={checked}
                onChange={() => onToggle(task.id)}
                style={{ position: 'absolute', opacity: 0, width: 1, height: 1 }}
              />
              <span
                aria-hidden="true"
                style={{
                  width: 16,
                  height: 16,
                  flexShrink: 0,
                  borderRadius: 4,
                  border: `1.5px solid ${checked ? color.ok : color.line3}`,
                  background: checked ? color.ok : color.panel,
                  color: fixed.white,
                  fontSize: 11,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  boxSizing: 'border-box',
                }}
              >
                {checked ? '✓' : ''}
              </span>
              <span
                style={{
                  flex: 1,
                  color: checked ? color.muted : color.ink,
                  textDecoration: checked ? 'line-through' : 'none',
                  textWrap: 'pretty',
                }}
              >
                {task.title}
              </span>
              <Tag style={{ fontSize: 10.5, padding: '2px 6px', color: color.muted }}>
                {task.requirement}
              </Tag>
            </label>
          );
        })}
      </div>
    </Card>
  );
}
