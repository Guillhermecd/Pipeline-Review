import { Controller, useForm } from 'react-hook-form';
import { DocumentService } from '../../api/modules/document.service';
import type { EditableField, FieldKey, PipelineDocument } from '../../api/modules/types';
import { ProgressBar } from '../../components/ui/Primitives';
import { color, mono } from '../../theme';
import { percent, shortDate } from '../../utils/format';
import { confidenceTone } from '../../utils/pipeline';

type FormValues = Record<EditableField, string>;

type FieldRow = { key: FieldKey; label: string; editable?: EditableField; value?: string };

type ExtractedFieldsProps = { doc: PipelineDocument; threshold: number };

// Monte com key={doc.id}: o formulário reinicia ao trocar de documento.
export function ExtractedFields({ doc, threshold }: ExtractedFieldsProps) {
  const { control } = useForm<FormValues>({ defaultValues: { cnpj: doc.cnpj, total: doc.total } });
  const editable = !doc.review;

  const rows: FieldRow[] = [
    { key: 'cnpj', label: 'CNPJ', editable: 'cnpj' },
    { key: 'issuer', label: 'Emitente', value: doc.issuer },
    { key: 'issueDate', label: 'Emissão', value: shortDate(doc.issueDate) },
    { key: 'total', label: 'Total (R$)', editable: 'total' },
    { key: 'items', label: 'Itens', value: `${doc.items.length} linhas` },
  ];

  const inputStyle = (canEdit: boolean) => ({
    ...mono(13),
    border: `1px solid ${canEdit ? color.line3 : 'transparent'}`,
    background: canEdit ? color.panel : color.track,
    borderRadius: 5,
    padding: '6px 8px',
    color: color.ink,
    minWidth: 0,
    outline: 'none',
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column' }}>
      {rows.map((row) => {
        const confidence = doc.fieldConfidence[row.key];
        const tone = confidenceTone(confidence, threshold);
        const canEdit = Boolean(row.editable) && editable;
        const inputId = `field-${doc.id}-${row.key}`;
        return (
          <div
            key={row.key}
            style={{
              display: 'grid',
              gridTemplateColumns: '72px minmax(150px,1fr) minmax(56px,90px)',
              gap: 12,
              alignItems: 'center',
              padding: '6px 0',
              borderBottom: `1px solid ${color.line2}`,
            }}
          >
            <label htmlFor={inputId} style={{ fontSize: 12.5, color: color.muted }}>
              {row.label}
            </label>
            {row.editable ? (
              <Controller
                name={row.editable}
                control={control}
                render={({ field }) => (
                  <input
                    id={inputId}
                    {...field}
                    readOnly={!canEdit}
                    onChange={(event) => {
                      field.onChange(event);
                      DocumentService.updateField(doc.id, row.editable!, event.target.value);
                    }}
                    style={inputStyle(canEdit)}
                  />
                )}
              />
            ) : (
              <input id={inputId} value={row.value} readOnly style={inputStyle(false)} />
            )}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <ProgressBar ratio={confidence} fill={tone} style={{ flex: 1 }} />
              <span style={{ ...mono(11.5), width: 32, textAlign: 'right', color: tone }}>
                {percent(confidence)}
              </span>
            </div>
          </div>
        );
      })}
    </div>
  );
}
