import type { BrokerConfig, Span } from '../modules/types';

// Monta a sequência de spans que um documento percorre, na ordem do trace real.

const MAX_RETRIES = 3;
const BASE_BACKOFF_MS = 1000;

const randomBetween = (min: number, max: number) => Math.round(min + Math.random() * (max - min));

export type SpanPlan = { spans: Span[]; durationMs: number; endsInDlq: boolean };

export const buildSpanPlan = (failures: number, error: string, broker: BrokerConfig): SpanPlan => {
  const spans: Span[] = [];
  let clock = 0;
  const add = (span: Omit<Span, 'startMs' | 'status'> & { status?: Span['status'] }) => {
    spans.push({ ...span, status: span.status ?? 'ok', startMs: clock });
    clock += span.durationMs;
  };

  add({
    service: 'api',
    node: 'api',
    name: 'POST /api/v1/documentos',
    durationMs: randomBetween(90, 160),
    stage: 'Recebido',
  });
  add({
    service: 'db',
    node: 'api',
    name: 'INSERT documento ON CONFLICT (idempotency_key)',
    durationMs: randomBetween(15, 35),
    stage: 'Recebido',
  });
  add({
    service: 'broker',
    node: 'broker',
    name: `publish ${broker.inputQueue}`,
    durationMs: randomBetween(10, 25),
    stage: 'Na fila',
  });

  let endsInDlq = false;
  for (let attempt = 0; ; attempt++) {
    const suffix = attempt ? ` · tentativa ${attempt + 1}` : '';
    add({
      service: 'worker',
      node: 'worker',
      name: `consume ${broker.inputQueue}${suffix}`,
      durationMs: randomBetween(10, 20),
      stage: 'OCR',
    });
    add({
      service: 'worker',
      node: 'worker',
      name: 'ocr.paddleocr',
      durationMs: randomBetween(700, 1300),
      stage: 'OCR',
    });
    if (attempt >= failures) {
      add({
        service: 'worker',
        node: 'worker',
        name: 'llm.extract (json schema)',
        durationMs: randomBetween(900, 1700),
        stage: 'Extração',
      });
      break;
    }
    add({
      service: 'worker',
      node: 'worker',
      name: `llm.extract → ${error}`,
      durationMs: randomBetween(600, 1100),
      stage: 'Extração',
      status: 'error',
    });
    if (attempt >= MAX_RETRIES) {
      add({
        service: 'broker',
        node: 'retry',
        name: `publish ${broker.deadLetterQueue}`,
        durationMs: randomBetween(10, 20),
        stage: 'DLQ',
        status: 'error',
      });
      endsInDlq = true;
      break;
    }
    const backoff = BASE_BACKOFF_MS * 2 ** attempt;
    add({
      service: 'broker',
      node: 'retry',
      name: `retry +${backoff / 1000}s → ${broker.retryQueue}`,
      durationMs: backoff,
      stage: `Retry ${attempt + 1}/${MAX_RETRIES}`,
      status: 'wait',
    });
  }

  if (!endsInDlq) {
    add({
      service: 'broker',
      node: 'broker',
      name: `publish ${broker.outputQueue}`,
      durationMs: randomBetween(10, 20),
      stage: 'Validação',
    });
    add({
      service: 'api',
      node: 'validation',
      name: `consume ${broker.outputQueue}`,
      durationMs: randomBetween(10, 20),
      stage: 'Validação',
    });
    add({
      service: 'api',
      node: 'validation',
      name: 'validate cnpj.dv + soma.itens',
      durationMs: randomBetween(40, 90),
      stage: 'Validação',
    });
    add({
      service: 'api',
      node: 'validation',
      name: 'score.confianca',
      durationMs: randomBetween(10, 20),
      stage: 'Score',
    });
    add({
      service: 'db',
      node: 'validation',
      name: 'UPDATE documento SET status',
      durationMs: randomBetween(15, 30),
      stage: 'Score',
    });
  }

  return { spans, durationMs: clock, endsInDlq };
};

export const MAX_ATTEMPTS = MAX_RETRIES + 1;
