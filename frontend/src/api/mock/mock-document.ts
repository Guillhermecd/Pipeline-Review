import type {
  BrokerConfig,
  EditableField,
  FieldKey,
  Outcome,
  PipelineDocument,
  ReviewDecision,
  SampleDocument,
  Span,
} from '../modules/types';
import { decimal } from '../../utils/format';
import { TRANSIENT_ERRORS } from './fixtures';
import { decide, validateDocument, weightedConfidence } from './rules';
import { buildSpanPlan, MAX_ATTEMPTS } from './spans';

const CODE_OFFSET = 1000;
const PERMANENT_FAILURE_SHARE = 0.35;
const CONFIDENCE_JITTER = 0.07;
const EDITED_FIELD_CONFIDENCE = 0.99;

export type MockDocument = {
  id: number;
  code: string;
  fileName: string;
  sample: SampleDocument;
  hash: string;
  traceId: string;
  spans: Span[];
  durationMs: number;
  elapsedMs: number;
  emittedSpans: number;
  endsInDlq: boolean;
  lastError: string;
  attempts: number;
  values: Record<EditableField, string>;
  fieldConfidence: Record<FieldKey, number>;
  outcome: Outcome | null;
  reason: string;
  review: ReviewDecision | null;
};

export const randomHex = (length: number) =>
  Array.from({ length }, () => Math.floor(Math.random() * 16).toString(16)).join('');

// Hash curto e determinístico para os exemplos (os arquivos reais usam SHA-256).
export const shortHash = (text: string) => {
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 16777619) >>> 0;
  }
  return hash.toString(16).padStart(8, '0');
};

const clampConfidence = (value: number) => Math.max(0.3, Math.min(0.995, value));

const drawFailures = (failureRate: number) => {
  if (Math.random() >= failureRate) return 0;
  return Math.random() < PERMANENT_FAILURE_SHARE ? MAX_ATTEMPTS : 1 + Math.floor(Math.random() * 3);
};

type NewDocument = {
  id: number;
  sample: SampleDocument;
  fileName: string;
  hash: string;
  broker: BrokerConfig;
  failureRate: number;
};

export const createMockDocument = ({
  id,
  sample,
  fileName,
  hash,
  broker,
  failureRate,
}: NewDocument): MockDocument => {
  const failures = drawFailures(failureRate);
  const lastError = TRANSIENT_ERRORS[Math.floor(Math.random() * TRANSIENT_ERRORS.length)];
  const plan = buildSpanPlan(failures, lastError, broker);
  const jitter = (bias = 0) =>
    clampConfidence(sample.baseConfidence + bias + Math.random() * CONFIDENCE_JITTER - 0.04);
  return {
    id,
    code: `DOC-${CODE_OFFSET + id}`,
    fileName,
    sample,
    hash,
    traceId: randomHex(32),
    spans: plan.spans,
    durationMs: plan.durationMs,
    elapsedMs: 0,
    emittedSpans: 0,
    endsInDlq: plan.endsInDlq,
    lastError,
    attempts: Math.min(failures, MAX_ATTEMPTS - 1) + 1,
    values: { cnpj: sample.cnpj, total: decimal(sample.total) },
    fieldConfidence: {
      cnpj: jitter(),
      issuer: jitter(0.02),
      issueDate: jitter(0.03),
      total: jitter(),
      items: jitter(-0.01),
    },
    outcome: null,
    reason: '',
    review: null,
  };
};

export const restartForReprocess = (doc: MockDocument, broker: BrokerConfig) => {
  const plan = buildSpanPlan(0, doc.lastError, broker);
  Object.assign(doc, {
    spans: plan.spans,
    durationMs: plan.durationMs,
    elapsedMs: 0,
    emittedSpans: 0,
    endsInDlq: false,
    outcome: null,
  });
};

export const editField = (doc: MockDocument, field: EditableField, value: string) => {
  doc.values[field] = value;
  doc.fieldConfidence[field] = Math.max(doc.fieldConfidence[field], EDITED_FIELD_CONFIDENCE);
};

export const currentSpan = (doc: MockDocument) =>
  doc.spans.reduce(
    (current, span) => (span.startMs <= doc.elapsedMs ? span : current),
    doc.spans[0],
  );

export const documentValidations = (doc: MockDocument) =>
  validateDocument({
    cnpj: doc.values.cnpj,
    total: doc.values.total,
    itemsSum: doc.sample.itemsSum,
    issueDate: doc.sample.issueDate,
  });

export const settle = (doc: MockDocument, threshold: number) => {
  const decision = decide(
    documentValidations(doc),
    weightedConfidence(doc.fieldConfidence),
    threshold,
  );
  doc.outcome = decision.outcome;
  doc.reason = decision.reason;
};

export const toPipelineDocument = (doc: MockDocument): PipelineDocument => {
  const span = currentSpan(doc);
  return {
    id: doc.id,
    code: doc.code,
    fileName: doc.fileName,
    kind: doc.sample.kind,
    issuer: doc.sample.issuer,
    issueDate: doc.sample.issueDate,
    cnpj: doc.values.cnpj,
    total: doc.values.total,
    items: doc.sample.items,
    itemsSum: doc.sample.itemsSum,
    idempotencyKey: `sha256:${doc.hash}`,
    traceId: doc.traceId,
    spans: doc.spans.filter((s) => s.startMs <= doc.elapsedMs),
    elapsedMs: doc.elapsedMs,
    durationMs: doc.durationMs,
    stage: { label: span.stage, node: span.node, service: span.service },
    outcome: doc.outcome,
    reason: doc.reason,
    review: doc.review,
    attempts: doc.attempts,
    lastError: doc.lastError,
    confidence: weightedConfidence(doc.fieldConfidence),
    fieldConfidence: { ...doc.fieldConfidence },
    validations: documentValidations(doc),
  };
};
