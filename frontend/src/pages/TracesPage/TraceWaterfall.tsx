import type { ReactNode } from 'react';
import type { PipelineDocument, ServiceName, Span } from '../../api/modules/types';
import { Card } from '../../components/ui/Card';
import { color, mono, serviceColor } from '../../theme';
import { duration } from '../../utils/format';
import { documentStatus, SERVICE_LABEL } from '../../utils/pipeline';

const COLUMNS = '84px minmax(0,150px) minmax(0,1fr) 48px';
const MIN_BAR_PERCENT = 0.4;
const SERVICES: ServiceName[] = ['api', 'broker', 'worker', 'db'];

const barColor = (span: Span) => {
  if (span.status === 'error') return color.err;
  if (span.status === 'wait') return color.waitbar;
  return serviceColor[span.service];
};

function Stat({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <span style={{ fontSize: 11.5, color: color.muted }}>{label}</span>
      {children}
    </div>
  );
}

function SpanRow({ span, doc }: { span: Span; doc: PipelineDocument }) {
  const total = doc.durationMs;
  const visible = Math.min(span.durationMs, doc.elapsedMs - span.startMs);
  const left = (span.startMs / total) * 100;
  const width = Math.max(MIN_BAR_PERCENT, (visible / total) * 100);
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: COLUMNS,
        gap: 10,
        alignItems: 'center',
        ...mono(11.5),
        padding: '3px 0',
      }}
    >
      <span
        style={{
          color: serviceColor[span.service],
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {SERVICE_LABEL[span.service]}
      </span>
      <span
        title={span.name}
        style={{
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          color: span.status === 'error' ? color.err : color.ink,
        }}
      >
        {span.name}
      </span>
      <div style={{ position: 'relative', height: 14, background: color.track, borderRadius: 2 }}>
        <div
          style={{
            position: 'absolute',
            top: 2,
            bottom: 2,
            left: `${left}%`,
            width: `${width}%`,
            background: barColor(span),
            borderRadius: 2,
            opacity: visible < span.durationMs ? 0.55 : 1,
          }}
        />
      </div>
      <span style={{ textAlign: 'right', color: color.muted }}>{duration(span.durationMs)}</span>
    </div>
  );
}

export function TraceWaterfall({ doc }: { doc: PipelineDocument }) {
  const status = documentStatus(doc);
  const shownDuration = doc.outcome ? doc.durationMs : doc.elapsedMs;
  const ticks = [0, 0.5, 1].map((fraction) => duration(doc.durationMs * fraction));

  return (
    <Card
      style={{
        padding: '18px 20px',
        display: 'flex',
        flexDirection: 'column',
        gap: 14,
        minWidth: 0,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 20, flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 3, flex: 1, minWidth: 240 }}>
          <span style={{ fontWeight: 600, fontSize: 16 }}>
            {doc.code} · {doc.fileName}
          </span>
          <span style={{ ...mono(11.5), color: color.muted, wordBreak: 'break-all' }}>
            trace_id {doc.traceId}
          </span>
        </div>
        <div style={{ display: 'flex', gap: 18 }}>
          <Stat label="Duração">
            <span style={mono(14)}>{duration(shownDuration)}</span>
          </Stat>
          <Stat label="Spans">
            <span style={mono(14)}>{doc.spans.length}</span>
          </Stat>
          <Stat label="Resultado">
            <span style={{ color: status.tone }}>{status.label}</span>
          </Stat>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap' }}>
        {SERVICES.map((service) => (
          <span
            key={service}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              fontSize: 12,
              color: color.ink2,
            }}
          >
            <span
              style={{ width: 10, height: 10, borderRadius: 2, background: serviceColor[service] }}
            />
            {SERVICE_LABEL[service]}
          </span>
        ))}
      </div>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: COLUMNS,
          gap: 10,
          ...mono(10.5),
          color: color.muted,
          borderBottom: `1px solid ${color.line}`,
          paddingBottom: 6,
        }}
      >
        <span>serviço</span>
        <span>span</span>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            gap: 6,
            overflow: 'hidden',
            whiteSpace: 'nowrap',
          }}
        >
          {ticks.map((tick, index) => (
            <span key={index}>{tick}</span>
          ))}
        </div>
        <span style={{ textAlign: 'right' }}>dur.</span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
        {doc.spans.map((span, index) => (
          <SpanRow key={index} span={span} doc={doc} />
        ))}
      </div>
    </Card>
  );
}
