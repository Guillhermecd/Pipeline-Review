import { DocumentService } from '../../api/modules/document.service';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { ScreenHeader } from '../../components/ui/ScreenHeader';
import { usePipeline } from '../../hooks/usePipeline';
import { color, mono } from '../../theme';
import { isInDlq } from '../../utils/pipeline';

const COLUMNS = '72px minmax(0,1fr) minmax(0,1fr) 70px 110px';
const BACKOFF_STEPS = ['+1s', '+2s', '+4s'];

const chip = (background: string, foreground: string) => ({
  padding: '6px 10px',
  borderRadius: 5,
  background,
  color: foreground,
});

export function DlqPage() {
  const { documents, broker } = usePipeline();
  const deadLetters = documents.filter(isInDlq);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <ScreenHeader
        eyebrow={`${broker.deadLetterQueue} · ${broker.label}`}
        title="Retries e dead-letter queue"
      />
      <Card style={{ padding: '18px 20px', display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div
          style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', ...mono(12) }}
        >
          {BACKOFF_STEPS.map((step, index) => (
            <span key={step} style={{ display: 'contents' }}>
              <span style={chip(color.bg, color.ink)}>tentativa {index + 1}</span>
              <span style={chip(color.warnbg, color.warn)}>{step}</span>
            </span>
          ))}
          <span style={chip(color.bg, color.ink)}>tentativa {BACKOFF_STEPS.length + 1}</span>
          <span style={chip(color.errbg, color.err)}>→ {broker.deadLetterQueue}</span>
        </div>
        <p
          style={{
            margin: 0,
            color: color.ink2,
            maxWidth: 820,
            textWrap: 'pretty',
            lineHeight: 1.5,
          }}
        >
          {broker.retryPolicy}
        </p>
      </Card>
      <Card style={{ padding: '8px 20px' }}>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: COLUMNS,
            gap: 12,
            padding: '8px 0',
            fontSize: 12,
            color: color.muted,
            borderBottom: `1px solid ${color.line}`,
          }}
        >
          <span>Documento</span>
          <span>Arquivo</span>
          <span>Último erro</span>
          <span>Tentativas</span>
          <span />
        </div>
        {!deadLetters.length && (
          <div style={{ padding: '16px 0', color: color.muted, fontSize: 13 }}>
            Nenhuma mensagem na DLQ. Aumente a taxa de falha (VITE_MOCK_FAILURE_RATE) para forçar
            retries.
          </div>
        )}
        {deadLetters.map((doc) => (
          <div
            key={doc.id}
            style={{
              display: 'grid',
              gridTemplateColumns: COLUMNS,
              gap: 12,
              alignItems: 'center',
              padding: '11px 0',
              borderBottom: `1px solid ${color.line2}`,
              fontSize: 13,
            }}
          >
            <span style={mono(12)}>{doc.code}</span>
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {doc.fileName}
            </span>
            <span style={{ ...mono(12), color: color.err }}>{doc.lastError}</span>
            <span style={mono(12)}>{doc.attempts}</span>
            <Button
              variant="outline"
              onClick={() => DocumentService.reprocess(doc.id)}
              style={{ fontSize: 12.5, padding: '5px 10px' }}
            >
              Reprocessar
            </Button>
          </div>
        ))}
      </Card>
    </div>
  );
}
