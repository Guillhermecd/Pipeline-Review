import type { EventLevel, PipelineEvent } from '../../api/modules/types';
import { Card } from '../../components/ui/Card';
import { color, mono, serviceColor } from '../../theme';
import { SERVICE_LABEL } from '../../utils/pipeline';

const RECENT_EVENTS = 10;

const LEVEL_TONE: Record<EventLevel, string> = {
  ok: color.ink,
  error: color.err,
  warn: color.warn,
  wait: color.warn,
};

export function EventsCard({ events }: { events: PipelineEvent[] }) {
  return (
    <Card style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <span style={{ fontWeight: 600, fontSize: 15, paddingBottom: 4 }}>Eventos</span>
      {events.slice(0, RECENT_EVENTS).map((event) => (
        <div
          key={event.id}
          style={{
            display: 'grid',
            gridTemplateColumns: '70px 90px 76px minmax(0,1fr)',
            gap: 10,
            ...mono(11.5),
            padding: '3px 0',
          }}
        >
          <span style={{ color: color.muted }}>{event.time}</span>
          <span style={{ color: serviceColor[event.service] }}>{SERVICE_LABEL[event.service]}</span>
          <span>{event.documentCode}</span>
          <span
            style={{
              color: LEVEL_TONE[event.level],
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {event.message}
          </span>
        </div>
      ))}
    </Card>
  );
}
