import type { PipelineDocument, PipelineNode, PipelineSnapshot } from '../../api/modules/types';
import { color, serviceColor } from '../../theme';
import { duration, percent } from '../../utils/format';
import { isInDlq, isPendingReview } from '../../utils/pipeline';

export type FlowNode = {
  step: string;
  title: string;
  subtitle: string;
  tone: string;
  documents: PipelineDocument[];
};

export type Metric = { label: string; value: string; tone: string };
export type QueueDepth = { name: string; depth: number; tone: string };

const inNodes = (docs: PipelineDocument[], nodes: PipelineNode[]) =>
  docs.filter((doc) => nodes.includes(doc.stage.node));

const median = (values: number[]) => {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
};

export function usePipelineView(snapshot: PipelineSnapshot) {
  const { documents, broker } = snapshot;
  const active = documents.filter((doc) => !doc.outcome);
  const finished = documents.filter((doc) => doc.outcome);
  const notDlq = finished.filter((doc) => doc.outcome !== 'dlq');
  const autoApproved = finished.filter((doc) => doc.outcome === 'auto').length;
  const pending = documents.filter(isPendingReview).length;
  const dlq = documents.filter(isInDlq).length;
  const p50 = median(notDlq.map((doc) => doc.durationMs));

  const nodes: FlowNode[] = [
    {
      step: '01 · JAVA',
      title: 'API Spring Boot',
      subtitle: 'upload, idempotência, outbox',
      tone: serviceColor.api,
      documents: inNodes(active, ['api']),
    },
    {
      step: `02 · ${broker.label.toUpperCase()}`,
      title: broker.label,
      subtitle: `${broker.inputQueue} · retry`,
      tone: serviceColor.broker,
      documents: inNodes(active, ['broker', 'retry']),
    },
    {
      step: '03 · PYTHON',
      title: 'Worker de extração',
      subtitle: 'OCR + LLM com JSON Schema',
      tone: serviceColor.worker,
      documents: inNodes(active, ['worker']),
    },
    {
      step: '04 · JAVA',
      title: 'Validação e score',
      subtitle: 'DV do CNPJ, soma dos itens, confiança',
      tone: serviceColor.db,
      documents: inNodes(active, ['validation']),
    },
  ];

  const metrics: Metric[] = [
    { label: 'Processados', value: String(finished.length), tone: color.ink },
    {
      label: 'Aprovação automática',
      value: notDlq.length ? percent(autoApproved / notDlq.length) : '—',
      tone: color.ok,
    },
    { label: 'Em revisão', value: String(pending), tone: color.warn },
    { label: 'Na DLQ', value: String(dlq), tone: color.err },
    { label: 'Latência p50', value: p50 ? duration(p50) : '—', tone: color.ink },
  ];

  const waitingInput = active.filter(
    (doc) => doc.stage.node === 'broker' && doc.stage.label === 'Na fila',
  ).length;
  const retrying = active.filter((doc) => doc.stage.node === 'retry').length;
  const waitingOutput = active.filter(
    (doc) => doc.stage.service === 'broker' && doc.stage.label === 'Validação',
  ).length;

  const queues: QueueDepth[] = [
    { name: broker.inputQueue, depth: waitingInput, tone: color.ink },
    { name: broker.retryQueue, depth: retrying, tone: retrying ? color.warn : color.ink },
    { name: broker.outputQueue, depth: waitingOutput, tone: color.ink },
    { name: broker.deadLetterQueue, depth: dlq, tone: dlq ? color.err : color.ink },
  ];

  const outcomes = [
    {
      title: 'Aprovado automático',
      subtitle: `confiança ≥ ${percent(snapshot.threshold)}`,
      count: autoApproved,
      tone: color.ok,
      to: '/traces',
    },
    {
      title: 'Revisão humana',
      subtitle: 'validação ou confiança',
      count: pending,
      tone: color.warn,
      to: '/revisao',
    },
    { title: 'Dead-letter', subtitle: 'após 3 retries', count: dlq, tone: color.err, to: '/dlq' },
  ];

  return { nodes, metrics, queues, outcomes };
}
