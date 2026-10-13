import type {
  BrokerConfig,
  EventEnvelope,
  EventLevel,
  PipelineEvent,
  ServiceName,
  Span,
  UploadResult,
} from '../modules/types';
import { clockTime } from '../../utils/format';
import { MAX_ATTEMPTS } from './spans';
import { randomHex, type MockDocument } from './mock-document';

const MAX_EVENTS = 80;
const MAX_UPLOADS = 8;

// Guarda o stream de eventos, o log de uploads e o último envelope publicado.
export class EventLog {
  events: PipelineEvent[] = [];
  uploads: UploadResult[] = [];
  lastEnvelope: EventEnvelope | null = null;
  mirrored = 0;
  mirroring = true;
  private sequence = 0;

  push(service: ServiceName, documentCode: string, message: string, level: EventLevel) {
    this.events = [
      { id: `ev-${++this.sequence}`, time: clockTime(), service, documentCode, message, level },
      ...this.events,
    ].slice(0, MAX_EVENTS);
    if (this.mirroring) this.mirrored++;
  }

  recordUpload(fileName: string, accepted: boolean, detail: string) {
    this.uploads = [
      { id: `up-${++this.sequence}`, time: clockTime(), fileName, accepted, detail },
      ...this.uploads,
    ].slice(0, MAX_UPLOADS);
  }

  recordSpan(doc: MockDocument, span: Span, broker: BrokerConfig) {
    const level = span.status === 'error' ? 'error' : span.status === 'wait' ? 'wait' : 'ok';
    this.push(span.service, doc.code, span.name, level);
    const verb = span.name.split(' ')[0];
    if (!['publish', 'consume', 'retry'].includes(verb)) return;
    const consumed = doc.spans
      .slice(0, doc.emittedSpans)
      .filter((s) => s.service === 'worker' && s.name.startsWith('consume')).length;
    this.lastEnvelope = {
      eventType: this.eventType(verb, span.name, broker),
      documentId: doc.code,
      idempotencyKey: `sha256:${doc.hash}`,
      attempt: Math.min(consumed, MAX_ATTEMPTS) || 1,
      traceparent: `00-${doc.traceId}-${randomHex(16)}-01`,
      broker: broker.label,
    };
  }

  private eventType(verb: string, spanName: string, broker: BrokerConfig) {
    if (verb === 'retry') return 'documento.retry';
    return spanName.includes(broker.outputQueue) ? 'documento.extraido' : 'documento.recebido';
  }
}
