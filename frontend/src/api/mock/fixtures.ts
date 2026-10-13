import type { BrokerConfig, BrokerKind, LineItem } from '../modules/types';

// Dados de exemplo do protótipo — só existem no modo mock.

export type RawSample = {
  key: string;
  fileName: string;
  kind: string;
  issuer: string;
  cnpjBase: string;
  issueDate: string;
  items: LineItem[];
  baseConfidence: number;
  scenario: string;
  corruptCnpj?: boolean;
  totalOffset?: number;
};

const item = (description: string, quantity: number, unitPrice: number): LineItem => ({
  description,
  quantity,
  unitPrice,
});

export const RAW_SAMPLES: RawSample[] = [
  {
    key: 's1',
    fileName: 'nfe_mercado_sao_jorge.pdf',
    kind: 'NF-e',
    issuer: 'Mercado São Jorge Ltda',
    cnpjBase: '112223330001',
    issueDate: '2026-10-02',
    items: [
      item('Arroz tipo 1 5kg', 2, 27.9),
      item('Feijão carioca 1kg', 3, 8.49),
      item('Óleo de soja 900ml', 2, 7.35),
    ],
    baseConfidence: 0.95,
    scenario: 'Deve aprovar',
  },
  {
    key: 's2',
    fileName: 'recibo_posto_foto.jpg',
    kind: 'Recibo',
    issuer: 'Auto Posto Via Norte',
    cnpjBase: '045432180001',
    issueDate: '2026-10-07',
    items: [item('Gasolina comum (L)', 38.2, 6.19)],
    baseConfidence: 0.74,
    scenario: 'Foto com baixa confiança',
  },
  {
    key: 's3',
    fileName: 'nfce_farmacia_central.pdf',
    kind: 'NFC-e',
    issuer: 'Farmácia Central EIRELI',
    cnpjBase: '273650410001',
    issueDate: '2026-10-09',
    items: [item('Dipirona 500mg', 1, 12.9), item('Protetor solar FPS50', 1, 64.9)],
    baseConfidence: 0.93,
    scenario: 'CNPJ com DV inválido',
    corruptCnpj: true,
  },
  {
    key: 's4',
    fileName: 'nfe_papelaria_lote.pdf',
    kind: 'NF-e',
    issuer: 'Papelaria Ponto Final',
    cnpjBase: '607011900001',
    issueDate: '2026-09-28',
    items: [
      item('Resma A4 75g', 5, 29.9),
      item('Caneta gel (cx 12)', 2, 38.5),
      item('Pasta catálogo', 4, 11.2),
    ],
    baseConfidence: 0.92,
    scenario: 'Soma dos itens ≠ total',
    totalOffset: 12.5,
  },
  {
    key: 's5',
    fileName: 'cupom_restaurante.jpg',
    kind: 'Cupom',
    issuer: 'Restaurante Dona Lia',
    cnpjBase: '334419520001',
    issueDate: '2026-10-11',
    items: [item('Prato executivo', 2, 42), item('Suco natural', 2, 11.5)],
    baseConfidence: 0.91,
    scenario: 'Deve aprovar',
  },
  {
    key: 's6',
    fileName: 'nfse_consultoria_ti.pdf',
    kind: 'NFS-e',
    issuer: 'Kodo Consultoria em TI',
    cnpjBase: '091238770001',
    issueDate: '2026-10-01',
    items: [item('Consultoria arquitetura (h)', 16, 220)],
    baseConfidence: 0.96,
    scenario: 'Deve aprovar',
  },
];

export const TRANSIENT_ERRORS = [
  'LLM 503 Service Unavailable',
  'timeout 30s no provedor LLM',
  '429 rate limit excedido',
];

export const BROKERS: Record<BrokerKind, BrokerConfig> = {
  kafka: {
    kind: 'kafka',
    label: 'Kafka',
    inputQueue: 'documentos.recebidos',
    outputQueue: 'documentos.extraidos',
    retryQueue: 'documentos.recebidos-retry',
    deadLetterQueue: 'documentos.recebidos.DLT',
    consumerConfig: 'consumer group extrator-py · 3 partições',
    retryPolicy:
      'Retry topics não bloqueantes (@RetryableTopic no Spring, retry topic no consumer Python) com backoff exponencial. Após a 3ª falha a mensagem vai para o DLT com os headers da exceção e o traceparent original, e pode ser reprocessada pela API.',
  },
  rabbitmq: {
    kind: 'rabbitmq',
    label: 'RabbitMQ',
    inputQueue: 'extracao.q',
    outputQueue: 'validacao.q',
    retryQueue: 'extracao.retry (TTL)',
    deadLetterQueue: 'extracao.dlq',
    consumerConfig: 'exchange documentos · prefetch 10',
    retryPolicy:
      'Fila de espera com TTL por tentativa e dead-letter-exchange apontando de volta para extracao.q. O header x-death conta as tentativas; após a 3ª falha a mensagem é roteada para extracao.dlq e pode ser reprocessada pela API.',
  },
};
