import { useNavigate } from 'react-router-dom';
import type { PipelineDocument } from '../../api/modules/types';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { Tag } from '../../components/ui/Primitives';
import { color, mono } from '../../theme';
import { percent } from '../../utils/format';
import { confidenceTone } from '../../utils/pipeline';
import { ExtractedFields } from './ExtractedFields';
import { ItemsTable, ValidationGrid } from './ReviewChecks';

type ReviewDetailProps = {
  doc: PipelineDocument;
  threshold: number;
  onApprove: () => void;
  onReject: () => void;
};

const currentReason = (doc: PipelineDocument, threshold: number) => {
  const failed = doc.validations.filter((validation) => !validation.ok);
  if (failed.length)
    return 'Validação falhou: ' + failed.map((validation) => validation.name).join(', ');
  if (doc.confidence < threshold) {
    return `Confiança ${percent(doc.confidence)} abaixo do limiar ${percent(threshold)}`;
  }
  return 'Validações ok após correção';
};

const isResolved = (doc: PipelineDocument, threshold: number) =>
  doc.validations.every((validation) => validation.ok) && doc.confidence >= threshold;

const reviewStatus = (doc: PipelineDocument) => {
  if (doc.review === 'approved') return { label: 'aprovado', tone: color.ok };
  if (doc.review === 'rejected') return { label: 'rejeitado', tone: color.muted };
  return { label: 'aguardando revisão', tone: color.warn };
};

export function ReviewDetail({ doc, threshold, onApprove, onReject }: ReviewDetailProps) {
  const navigate = useNavigate();
  const status = reviewStatus(doc);
  const pending = !doc.review;

  return (
    <Card style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 18 }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 16, flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4, flex: 1, minWidth: 220 }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <span style={mono(12)}>{doc.code}</span>
            <Tag>{doc.kind}</Tag>
            <span style={{ fontSize: 12, color: status.tone }}>{status.label}</span>
          </div>
          <span style={{ fontWeight: 600, fontSize: 19 }}>{doc.issuer}</span>
          <span
            style={{
              fontSize: 13,
              color: isResolved(doc, threshold) ? color.ok : color.err,
              textWrap: 'pretty',
            }}
          >
            {currentReason(doc, threshold)}
          </span>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 2 }}>
          <span style={{ fontSize: 12, color: color.muted }}>Confiança</span>
          <span
            style={{
              ...mono(30),
              fontWeight: 500,
              color: confidenceTone(doc.confidence, threshold),
            }}
          >
            {percent(doc.confidence)}
          </span>
        </div>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16 }}>
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 8,
            flex: '0 1 170px',
            minWidth: 140,
          }}
        >
          {/* Placeholder do documento: trocar pela imagem/PDF via URL pré-assinada do MinIO */}
          <div
            style={{
              height: 220,
              borderRadius: 8,
              border: `1px solid ${color.line}`,
              background: `repeating-linear-gradient(135deg, ${color.bg} 0 8px, ${color.stripe} 8px 16px)`,
              display: 'flex',
              alignItems: 'flex-end',
              padding: 10,
              boxSizing: 'border-box',
            }}
          >
            <span
              style={{
                ...mono(10.5),
                color: color.muted,
                background: color.panel,
                padding: '3px 6px',
                borderRadius: 3,
                wordBreak: 'break-all',
              }}
            >
              {doc.fileName}
            </span>
          </div>
          <Button
            variant="secondary"
            onClick={() => navigate(`/traces/${doc.code}`)}
            style={{ ...mono(11.5), padding: 7 }}
          >
            trace {doc.traceId.slice(0, 12)}…
          </Button>
        </div>
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 16,
            minWidth: 'min(100%,300px)',
            flex: '1 1 300px',
          }}
        >
          <ExtractedFields key={doc.id} doc={doc} threshold={threshold} />
          <ValidationGrid validations={doc.validations} />
          <ItemsTable items={doc.items} itemsSum={doc.itemsSum} />
          {pending && (
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <Button
                variant="secondary"
                onClick={onReject}
                style={{ fontSize: 13.5, padding: '9px 16px', color: color.err }}
              >
                Rejeitar
              </Button>
              <Button
                onClick={onApprove}
                style={{ fontSize: 13.5, fontWeight: 500, padding: '9px 18px' }}
              >
                Aprovar e publicar
              </Button>
            </div>
          )}
        </div>
      </div>
    </Card>
  );
}
