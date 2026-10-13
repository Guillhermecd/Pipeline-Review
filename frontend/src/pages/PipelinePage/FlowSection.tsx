import { useNavigate } from 'react-router-dom';
import type { PipelineDocument } from '../../api/modules/types';
import { color, mono } from '../../theme';
import { Dot } from '../../components/ui/Primitives';
import type { FlowNode } from './usePipelineView';

const MAX_CHIPS = 8;

function DocumentChip({ doc }: { doc: PipelineDocument }) {
  const retrying = doc.stage.node === 'retry';
  return (
    <span
      style={{
        ...mono(10.5),
        padding: '2px 6px',
        borderRadius: 4,
        background: retrying ? color.warnbg : color.bg,
        color: retrying ? color.warn : color.ink,
      }}
    >
      {doc.code.slice(4)}
      {retrying ? ' ↻' : ''}
    </span>
  );
}

function NodeCard({ node }: { node: FlowNode }) {
  const busy = node.documents.length > 0;
  return (
    <div
      style={{
        background: color.panel,
        border: `1.5px solid ${busy ? node.tone : color.line}`,
        borderRadius: 10,
        padding: 14,
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
        minHeight: 170,
        boxSizing: 'border-box',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <Dot fill={node.tone} square />
        <span style={{ ...mono(11), color: color.muted }}>{node.step}</span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <span style={{ fontWeight: 600 }}>{node.title}</span>
        <span style={{ fontSize: 12, color: color.muted, textWrap: 'pretty' }}>
          {node.subtitle}
        </span>
      </div>
      <span style={{ ...mono(26), fontWeight: 500, marginTop: 'auto' }}>
        {node.documents.length}
      </span>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, minHeight: 20 }}>
        {node.documents.slice(0, MAX_CHIPS).map((doc) => (
          <DocumentChip key={doc.id} doc={doc} />
        ))}
      </div>
    </div>
  );
}

type Outcome = { title: string; subtitle: string; count: number; tone: string; to: string };

type FlowSectionProps = { nodes: FlowNode[]; outcomes: Outcome[] };

export function FlowSection({ nodes, outcomes }: FlowSectionProps) {
  const navigate = useNavigate();
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit,minmax(180px,1fr))',
        gap: 12,
        alignItems: 'stretch',
      }}
    >
      {nodes.map((node) => (
        <NodeCard key={node.step} node={node} />
      ))}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {outcomes.map((outcome) => (
          <button
            key={outcome.title}
            type="button"
            onClick={() => navigate(outcome.to)}
            style={{
              flex: 1,
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              background: color.panel,
              border: `1px solid ${color.line}`,
              borderLeft: 0,
              borderRadius: 10,
              padding: '10px 14px',
              cursor: 'pointer',
              font: 'inherit',
              color: color.ink,
              textAlign: 'left',
              boxShadow: `inset 3px 0 0 ${outcome.tone}`,
            }}
          >
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 2 }}>
              <span style={{ fontWeight: 600, fontSize: 13.5 }}>{outcome.title}</span>
              <span style={{ fontSize: 11.5, color: color.muted }}>{outcome.subtitle}</span>
            </div>
            <span style={{ ...mono(20), color: outcome.tone }}>{outcome.count}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
