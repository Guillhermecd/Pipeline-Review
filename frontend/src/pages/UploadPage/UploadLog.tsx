import type { UploadResult } from '../../api/modules/types';
import { Card } from '../../components/ui/Card';
import { color, mono } from '../../theme';

export function UploadLog({ uploads }: { uploads: UploadResult[] }) {
  return (
    <Card style={{ padding: '6px 18px' }}>
      {!uploads.length && (
        <div style={{ padding: '14px 0', color: color.muted, fontSize: 13 }}>
          Nenhum envio nesta sessão.
        </div>
      )}
      {uploads.map((upload) => (
        <div
          key={upload.id}
          style={{
            display: 'grid',
            gridTemplateColumns: '80px minmax(0,1fr) minmax(120px,180px)',
            gap: 12,
            alignItems: 'center',
            padding: '11px 0',
            borderBottom: `1px solid ${color.line2}`,
            fontSize: 13,
          }}
        >
          <span style={{ ...mono(11.5), color: color.muted }}>{upload.time}</span>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{upload.fileName}</span>
            <span
              style={{
                ...mono(11),
                color: color.muted,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
              }}
            >
              {upload.detail}
            </span>
          </div>
          <span
            style={{
              color: upload.accepted ? color.ok : color.warn,
              fontWeight: 500,
              textAlign: 'right',
            }}
          >
            {upload.accepted ? 'Aceito · 202' : 'Duplicado ignorado'}
          </span>
        </div>
      ))}
    </Card>
  );
}
