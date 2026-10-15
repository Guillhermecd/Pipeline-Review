import type {
  BrokerKind,
  EditableField,
  PipelineSnapshot,
  SampleDocument,
  SimulationSpeed,
  Trace,
} from '../modules/types';
import { BROKERS } from './fixtures';
import { EventLog } from './event-log';
import {
  createMockDocument,
  editField,
  restartForReprocess,
  settle,
  shortHash,
  toPipelineDocument,
  toTrace,
  type MockDocument,
} from './mock-document';

const TICK_MS = 200;
const AUTO_TRAFFIC_INTERVAL_MS = 3200;
const SEED_STAGGER_MS = 1700;
const MAX_DOCUMENTS = 40;
const NEXT_SPEED: Record<SimulationSpeed, SimulationSpeed> = { 1: 2, 2: 4, 4: 1 };

type Settings = { threshold: number; failureRate: number };

// Backend falso: avança os documentos no tempo e publica um snapshot imutável.
export class MockPipelineEngine {
  private documents: MockDocument[] = [];
  private seenHashes = new Set<string>();
  private log = new EventLog();
  private listeners = new Set<() => void>();
  private timer: ReturnType<typeof setInterval> | null = null;
  private sequence = 0;
  private autoElapsed = 0;
  private broker: BrokerKind = 'kafka';
  private paused = false;
  private speed: SimulationSpeed = 1;
  private autoTraffic = true;
  private snapshot: PipelineSnapshot;
  private readonly settings: Settings;
  private readonly seedSamples: SampleDocument[];

  constructor(settings: Settings, seedSamples: SampleDocument[]) {
    this.settings = settings;
    this.seedSamples = seedSamples;
    this.snapshot = this.buildSnapshot();
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    this.start();
    return () => {
      this.listeners.delete(listener);
    };
  };

  getSnapshot = () => this.snapshot;

  submitSample(sample: SampleDocument) {
    const suffix = String(this.sequence + 1).padStart(4, '0');
    const fileName = sample.fileName.replace(/(\.\w+)$/, `_${suffix}$1`);
    this.ingest(sample, fileName, shortHash(`${sample.key}-u${suffix}`));
  }

  resubmitFirstSample() {
    const sample = this.seedSamples[0];
    this.ingest(sample, sample.fileName, shortHash(sample.key));
  }

  ingest(sample: SampleDocument, fileName: string, hash: string) {
    if (this.seenHashes.has(hash)) {
      this.log.recordUpload(fileName, false, `idempotency_key sha256:${hash}… já existe`);
      this.log.push('api', '—', `409 duplicado · sha256:${hash} já processado`, 'warn');
      return this.publish();
    }
    this.seenHashes.add(hash);
    const doc = createMockDocument({
      id: ++this.sequence,
      sample,
      fileName,
      hash,
      broker: BROKERS[this.broker],
      failureRate: this.settings.failureRate,
    });
    this.documents = [doc, ...this.documents].slice(0, MAX_DOCUMENTS);
    this.log.recordUpload(fileName, true, `${doc.code} · sha256:${hash}`);
    this.advance(doc, 0);
    this.publish();
  }

  review(id: string, decision: 'approved' | 'rejected') {
    const doc = this.find(id);
    if (!doc || doc.review) return;
    doc.review = decision;
    const message =
      decision === 'approved'
        ? 'revisão humana · documento.aprovado publicado'
        : 'revisão humana · documento.rejeitado';
    this.log.push('api', doc.code, message, decision === 'approved' ? 'ok' : 'warn');
    this.publish();
  }

  edit(id: string, field: EditableField, value: string) {
    const doc = this.find(id);
    if (!doc || doc.review) return;
    editField(doc, field, value);
    this.publish();
  }

