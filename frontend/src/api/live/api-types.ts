import type {
  BrokerConfig,
  DocumentStage,
  EventEnvelope,
  FieldKey,
  Outcome,
  PipelineEvent,
  ReviewDecision,
  Span,
  Validation,
} from '../modules/types';

// Formatos da API (BACKEND.md §7.1). Dinheiro e quantidade chegam como string decimal com ponto.

export type ApiItem = { description: string; quantity: string; unitPrice: string };

export type ApiDocument = {
  id: string;
  code: string;
  fileName: string;
  kind: string | null;
  issuer: string | null;
  issueDate: string | null;
  cnpj: string | null;
  total: string | null;
  items: ApiItem[];
  itemsSum: string;
  idempotencyKey: string;
  traceId: string;
  broker: string;
  status: string;
  stage: DocumentStage;
  progress: number;
  outcome: Outcome | null;
  review: ReviewDecision | null;
  reason: string;
  attempts: number;
  lastError: string | null;
  confidence: number;
  fieldConfidence: Record<FieldKey, number>;
  validations: Validation[];
  receivedAt: string;
  finishedAt: string | null;
  // Até finishedAt: num documento em revisão inclui a espera pelo revisor.
  durationMs: number;
  // Só o processamento automático, até a decisão do pipeline ou a DLQ.
  processingMs: number;
};

export type ApiDuplicate = {
  message: string;
  idempotencyKey: string;
  documentId: string;
  code: string;
};

export type ApiSimulator = { connected: boolean; url: string; mirroredEvents: number };

export type ApiSnapshot = {
  documents: ApiDocument[];
  events: PipelineEvent[];
  broker: BrokerConfig;
  threshold: number;
  simulator: ApiSimulator;
};

export type ApiTrace = { traceId: string; durationMs: number; spans: Span[] };

export type ApiFileLink = { url: string; expiresAt: string };

export type ApiEnvelope = EventEnvelope;
