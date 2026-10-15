import type { PipelineDocument } from '../../api/modules/types';
import { color, fixed, mono } from '../../theme';
import { percent } from '../../utils/format';
import { confidenceTone } from '../../utils/pipeline';

type ReviewListProps = {
  queue: PipelineDocument[];
  selectedId: string | null;
  threshold: number;
  onSelect: (id: string) => void;
};

const reviewNote = (doc: PipelineDocument) => {
  if (doc.review === 'approved') return 'Aprovado na revisão';
  if (doc.review === 'rejected') return 'Rejeitado na revisão';
  return doc.reason;
};

export function ReviewList({ queue, selectedId, threshold, onSelect }: ReviewListProps) {
  return (
    <div
      style={{
        background: color.panel,
        border: `1px solid ${color.line}`,
        borderRadius: 10,
        overflow: 'hidden',
      }}
    >
      {!queue.length && (
        <div style={{ padding: 18, color: color.muted, fontSize: 13 }}>
          Nenhum documento aguardando revisão.
        </div>
      )}
      {queue.map((doc) => {
        const active = doc.id === selectedId;
        return (
          <button
            key={doc.id}
            type="button"
            onClick={() => onSelect(doc.id)}
            aria-current={active}
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: 5,
              width: '100%',
              padding: '12px 14px',
              border: 0,
              borderBottom: `1px solid ${color.line2}`,
              cursor: 'pointer',
              font: 'inherit',
              textAlign: 'left',
              color: color.ink,
              background: active ? color.soft : color.panel,
              opacity: doc.review ? 0.6 : 1,
              boxShadow: active ? `inset 3px 0 0 ${fixed.accent}` : 'none',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, width: '100%' }}>
              <span style={mono(12)}>{doc.code}</span>
              <span style={{ fontSize: 11.5, color: color.muted }}>{doc.kind}</span>
              <span
                style={{
                  marginLeft: 'auto',
                  ...mono(12),
                  color: confidenceTone(doc.confidence, threshold),
                }}
              >
                {percent(doc.confidence)}
              </span>
            </div>
            <span style={{ fontWeight: 500, fontSize: 13.5 }}>{doc.issuer}</span>
            <span
              style={{
                fontSize: 12,
                color: doc.review ? color.muted : color.ink2,
                textWrap: 'pretty',
              }}
            >
              {reviewNote(doc)}
            </span>
          </button>
        );
      })}
    </div>
  );
}