  reprocess(id: string) {
    const doc = this.find(id);
    if (!doc || doc.outcome !== 'dlq') return;
    restartForReprocess(doc, BROKERS[this.broker]);
    const message = `POST /documentos/${doc.code}/reprocessar · mesma idempotency_key`;
    this.log.push('api', doc.code, message, 'ok');
    this.advance(doc, 0);
    this.publish();
  }

  getTrace(id: string): Trace | null {
    const doc = this.find(id);
    return doc ? toTrace(doc) : null;
  }

  setBroker(kind: BrokerKind) {
    if (kind === this.broker) return;
    this.broker = kind;
    const broker = BROKERS[kind];
    const message = `broker alterado para ${broker.label} · novos documentos usam ${broker.inputQueue}`;
    this.log.push('api', '—', message, 'ok');
    this.publish();
  }

  togglePause = () => this.update(() => (this.paused = !this.paused));
  cycleSpeed = () => this.update(() => (this.speed = NEXT_SPEED[this.speed]));
  toggleAutoTraffic = () => this.update(() => (this.autoTraffic = !this.autoTraffic));
  toggleSimulator = () => this.update(() => (this.log.mirroring = !this.log.mirroring));

  private start() {
    if (this.timer) return;
    this.seedSamples.forEach((sample) =>
      this.ingest(sample, sample.fileName, shortHash(sample.key)),
    );
    [...this.documents].reverse().forEach((doc, i) => this.advance(doc, (i + 1) * SEED_STAGGER_MS));
    this.timer = setInterval(() => this.tick(), TICK_MS);
  }

  private tick() {
    if (this.paused) return;
    const step = TICK_MS * this.speed;
    this.documents.forEach((doc) => this.advance(doc, step));
    if (this.autoTraffic) {
      this.autoElapsed += step;
      if (this.autoElapsed > AUTO_TRAFFIC_INTERVAL_MS) {
        this.autoElapsed = 0;
        const index = Math.floor(Math.random() * this.seedSamples.length);
        this.submitSample(this.seedSamples[index]);
      }
    }
    this.publish();
  }

  private advance(doc: MockDocument, deltaMs: number) {
    if (doc.outcome) return;
    doc.elapsedMs = Math.min(doc.elapsedMs + deltaMs, doc.durationMs);
    while (
      doc.emittedSpans < doc.spans.length &&
      doc.spans[doc.emittedSpans].startMs <= doc.elapsedMs
    ) {
      const span = doc.spans[doc.emittedSpans++];
      this.log.recordSpan(doc, span, BROKERS[this.broker]);
    }
    if (doc.elapsedMs < doc.durationMs) return;
    if (doc.endsInDlq) {
      doc.outcome = 'dlq';
      doc.reason = doc.lastError;
      const message = `DLQ após ${doc.attempts} tentativas · ${doc.lastError}`;
      this.log.push('broker', doc.code, message, 'error');
      return;
    }
    settle(doc, this.settings.threshold);
    const approved = doc.outcome === 'auto';
    const message = approved
      ? 'documento.aprovado (automático)'
      : `documento.em_revisao · ${doc.reason}`;
    this.log.push('api', doc.code, message, approved ? 'ok' : 'warn');
  }

  private find(id: string) {
    return this.documents.find((doc) => String(doc.id) === id);
  }

  private update(mutate: () => void) {
    mutate();
    this.publish();
  }

  private publish() {
    this.snapshot = this.buildSnapshot();
    this.listeners.forEach((listener) => listener());
  }

  private buildSnapshot(): PipelineSnapshot {
    return {
      documents: this.documents.map(toPipelineDocument),
      events: this.log.events,
      uploads: this.log.uploads,
      lastEnvelope: this.log.lastEnvelope,
      broker: BROKERS[this.broker],
      threshold: this.settings.threshold,
      paused: this.paused,
      speed: this.speed,
      autoTraffic: this.autoTraffic,
      simulatorConnected: this.log.mirroring,
      mirroredEvents: this.log.mirrored,
    };
  }
}
