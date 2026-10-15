import { useNavigate } from 'react-router-dom';
import type { EventEnvelope, PipelineDocument } from '../../api/modules/types';
import { Card, DarkCard } from '../../components/ui/Card';
import { ProgressBar } from '../../components/ui/Primitives';
import { color, fixed, mono } from '../../theme';
import { documentStatus } from '../../utils/pipeline';
import type { Metric, QueueDepth } from './usePipelineView';

const RECENT_DOCUMENTS = 8;

export function MetricsStrip({ metrics }: { metrics: Metric[] }) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(5,minmax(0,1fr))',
        gap: 1,
        background: color.line,
        border: `1px solid ${color.line}`,
        borderRadius: 10,
        overflow: 'hidden',
      }}
    >
      {metrics.map((metric) => (
        <div
          key={metric.label}
          style={{
            background: color.panel,
            padding: '12px 14px',
            display: 'flex',
            flexDirection: 'column',
            gap: 4,
            minWidth: 0,
          }}
        >
          <span
            style={{
              fontSize: 12,
              color: color.muted,
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
          >
            {metric.label}
          </span>
          <span
            style={{
              ...mono(0),
              fontSize: 'clamp(16px,1.8vw,22px)',
              fontWeight: 500,
              color: metric.tone,
            }}
          >
            {metric.value}
          </span>
        </div>
      ))}
    </div>
  );
}

export function DocumentsCard({ documents }: { documents: PipelineDocument[] }) {
  const navigate = useNavigate();
  return (
    <Card style={{ padding: '14px 18px 6px' }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'baseline',
          paddingBottom: 8,
        }}
      >
        <span style={{ fontWeight: 600, fontSize: 15 }}>Documentos</span>
        <span style={{ fontSize: 12, color: color.muted }}>clique para abrir o trace</span>
      </div>
      {documents.slice(0, RECENT_DOCUMENTS).map((doc) => {
        const status = documentStatus(doc);
        return (
          <button
            key={doc.id}
            type="button"
            onClick={() => navigate(`/traces/${doc.code}`)}
            style={{
              display: 'grid',
              gridTemplateColumns: '72px minmax(0,1fr) minmax(0,120px) 70px',
              gap: 12,
              alignItems: 'center',
              width: '100%',
              padding: '9px 0',
              border: 0,
              borderTop: `1px solid ${color.line2}`,
              background: 'transparent',
              cursor: 'pointer',
              font: 'inherit',
              fontSize: 13,
              color: color.ink,
              textAlign: 'left',
            }}
          >
            <span style={mono(12)}>{doc.code}</span>
            <span
              style={{
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                color: color.ink2,
              }}
            >
              {doc.fileName}
            </span>
            <span style={{ color: status.tone, fontSize: 12.5 }}>{status.label}</span>
            <ProgressBar ratio={doc.progress} fill={status.tone} />
          </button>
        );
      })}
    </Card>
  );
}

type BrokerCardProps = { label: string; consumerConfig: string; queues: QueueDepth[] };

export function BrokerCard({ label, consumerConfig, queues }: BrokerCardProps) {
  return (
    <Card style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <span style={{ fontWeight: 600, fontSize: 15 }}>{label}</span>
      <span style={{ ...mono(11.5), color: color.muted, paddingBottom: 6 }}>{consumerConfig}</span>
      {queues.map((queue) => (
        <div
          key={queue.name}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '7px 0',
            borderTop: `1px solid ${color.line2}`,
          }}
        >
          <span style={{ ...mono(12), flex: 1 }}>{queue.name}</span>
          <span style={{ ...mono(12), color: queue.tone }}>{queue.depth}</span>
        </div>
      ))}
    </Card>
  );
}

export function EnvelopeCard({ envelope }: { envelope: EventEnvelope | null }) {
  return (
    <DarkCard
      title="ÚLTIMO ENVELOPE"
      style={{ padding: '14px 16px', gap: 8, color: fixed.darkCardText }}
    >
      <pre
        style={{
          margin: 0,
          ...mono(11.5),
          lineHeight: 1.55,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-all',
        }}
      >
        {envelope ? JSON.stringify(envelope, null, 2) : 'aguardando eventos…'}
      </pre>
    </DarkCard>
  );
}
