import type {
  BrokerConfig,
  EventEnvelope,
  PipelineDocument,
  PipelineEvent,
  PipelineSnapshot,
  UploadResult,
} from '../modules/types';
import { API_BASE_URL } from '../../config';
import { clockTime } from '../../utils/format';
import { api, authStorage } from '../modules/api';
import { BROKERS } from '../mock/fixtures';
import type { ApiDocument, ApiSimulator, ApiSnapshot } from './api-types';
import { toPipelineDocument } from './mappers';

const MAX_DOCUMENTS = 40;
const MAX_EVENTS = 80;
const MAX_UPLOADS = 8;
const DEFAULT_THRESHOLD = 0.85;

type State = {
  documents: PipelineDocument[];
  events: PipelineEvent[];
  uploads: UploadResult[];
  lastEnvelope: EventEnvelope | null;
  broker: BrokerConfig;
  threshold: number;
  simulator: ApiSimulator;
};

// Estado ao vivo do pipeline: GET implícito no snapshot do stream + eventos SSE de /eventos.
// Expõe a mesma interface do mockEngine (subscribe/getSnapshot), então as telas não mudam.
export class LivePipelineStore {
  private state: State = {
    documents: [],
    events: [],
    uploads: [],
    lastEnvelope: null,
    // Até o snapshot chegar; o valor real vem da API.
    broker: BROKERS.kafka,
    threshold: DEFAULT_THRESHOLD,
    simulator: { connected: false, url: '', mirroredEvents: 0 },
  };
  private snapshot: PipelineSnapshot = this.buildSnapshot();
  private listeners = new Set<() => void>();
  private source: EventSource | null = null;
  private uploadSequence = 0;

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    this.connect();
    return () => {
      this.listeners.delete(listener);
    };
  };

  // A mesma referência até algo mudar: useSyncExternalStore depende disso.
  getSnapshot = () => this.snapshot;

  // Uma só conexão, mesmo que vários componentes assinem (ou o StrictMode assine duas vezes).
  connect() {
    const token = authStorage.getToken();
    if (this.source || !token) return;
    // EventSource não envia headers; só esta rota aceita o token na query string.
    const source = new EventSource(
      `${API_BASE_URL}/eventos?access_token=${encodeURIComponent(token)}`,
    );
    source.addEventListener('snapshot', (event) => this.applySnapshot(parse<ApiSnapshot>(event)));
    source.addEventListener('document', (event) => this.upsert(parse<ApiDocument>(event)));
    source.addEventListener('event', (event) => this.addEvent(parse<PipelineEvent>(event)));
    source.addEventListener('envelope', (event) =>
      this.update({ lastEnvelope: parse<EventEnvelope>(event) }),
    );
    source.addEventListener('broker', (event) =>
      this.update({ broker: parse<BrokerConfig>(event) }),
    );
    source.addEventListener('simulator', (event) =>
      this.update({ simulator: parse<ApiSimulator>(event) }),
    );
    // O EventSource reconecta sozinho e não informa o status HTTP. Uma chamada autenticada
    // distingue "API fora do ar" de "sessão expirada": no 401 o cliente HTTP volta para o login.
    source.onerror = () => {
      api('/configuracao').catch(() => undefined);
    };
    this.source = source;
  }

  disconnect() {
    this.source?.close();
    this.source = null;
  }

  // A resposta de uma ação costuma chegar antes do evento do stream, e aplicá-la já deixa a tela
  // em dia. Se o stream já mostrou um estado mais adiantado, a resposta é a notícia velha.
  applyResponse(doc: ApiDocument) {
    const current = this.state.documents.find((known) => known.id === doc.id);
    if (!current || doc.progress >= current.progress) this.upsert(doc);
  }

  upsert(doc: ApiDocument) {
    const mapped = toPipelineDocument(doc);
    const known = this.state.documents.some((current) => current.id === mapped.id);
    const documents = known
      ? this.state.documents.map((current) => (current.id === mapped.id ? mapped : current))
      : [mapped, ...this.state.documents].slice(0, MAX_DOCUMENTS);
    this.update({ documents });
  }

  setBroker(broker: BrokerConfig) {
    this.update({ broker });
  }

  // O log de uploads é desta sessão do navegador: vem das respostas 202 e 409.
  recordUpload(fileName: string, accepted: boolean, detail: string) {
    const upload: UploadResult = {
      id: `up-${++this.uploadSequence}`,
      time: clockTime(),
      fileName,
      accepted,
      detail,
    };
    this.update({ uploads: [upload, ...this.state.uploads].slice(0, MAX_UPLOADS) });
  }

  // Na conexão e em cada reconexão o servidor manda o estado inteiro, que substitui o local.
  private applySnapshot(snapshot: ApiSnapshot) {
    this.update({
      documents: snapshot.documents.map(toPipelineDocument),
      events: snapshot.events,
      broker: snapshot.broker,
      threshold: snapshot.threshold,
      simulator: snapshot.simulator,
    });
  }

  private addEvent(event: PipelineEvent) {
    if (this.state.events.some((current) => current.id === event.id)) return;
    this.update({ events: [event, ...this.state.events].slice(0, MAX_EVENTS) });
  }

  private update(changes: Partial<State>) {
    this.state = { ...this.state, ...changes };
    this.snapshot = this.buildSnapshot();
    this.listeners.forEach((listener) => listener());
  }

  private buildSnapshot(): PipelineSnapshot {
    return {
      documents: this.state.documents,
      events: this.state.events,
      uploads: this.state.uploads,
      lastEnvelope: this.state.lastEnvelope,
      broker: this.state.broker,
      threshold: this.state.threshold,
      // Controles da simulação não existem no modo live.
      paused: false,
      speed: 1,
      autoTraffic: false,
      simulatorConnected: this.state.simulator.connected,
      mirroredEvents: this.state.simulator.mirroredEvents,
    };
  }
}

const parse = <T>(event: Event) => JSON.parse((event as MessageEvent<string>).data) as T;

export const liveStore = new LivePipelineStore();
