export type BrokerKind = 'kafka' | 'rabbitmq';
export type ServiceName = 'api' | 'broker' | 'worker' | 'db';
export type PipelineNode = 'api' | 'broker' | 'retry' | 'worker' | 'validation';
export type SpanStatus = 'ok' | 'error' | 'wait';
export type EventLevel = 'ok' | 'warn' | 'error' | 'wait';
export type Outcome = 'auto' | 'review' | 'dlq';
export type ReviewDecision = 'approved' | 'rejected';
export type FieldKey = 'cnpj' | 'issuer' | 'issueDate' | 'total' | 'items';
export type EditableField = 'cnpj' | 'total';
export type SimulationSpeed = 1 | 2 | 4;

export type Span = {
  service: ServiceName;
  node: PipelineNode;
  name: string;
  stage: string;
  startMs: number;
  durationMs: number;
  status: SpanStatus;
};

export type LineItem = {
  description: string;
  quantity: number;
  unitPrice: number;
};

export type Validation = {
  name: string;
  ok: boolean;
  detail: string;
};

export type DocumentStage = {
  label: string;
  node: PipelineNode;
  service: ServiceName;
};

export type PipelineDocument = {
  id: number;
  code: string;
  fileName: string;
  kind: string;
  issuer: string;
  issueDate: string;
  cnpj: string;
  total: string;
  items: LineItem[];
  itemsSum: number;
  idempotencyKey: string;
  traceId: string;
  spans: Span[];
  elapsedMs: number;
  durationMs: number;
  stage: DocumentStage;
  outcome: Outcome | null;
  reason: string;
  review: ReviewDecision | null;
  attempts: number;
  lastError: string;
  confidence: number;
  fieldConfidence: Record<FieldKey, number>;
  validations: Validation[];
};

export type PipelineEvent = {
  id: string;
  time: string;
  service: ServiceName;
  documentCode: string;
  message: string;
  level: EventLevel;
};

export type UploadResult = {
  id: string;
  time: string;
  fileName: string;
  accepted: boolean;
  detail: string;
};

export type EventEnvelope = {
  eventType: string;
  documentId: string;
  idempotencyKey: string;
  attempt: number;
  traceparent: string;
  broker: string;
};

export type BrokerConfig = {
  kind: BrokerKind;
  label: string;
  inputQueue: string;
  outputQueue: string;
  retryQueue: string;
  deadLetterQueue: string;
  consumerConfig: string;
  retryPolicy: string;
};

export type SampleDocument = {
  key: string;
  fileName: string;
  kind: string;
  issuer: string;
  cnpj: string;
  issueDate: string;
  items: LineItem[];
  itemsSum: number;
  total: number;
  baseConfidence: number;
  scenario: string;
  expectedToPass: boolean;
};

export type PipelineSnapshot = {
  documents: PipelineDocument[];
  events: PipelineEvent[];
  uploads: UploadResult[];
  lastEnvelope: EventEnvelope | null;
  broker: BrokerConfig;
  threshold: number;
  paused: boolean;
  speed: SimulationSpeed;
  autoTraffic: boolean;
  simulatorConnected: boolean;
  mirroredEvents: number;
};
