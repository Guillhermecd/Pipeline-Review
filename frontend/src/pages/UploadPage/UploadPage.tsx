import { useState, type DragEvent } from 'react';
import { DocumentService } from '../../api/modules/document.service';
import { Button } from '../../components/ui/Button';
import { ScreenHeader } from '../../components/ui/ScreenHeader';
import { usePipeline } from '../../hooks/usePipeline';
import { color, fixed } from '../../theme';
import { SampleGrid } from './SampleGrid';
import { UploadLog } from './UploadLog';

const ACCEPTED_TYPES = 'image/*,application/pdf';

const isAccepted = (file: File) =>
  file.type.startsWith('image/') || file.type === 'application/pdf';

export function UploadPage() {
  const { uploads } = usePipeline();
  const [dragging, setDragging] = useState(false);

  const send = (files: File[]) => {
    DocumentService.uploadFiles(files.filter(isAccepted)).catch((error) =>
      console.error('Falha ao calcular o SHA-256 do arquivo', error),
    );
  };

  const onDrop = (event: DragEvent<HTMLLabelElement>) => {
    event.preventDefault();
    setDragging(false);
    send(Array.from(event.dataTransfer.files));
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 22 }}>
      <ScreenHeader
        eyebrow="POST /api/v1/documentos"
        title="Upload de documentos"
        description="A API calcula o SHA-256 do arquivo como chave de idempotência, grava o documento e publica o evento no broker. Reenviar o mesmo arquivo não cria um novo processamento."
      />
      <label
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        style={{
          position: 'relative',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 8,
          height: 150,
          border: `1.5px dashed ${dragging ? fixed.accent : color.line3}`,
          borderRadius: 12,
          background: color.panel,
          cursor: 'pointer',
          textAlign: 'center',
          padding: '0 16px',
        }}
      >
        <input
          type="file"
          multiple
          accept={ACCEPTED_TYPES}
          onChange={(event) => {
            send(Array.from(event.target.files ?? []));
            event.target.value = '';
          }}
          style={{ position: 'absolute', width: 1, height: 1, opacity: 0 }}
        />
        <span style={{ fontWeight: 600, fontSize: 15 }}>
          Solte fotos ou PDFs de notas fiscais e recibos
        </span>
        <span style={{ fontSize: 13, color: color.muted }}>
          ou clique para escolher · JPG, PNG, PDF
        </span>
      </label>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <span style={{ fontWeight: 600, fontSize: 15, marginRight: 'auto' }}>
          Documentos de exemplo
        </span>
        <Button variant="secondary" onClick={DocumentService.resubmitSameFile}>
          Testar idempotência
        </Button>
        <Button
          variant="accent"
          onClick={DocumentService.submitAllSamples}
          style={{ padding: '8px 14px' }}
        >
          Enviar todos
        </Button>
      </div>
      <SampleGrid samples={DocumentService.getSamples()} />
      <UploadLog uploads={uploads} />
    </div>
  );
}
