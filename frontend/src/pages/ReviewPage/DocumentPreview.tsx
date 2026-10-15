import { useEffect, useState } from 'react';
import { DocumentService } from '../../api/modules/document.service';
import type { PipelineDocument } from '../../api/modules/types';
import { color, mono } from '../../theme';

const HEIGHT = 220;
const isPdf = (fileName: string) => fileName.toLowerCase().endsWith('.pdf');

// O arquivo enviado, ao lado dos campos extraídos, para o revisor conferir.
// Monte com key={doc.id}: o link é buscado de novo ao trocar de documento.
// No modo mock não existe arquivo: fica o fundo listrado com o nome.
export function DocumentPreview({ doc }: { doc: PipelineDocument }) {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    DocumentService.getFileUrl(doc.id)
      .then((link) => {
        if (!cancelled) setUrl(link);
      })
      // Sem o arquivo a revisão continua possível; o nome do arquivo fica no lugar.
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [doc.id]);

  const frame = {
    height: HEIGHT,
    width: '100%',
    borderRadius: 8,
    border: `1px solid ${color.line}`,
    boxSizing: 'border-box',
  } as const;

  if (url && isPdf(doc.fileName)) {
    return <iframe src={url} title={`Arquivo ${doc.fileName}`} style={frame} />;
  }
  if (url) {
    return (
      <a href={url} target="_blank" rel="noreferrer" title="Abrir em tamanho real">
        <img
          src={url}
          alt={`Arquivo ${doc.fileName}`}
          style={{ ...frame, objectFit: 'cover', objectPosition: 'top', display: 'block' }}
        />
      </a>
    );
  }
  return (
    <div
      style={{
        ...frame,
        background: `repeating-linear-gradient(135deg, ${color.bg} 0 8px, ${color.stripe} 8px 16px)`,
        display: 'flex',
        alignItems: 'flex-end',
        padding: 10,
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
  );
}
