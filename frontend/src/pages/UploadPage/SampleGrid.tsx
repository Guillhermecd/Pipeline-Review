import { DocumentService } from '../../api/modules/document.service';
import type { SampleDocument } from '../../api/modules/types';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { Tag } from '../../components/ui/Primitives';
import { color, mono } from '../../theme';
import { currency } from '../../utils/format';

export function SampleGrid({ samples }: { samples: SampleDocument[] }) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fill,minmax(250px,1fr))',
        gap: 12,
      }}
    >
      {samples.map((sample) => (
        <Card
          key={sample.key}
          style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 10 }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Tag>{sample.kind}</Tag>
            <span
              style={{
                fontSize: 12,
                marginLeft: 'auto',
                color: sample.expectedToPass ? color.ok : color.warn,
              }}
            >
              {sample.scenario}
            </span>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span style={{ fontWeight: 600 }}>{sample.issuer}</span>
            <span style={{ ...mono(11.5), color: color.muted }}>{sample.fileName}</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <span style={mono(13)}>{currency(sample.total)}</span>
            <Button
              variant="outline"
              onClick={() => DocumentService.submitSample(sample)}
              style={{ fontSize: 12.5, padding: '5px 12px' }}
            >
              Enviar
            </Button>
          </div>
        </Card>
      ))}
    </div>
  );
}
