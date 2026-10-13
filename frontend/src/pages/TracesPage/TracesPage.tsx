import { useNavigate, useParams } from 'react-router-dom';
import { Dot } from '../../components/ui/Primitives';
import { ScreenHeader } from '../../components/ui/ScreenHeader';
import { usePipeline } from '../../hooks/usePipeline';
import { color, fixed, mono } from '../../theme';
import { duration } from '../../utils/format';
import { documentStatus } from '../../utils/pipeline';
import { TraceWaterfall } from './TraceWaterfall';

const RECENT_TRACES = 16;

export function TracesPage() {
  const { documents } = usePipeline();
  const { documentCode } = useParams();
  const navigate = useNavigate();
  const selected = documents.find((doc) => doc.code === documentCode) ?? documents[0];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <ScreenHeader eyebrow="OPENTELEMETRY · W3C TRACEPARENT" title="Trace ponta a ponta" />
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit,minmax(min(100%,460px),1fr))',
          gap: 16,
          alignItems: 'start',
        }}
      >
        <div
          style={{
            background: color.panel,
            border: `1px solid ${color.line}`,
            borderRadius: 10,
            overflow: 'hidden',
          }}
        >
          {documents.slice(0, RECENT_TRACES).map((doc) => {
            const active = doc.id === selected?.id;
            const elapsed = doc.outcome ? duration(doc.durationMs) : `${duration(doc.elapsedMs)}…`;
            return (
              <button
                key={doc.id}
                type="button"
                aria-current={active}
                onClick={() => navigate(`/traces/${doc.code}`)}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  width: '100%',
                  padding: '9px 12px',
                  border: 0,
                  borderBottom: `1px solid ${color.line2}`,
                  cursor: 'pointer',
                  font: 'inherit',
                  color: color.ink,
                  background: active ? color.soft : color.panel,
                  boxShadow: active ? `inset 3px 0 0 ${fixed.accent}` : 'none',
                }}
              >
                <Dot fill={documentStatus(doc).tone} size={7} />
                <span style={mono(12)}>{doc.code}</span>
                <span style={{ marginLeft: 'auto', ...mono(11), color: color.muted }}>
                  {elapsed}
                </span>
              </button>
            );
          })}
        </div>
        {selected && <TraceWaterfall doc={selected} />}
      </div>
    </div>
  );
}
