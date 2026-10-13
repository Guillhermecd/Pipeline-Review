import type { PipelineDocument, ServiceName } from '../api/modules/types';
import { color, fixed, serviceColor } from '../theme';

// Regras de apresentação (rótulo e cor) derivadas do estado que a API devolve.

export const SERVICE_LABEL: Record<ServiceName, string> = {
  api: 'api · java',
  broker: 'broker',
  worker: 'worker · py',
  db: 'postgres',
};

// Cores dos serviços para fundos escuros (sidebar e cards escuros).
export const SERVICE_COLOR_ON_DARK: Record<ServiceName, string> = {
  api: '#8FC27F',
  broker: fixed.dotPartial,
  worker: '#86A9E6',
  db: '#B9A2E3',
};

export type DocumentStatus = { label: string; tone: string };

export const documentStatus = (doc: PipelineDocument): DocumentStatus => {
  if (doc.outcome === 'auto') return { label: 'Aprovado', tone: color.ok };
  if (doc.outcome === 'dlq') return { label: 'DLQ', tone: color.err };
  if (doc.outcome === 'review') {
    if (doc.review === 'approved') return { label: 'Aprovado (revisão)', tone: color.ok };
    if (doc.review === 'rejected') return { label: 'Rejeitado', tone: color.muted };
    return { label: 'Em revisão', tone: color.warn };
  }
  const retrying = doc.stage.node === 'retry';
  return { label: doc.stage.label, tone: retrying ? color.warn : serviceColor[doc.stage.service] };
};

export const isPendingReview = (doc: PipelineDocument) => doc.outcome === 'review' && !doc.review;

export const isInDlq = (doc: PipelineDocument) => doc.outcome === 'dlq';

const WARN_BAND = 0.1;

export const confidenceTone = (confidence: number, threshold: number) => {
  if (confidence >= threshold) return color.ok;
  return confidence >= threshold - WARN_BAND ? color.warn : color.err;
};
