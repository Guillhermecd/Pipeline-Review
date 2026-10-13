export type RequirementKey =
  'base' | 'eventos' | 'validacao' | 'score' | 'otel' | 'testes' | 'simulador';

export type PlanTask = { id: string; title: string; requirement: RequirementKey };
export type PlanPhase = { id: string; title: string; tasks: PlanTask[] };
export type Requirement = { key: RequirementKey; text: string };
export type IntegrationTest = { name: string; description: string };

const phase = (id: string, title: string, tasks: [string, RequirementKey][]): PlanPhase => ({
  id,
  title,
  tasks: tasks.map(([taskTitle, requirement], index) => ({
    id: `${id}.${index}`,
    title: taskTitle,
    requirement,
  })),
});

export const PHASES: PlanPhase[] = [
  phase('F1', 'Fundação', [
    ['docker-compose com Postgres, Kafka, RabbitMQ, MinIO e Jaeger', 'base'],
    ['Spring Boot: POST /api/v1/documentos com upload para MinIO', 'base'],
    ['Schema Postgres + migrações Flyway', 'base'],
    ['Idempotency key = SHA-256 do arquivo com UNIQUE constraint', 'eventos'],
  ]),
  phase('F2', 'Worker Python', [
    ['Consumer com ack manual (aiokafka / aio-pika)', 'eventos'],
    ['OCR com PaddleOCR + pré-processamento de imagem', 'base'],
    ['Extração via LLM com saída em JSON Schema', 'base'],
    ['Publicar documento.extraido com confiança por campo', 'score'],
  ]),
  phase('F3', 'Confiabilidade', [
    ['Retry com backoff exponencial 1s · 2s · 4s', 'eventos'],
    ['Dead-letter queue + endpoint de reprocessamento', 'eventos'],
    ['Transactional outbox no Spring Boot', 'eventos'],
    ['Abstração de broker: Kafka e RabbitMQ intercambiáveis', 'simulador'],
  ]),
  phase('F4', 'Validação e decisão', [
    ['Dígito verificador do CNPJ', 'validacao'],
    ['Soma dos itens igual ao total (tolerância R$ 0,01)', 'validacao'],
    ['Score de confiança ponderado + limiar configurável', 'score'],
    ['Fila de revisão humana (status PENDING_REVIEW)', 'score'],
  ]),
  phase('F5', 'Revisão e observabilidade', [
    ['Tela de revisão React/TS com edição de campos', 'score'],
    ['OpenTelemetry Java agent na API', 'otel'],
    ['OTel SDK Python + propagação do traceparent nos headers', 'otel'],
    ['Dashboards Jaeger/Grafana por documento', 'otel'],
  ]),
  phase('F6', 'Testes e integração', [
    ['Testcontainers: PostgreSQL + Kafka nos ITs Java', 'testes'],
    ['testcontainers-python no worker', 'testes'],
    ['Teste de propagação de trace Java → Python', 'testes'],
    ['Espelhar eventos no simulador de mensageria', 'simulador'],
    ['README com diagrama e demo gravada', 'base'],
  ]),
];

export const REQUIREMENTS: Requirement[] = [
  {
    key: 'eventos',
    text: 'A arquitetura é orientada a eventos, com idempotência, retry com backoff e dead-letter queue.',
  },
  {
    key: 'validacao',
    text: 'Os dados extraídos passam por validações determinísticas, como dígito verificador do CNPJ e soma dos itens igual ao total.',
  },
  {
    key: 'score',
    text: 'Um score de confiança decide se o documento é aprovado automaticamente ou vai para a fila de revisão.',
  },
  {
    key: 'otel',
    text: 'Há observabilidade com OpenTelemetry, rastreando um documento de ponta a ponta entre os serviços.',
  },
  { key: 'testes', text: 'Os testes de integração usam Testcontainers.' },
  {
    key: 'simulador',
    text: 'Diferencial: conversa diretamente com o seu simulador de mensageria.',
  },
];

export const INTEGRATION_TESTS: IntegrationTest[] = [
  { name: 'DocumentUploadIT', description: 'Java · PostgreSQLContainer + KafkaContainer' },
  { name: 'IdempotencyIT', description: 'Java · mesmo hash não cria novo documento' },
  { name: 'RetryBackoffIT', description: 'Java · 3 falhas levam a mensagem ao DLT' },
  { name: 'CnpjValidatorTest', description: 'Java · unitário, casos válidos e inválidos' },
  { name: 'ItemsSumValidatorTest', description: 'Java · unitário, tolerância de centavos' },
  { name: 'test_worker_extraction.py', description: 'Python · testcontainers KafkaContainer' },
  { name: 'TracePropagationIT', description: 'Java + Python · traceparent preservado' },
];

export const DEFAULT_COMPLETED_TASKS = ['F1.0', 'F1.1', 'F1.2'];
