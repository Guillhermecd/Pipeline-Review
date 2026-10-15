import type { PipelineDocument, Trace } from '../modules/types';
import { decimal } from '../../utils/format';
import type { ApiDocument, ApiTrace } from './api-types';

const EMPTY = '—';
const API_MONEY = /^\d{1,12}\.\d{2}$/;

// "283.80" → "283,80", o formato que as telas já usam para o total. Sem valor fica vazio.
const toScreenMoney = (value: string | null) => (value === null ? '' : decimal(Number(value)));

// O que o revisor digitou ("283,80", "1.283,80" ou "283.8") no formato que a API aceita ("283.80").
// Devolve null quando o texto ainda não é um valor.
export const toApiMoney = (text: string): string | null => {
  const trimmed = text.trim().replace(/^R\$\s*/, '');
  const normalized = trimmed.includes(',') ? trimmed.replace(/\./g, '').replace(',', '.') : trimmed;
  if (!/^\d+(\.\d{1,2})?$/.test(normalized)) return null;
  const fixed = Number(normalized).toFixed(2);
  return API_MONEY.test(fixed) ? fixed : null;
};

// A API manda nulo no que o worker não conseguiu ler; as telas mostram um traço ou campo vazio.
export const toPipelineDocument = (doc: ApiDocument): PipelineDocument => ({
  id: doc.id,
  code: doc.code,
  fileName: doc.fileName,
  kind: doc.kind ?? EMPTY,
  issuer: doc.issuer ?? EMPTY,
  issueDate: doc.issueDate ?? '',
  cnpj: doc.cnpj ?? '',
  total: toScreenMoney(doc.total),
  items: doc.items.map((item) => ({
    description: item.description,
    quantity: Number(item.quantity),
    unitPrice: Number(item.unitPrice),
  })),
  itemsSum: Number(doc.itemsSum),
  idempotencyKey: doc.idempotencyKey,
  traceId: doc.traceId,
  progress: doc.progress,
  // As telas medem o pipeline, não a espera pelo revisor.
  durationMs: doc.processingMs,
  stage: doc.stage,
  outcome: doc.outcome,
  reason: doc.reason,
  review: doc.review,
  attempts: doc.attempts,
  lastError: doc.lastError ?? '',
  confidence: doc.confidence,
  fieldConfidence: doc.fieldConfidence,
  validations: doc.validations,
});

// Um trace lido do Jaeger já aconteceu por inteiro.
export const toTrace = (trace: ApiTrace): Trace => ({
  durationMs: Math.max(trace.durationMs, 1),
  elapsedMs: Math.max(trace.durationMs, 1),
  spans: trace.spans,
});
