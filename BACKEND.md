# Pipeline Fiscal — Especificação do backend

Este documento guia a construção do backend (API Spring Boot + worker Python) do **Pipeline de Extração de Documentos Fiscais**. O ponto de partida é o frontend em `frontend/`, que já funciona em modo mock. O backend existe para substituir esse mock sem mudar as telas.

Três regras valem para todo o documento:

1. **O frontend é o contrato.** Os tipos de `frontend/src/api/modules/types.ts`, os nomes de filas e os textos que a interface mostra (rótulos de estágio, nomes de spans, mensagens de validação) são o que o backend precisa produzir. Se algo aqui contradisser o frontend, o frontend vence e este documento é corrigido.
2. **O Main README (BIMD Template) é a lei de organização.** Estrutura de pastas, camadas, Clean Code/SOLID, nomenclatura em inglês, datas ISO 8601, Conventional Commits e checklist de PR valem sem exceção. Onde este projeto acrescenta algo, a seção [Acréscimos ao template](#acréscimos-ao-template) diz o quê e por quê.
3. **A regra de negócio mora no backend.** As regras em `frontend/src/api/mock/rules.ts` são uma cópia para a simulação. A fonte da verdade passa a ser o Spring Boot.

---

## Sumário

1. [Decisões de arquitetura](#1-decisões-de-arquitetura)
2. [Estrutura do repositório](#2-estrutura-do-repositório)
3. [Serviços locais e variáveis de ambiente](#3-serviços-locais-e-variáveis-de-ambiente)
4. [Fluxo de ponta a ponta](#4-fluxo-de-ponta-a-ponta)
5. [Mensageria: filas, mensagens, retry e DLQ](#5-mensageria-filas-mensagens-retry-e-dlq)
6. [Backend Spring Boot](#6-backend-spring-boot)
7. [API REST e stream de eventos](#7-api-rest-e-stream-de-eventos)
8. [Worker Python](#8-worker-python)
9. [Observabilidade (OpenTelemetry)](#9-observabilidade-opentelemetry)
10. [Testes](#10-testes)
11. [Mudanças no frontend para o modo live](#11-mudanças-no-frontend-para-o-modo-live)
12. [Ordem de implementação](#12-ordem-de-implementação)
13. [Checklist de validação](#13-checklist-de-validação)
14. [Pontos em aberto](#14-pontos-em-aberto)

---

## 1. Decisões de arquitetura

Copiar esta tabela para o `ARCHITECTURE.md` na fase F1, como o template exige.

| Decisão | Escolha | Justificativa |
|---|---|---|
| Backend | **Java 21 / Spring Boot 3** (Maven, `./mvnw`) | Regras de negócio complexas (validações, score, máquina de estados) e orquestração transacional |
| Banco | **PostgreSQL** + `spring-boot-starter-data-jpa` + Hibernate, migrações com **Flyway** | Relações documento → itens → validações; transações ACID para o outbox; `ddl-auto=validate` do template |
| Worker | **Python 3.12** com `uv` | Ecossistema de OCR e LLM |
| OCR | **PaddleOCR** + pré-processamento OpenCV; **PyMuPDF** para PDF com camada de texto; **pyzbar** para QR Code de NFC-e | Decisão do design; PaddleOCR lida melhor com cupom térmico e foto torta |
| LLM | **Google Gemini**, saída em **JSON Schema** (ver §14) | Estrutura itens e campos a partir do texto do OCR |
| Broker | **Kafka e RabbitMQ intercambiáveis** atrás de uma abstração (`MessagingPort`) | Requisito do design ("Abstração de broker", fase F3) e diferencial do simulador de mensageria |
| Retry | 3 retries com backoff **1 s · 2 s · 4 s**; a 4ª falha vai para a DLQ | Valores mostrados na tela DLQ |
| Idempotência | `idempotency_key = SHA-256 do arquivo`, UNIQUE no banco; reenvio responde **409** | Tela de Upload |
| Score | Média ponderada da confiança por campo: CNPJ .25, total .25, itens .20, data .15, emitente .15 | Mesmos pesos do mock |
| Decisão | Validação falhou → revisão; score < limiar → revisão; senão aprovação automática. Limiar padrão **0.85**, configurável | Tela de Revisão |
| Datas | **String ISO 8601** (`2026-10-13T13:20:00.000Z`) no tráfego **e no banco**, como manda o README | Regra do template. Ver §14 sobre `timestamptz` |
| Observabilidade | OpenTelemetry (Java agent + SDK Python), `traceparent` nos headers das mensagens, Jaeger | Tela de Traces |

---

## 2. Estrutura do repositório

Monorepo do template, com duas pastas novas na raiz (`worker/` e `contracts/`). `frontend/` e `backend/` continuam onde o repositório `infra` espera.

```text
pipeline-fiscal/
  backend/                 # Spring Boot (estrutura Java do template)
  frontend/                # React + TS + Vite (já existe)
  worker/                  # NOVO — Python: OCR + extração
  contracts/               # NOVO — JSON Schemas das mensagens + exemplos
    events/
    examples/
  docker-compose.dev.yml
  ARCHITECTURE.md
  README.md
```

### Backend — dentro da estrutura do template

Pacote base `com.bimd.fiscalpipeline`. Nada sai das pastas do template; o que é novo está marcado.

```text
backend/src/main/java/com/bimd/fiscalpipeline/
  config/                  # Beans, segurança, CORS, S3, OTel, propriedades tipadas (@ConfigurationProperties)
  controller/              # DocumentController, TraceController, BrokerController,
                           # EventStreamController, SimulatorController, DemoController
  service/
    document/              # DocumentService (upload, consulta, correção, revisão, reprocessamento)
    pipeline/              # PipelineStateService (máquina de estados), ExtractionResultService
    validation/            # NOVO — DocumentRule (interface) + uma classe por regra
    scoring/               # NOVO — ConfidenceScorer, ReviewDecider
    trace/                 # NOVO — TraceService (consulta o Jaeger)
    event/                 # NOVO — PipelineEventPublisher (SSE + simulador), SimulatorForwarder
    demo/                  # NOVO — DemoTrafficService (perfil `demo`)
  messaging/               # NOVO — porta de entrada/saída das mensagens
    MessagingPort.java     # interface: publish(EventEnvelope, Destination)
    kafka/                 # KafkaMessagingAdapter + listeners
    rabbitmq/              # RabbitMessagingAdapter + listeners + declaração de filas
    outbox/                # OutboxRelay (agendado) + OutboxEvent
    listener/              # ExtractionResultListener, ProgressListener, DeadLetterListener
  repository/              # Interfaces Spring Data puras
  domain/
    model/                 # @Entity: Document, DocumentItem, Extraction, ValidationResult, Review,
                           #          OutboxEvent, ProcessedEvent, PipelineEventRecord
    dto/                   # Records de requisição/resposta (ver §7)
    mapper/                # MapStruct
    enums/                 # NOVO — DocumentStatus, BrokerKind, ServiceName, EventLevel...
  exception/               # Exceções nomeadas + @ControllerAdvice
  util/                    # Funções puras (CnpjCheckDigit, MoneyParser, IsoDates)
  security/                # JWT do template
backend/src/main/resources/
  application.yml · application-dev.yml · application-prod.yml · application-demo.yml
  db/migration/            # V1__init.sql, V2__..., Flyway
backend/src/test/java/com/bimd/fiscalpipeline/
  controller/              # Testes de integração (MockMvc + Testcontainers)
  service/                 # Testes unitários (Mockito)
  messaging/               # NOVO — ITs de mensageria (Testcontainers)
```

Regras de camada do template aplicadas aqui:

- **Controller:** declara a rota, valida com Bean Validation, delega ao service. Nunca devolve entidade, sempre DTO.
- **Listener de mensagens** (`messaging/listener`): é uma porta de entrada como o controller. Recebe, converte para DTO, delega ao service. Nenhuma regra.
- **Service:** dono de toda regra. Não conhece `ResponseEntity` nem detalhes de Kafka/RabbitMQ (fala com `MessagingPort`).
- **Adapters de broker:** só traduzem `MessagingPort` para o client do broker. Trocar de broker não toca em service.
- **Validação:** cada regra é uma classe que implementa `DocumentRule` (Open/Closed). Regra nova não altera as existentes.
- Arquivos com no máximo ~200 linhas. Exceções nunca são engolidas.

### Worker — camadas espelhando o template

```text
worker/
  src/fiscal_worker/
    consumers/             # ≈ controller: recebe a mensagem, valida o envelope, chama o service
    services/              # ExtractionService — orquestra a extração
    extractors/            # pdf_text, paddle_ocr, qr_code, llm — atrás de Protocols
    messaging/             # adapters kafka (aiokafka) e rabbitmq (aio-pika) atrás de um Protocol
    publishers/            # publica resultado, progresso e DLQ com confirmação
    domain/                # modelos Pydantic gerados/validados contra contracts/
    config/                # pydantic-settings (.env)
    storage/               # leitura do arquivo no MinIO (S3), atrás de um Protocol
    telemetry/             # setup do OpenTelemetry (fase F5)
    helpers/               # funções puras: normalização de texto, valores, datas
  tests/{unit,integration}/
  scripts/                 # generate_samples.py (amostras), smoke.py (ponta a ponta), probes.py
                           # evaluate.py: mede acerto por campo no conjunto rotulado (fora do CI, a fazer)
  pyproject.toml · uv.lock · .env.example
```

Mesmas regras: arquivos de ~150 linhas, nomes em inglês, dependências por injeção no construtor (`Protocol`), erro classificado em `TransientError` (retry) ou `PermanentError` (DLQ direto), nunca `except Exception: pass`. **O worker não acessa o PostgreSQL**: lê o arquivo no MinIO e fala com o backend só por mensagens.

### Acréscimos ao template

| Acréscimo | Onde | Por quê |
|---|---|---|
| `worker/` e `contracts/` | raiz | Serviço Python e contrato compartilhado das mensagens |
| `messaging/` | backend | Mensagens são uma porta de entrada como HTTP; isolar evita que services dependam do broker |
| `domain/enums/` | backend | Estados e tipos fechados usados por entidade, DTO e mensagens |
| `api/mock/` e `utils/` | frontend | Modo mock e formatação (já existem) |
| Perfil `demo` | backend | Tráfego automático e amostras só existem em demonstração |
| Serviço do worker no deploy | infra | `infra/deploy.sh` conhece só `frontend/` e `backend/`; o worker precisa de um serviço próprio, sem rota no Nginx |

---

## 3. Serviços locais e variáveis de ambiente

```sh
docker compose -f docker-compose.dev.yml up -d postgres minio mailpit kafka rabbitmq jaeger
```

| Serviço | Endereço | Observação |
|---|---|---|
| PostgreSQL | `localhost:5442` | Obrigatório no boot (template). Banco `pipeline_fiscal` |
| MinIO | `http://localhost:9050` · console `:9051` | Bucket `pipeline-fiscal`. Imagem `cgr.dev/chainguard/minio` (a `minio/minio` saiu do Docker Hub) |
| Mailpit | SMTP `127.0.0.1:1035` · UI `:8035` | Template |
| Kafka (KRaft, 1 nó) | `localhost:9092` | Tópicos com 3 partições |
| RabbitMQ | `amqp://localhost:5673` · UI `:15673` | Imagem `rabbitmq:3-management` |
| Jaeger all-in-one | UI `:16686` · OTLP gRPC `:4317` · HTTP `:4318` | Recebe direto do Java e do Python |

As portas do host de Postgres, MinIO, Mailpit e RabbitMQ estão deslocadas para conviver com outros projetos na mesma máquina. Dentro da rede do compose as portas são as padrão.

Os dois brokers sobem juntos: o broker ativo é escolhido em tempo de execução (§5.1). Falha no MinIO, Mailpit, Jaeger ou no broker **inativo** gera só warning, seguindo a regra do template.

### Backend (`backend/.env.example`)

Mantém todas as variáveis do template para Spring Boot + PostgreSQL (`SERVER_PORT=1337`, `SPRING_DATASOURCE_*`, `JWT_*`, `CORS_ORIGINS`, `INITIAL_USER_*`, `EMAIL_*`, `SMTP_*`, `S3_*`) e acrescenta:

```env
S3_BUCKET=pipeline-fiscal
UPLOAD_MAX_SIZE_MB=10

BROKER_DEFAULT=kafka                       # kafka | rabbitmq
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
RABBITMQ_URL=amqp://guest:guest@localhost:5673

REVIEW_CONFIDENCE_THRESHOLD=0.85
RETRY_BACKOFF_MS=1000,2000,4000

OTEL_SERVICE_NAME=fiscal-api
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
JAEGER_QUERY_URL=http://localhost:16686

MESSAGING_SIMULATOR_URL=ws://localhost:8090/events
MESSAGING_SIMULATOR_ENABLED=true

DEMO_AUTO_TRAFFIC_INTERVAL_MS=3200          # só no perfil demo
```

### Worker (`worker/.env.example`)

```env
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
RABBITMQ_URL=amqp://guest:guest@localhost:5673
S3_ENDPOINT=http://localhost:9050
S3_BUCKET=pipeline-fiscal
S3_REGION=us-east-1
S3_ACCESS_KEY_ID=worker-readonly
S3_SECRET_ACCESS_KEY=change-me
OTEL_SERVICE_NAME=fiscal-worker
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
GEMINI_API_KEY=
LLM_MODEL=gemini-2.5-flash
LLM_TIMEOUT_SECONDS=30
OCR_LANG=pt
```

---

## 4. Fluxo de ponta a ponta

```text
Frontend ──POST /api/v1/documentos──▶ API Spring Boot ──(outbox)──▶ entrada ──▶ Worker Python
   ▲                                      │   ▲                                    │
   │ SSE /api/v1/eventos                  │   └──── saída ◀────────────────────────┤ resultado
   └──────────────────────────────────────┤   └──── progresso ◀────────────────────┤ estágio
                                          │   └──── DLQ ◀──────── retry (1s/2s/4s)─┘ falha
                                          ▼
                                     PostgreSQL · MinIO · Jaeger
```

1. **Upload.** `POST /api/v1/documentos` grava o arquivo no MinIO, calcula o SHA-256 e, **na mesma transação**, insere o documento (`RECEIVED`), uma linha em `outbox_events` e o evento de log. Hash repetido → 409, nada é criado.
2. **Outbox.** O `OutboxRelay` publica `documento.recebido` na fila de entrada do broker gravado no documento e move o status para `QUEUED`.
3. **Worker.** Consome com ack manual, publica `documento.progresso` ao entrar em OCR e em extração, extrai e publica `documento.extraido` na fila de saída. Só então dá ack.
4. **Falha transitória.** O worker publica `documento.progresso` com estágio `RETRY_WAIT` e reenvia a mensagem para a fila de retry com `attempt + 1`. Na 4ª falha, ou em erro permanente, publica na DLQ.
5. **Validação.** O backend consome o resultado, grava a extração, roda as regras, calcula o score e move para `AUTO_APPROVED` ou `PENDING_REVIEW`.
6. **DLQ.** O backend consome a DLQ e move para `DEAD_LETTERED`.
7. **Stream.** Toda mudança de estado e todo evento vão para o SSE (frontend) e para o simulador de mensageria.

---

## 5. Mensageria: filas, mensagens, retry e DLQ

### 5.1 Broker ativo

`BrokerKind` = `KAFKA | RABBITMQ`. O broker ativo fica em memória (inicializado por `BROKER_DEFAULT`) e muda por `PUT /api/v1/broker`. **Cada documento grava o broker em que entrou** (`documents.broker`), e todo o ciclo dele (retries, resultado, reprocessamento) usa esse broker. O worker consome dos dois ao mesmo tempo. Trocar o broker afeta só documentos novos e registra o evento `broker alterado para {label} · novos documentos usam {fila de entrada}`.

### 5.2 Nomes (iguais aos do frontend)

| Papel | Kafka | RabbitMQ |
|---|---|---|
| Entrada | tópico `documentos.recebidos` (3 partições) | fila `extracao.q` |
| Retry | tópico `documentos.recebidos-retry` | filas `extracao.retry.1s`, `.2s`, `.4s` (TTL fixo, DLX → `extracao.q`) |
| Saída | tópico `documentos.extraidos` | fila `validacao.q` |
| DLQ | tópico `documentos.recebidos.DLT` | fila `extracao.dlq` |
| Progresso (interno) | tópico `documentos.progresso` | fila `progresso.q` |
| Grupo / config | consumer group `extrator-py` | exchange `documentos` (direct), `prefetch 10` |

A tela mostra uma fila de retry por broker. No RabbitMQ são três filas (uma por TTL) para evitar o bloqueio de cabeça de fila que uma fila única com TTL por mensagem causaria. O `BrokerConfig` devolvido à tela mantém `retryQueue = "extracao.retry (TTL)"`.

Os textos de `consumerConfig` e `retryPolicy` do `BrokerConfig` são os mesmos de `frontend/src/api/mock/fixtures.ts`.

### 5.3 Envelope (`contracts/events/envelope.schema.json`)

```json
{
  "eventId": "3f6c2a10-8d1e-4b7a-9c55-0e2f1a9b7d42",
  "eventType": "documento.recebido",
  "schemaVersion": 1,
  "documentId": "c1d2e3f4-...",
  "documentCode": "DOC-1007",
  "idempotencyKey": "sha256:9f2c...",
  "requestId": "a9b8c7d6-...",
  "attempt": 1,
  "broker": "kafka",
  "occurredAt": "2026-10-13T13:20:00.000Z",
  "payload": {}
}
```

O `traceparent` vai nos **headers** (Kafka headers / AMQP headers), não no corpo. O card "Último envelope" do frontend é montado a partir do envelope + `traceparent`.

### 5.4 Tipos de evento

| `eventType` | Quem publica | Payload |
|---|---|---|
| `documento.recebido` | API (outbox) | `{ fileKey, mimeType, fileName }` |
| `documento.progresso` | Worker | `{ stage: "OCR" \| "EXTRACTING" \| "RETRY_WAIT", attempt, error?, retryInMs? }` |
| `documento.extraido` | Worker | `ExtractionPayload` (abaixo) |
| `documento.retry` | Worker | igual a `documento.recebido`, com `attempt + 1` |
| (DLQ) | Worker | envelope original + headers `x-error`, `x-error-class`, `x-attempts` |

`ExtractionPayload` — todo valor monetário trafega como **string decimal com ponto** (`"283.80"`), nunca float:

```json
{
  "documentKind": "NF-e",
  "fields": {
    "cnpj":      { "value": "60.701.190/0001-04", "confidence": 0.95, "source": "ocr" },
    "issuer":    { "value": "Papelaria Ponto Final", "confidence": 0.96, "source": "llm" },
    "issueDate": { "value": "2026-09-28", "confidence": 0.95, "source": "llm" },
    "total":     { "value": "283.80", "confidence": 0.92, "source": "pdf_text" }
  },
  "items": [
    { "description": "Resma A4 75g", "quantity": "5", "unitPrice": "29.90" }
  ],
  "itemsConfidence": 0.89,
  "ocrEngine": "paddleocr",
  "llmModel": "<modelo>",
  "tokens": { "input": 1830, "output": 412 }
}
```

### 5.5 Retry e DLQ

| Situação | Kafka | RabbitMQ |
|---|---|---|
| Falha transitória (LLM 429/5xx, timeout, MinIO fora) | Worker publica em `documentos.recebidos-retry` com header `x-not-before`; o consumer de retry pausa a partição até o horário e republica em `documentos.recebidos` | Worker publica na fila `extracao.retry.{1s,2s,4s}` conforme a tentativa; ao expirar, a DLX devolve para `extracao.q` |
| Backoff | 1 s, 2 s, 4 s (`RETRY_BACKOFF_MS`) | idem |
| Erro permanente (PDF com senha, formato não suportado, arquivo corrompido) | Direto para `documentos.recebidos.DLT` | Direto para `extracao.dlq` |
| 4ª falha | DLT | `extracao.dlq` |
| Mensagem fora do schema (poison) | DLT na primeira leitura | `extracao.dlq` na primeira leitura |

Textos de erro que a tela DLQ mostra em "Último erro" vêm do header `x-error` (ex.: `LLM 503 Service Unavailable`, `timeout 30s no provedor LLM`, `429 rate limit excedido`).

O cliente HTTP do LLM no worker não faz retry próprio além de 1 tentativa curta. Retries empilhados se multiplicariam com os do broker.

### 5.6 Idempotência em três pontos

| Onde | Duplicata possível | Defesa |
|---|---|---|
| Upload | Mesmo arquivo enviado de novo | UNIQUE em `documents.idempotency_key` → 409 |
| Resultado | Worker publica, cai antes do ack e publica de novo | UNIQUE em `extractions.request_id` |
| Qualquer consumo no backend | Redelivery | `processed_events (event_id PK)` na mesma transação da mudança de estado + guarda de estado (§6.3) |

**Reprocessar** (`POST /reprocessar`) mantém a mesma `idempotency_key` e o mesmo `trace_id` (o trace do documento ganha novos spans), mas gera um **novo `requestId`**. Um resultado atrasado da tentativa antiga é descartado pela guarda de estado.

---

## 6. Backend Spring Boot

### 6.1 Tabelas (Flyway, `V1__init.sql`)

Nomes de tabela e coluna em inglês, `snake_case`. Datas como `VARCHAR(24)` em ISO 8601 UTC (`createdAt`/`updatedAt` como o template define). Dinheiro como `NUMERIC(14,2)` e `BigDecimal`.

| Tabela | Colunas principais | Restrições |
|---|---|---|
| `documents` | `id UUID`, `code VARCHAR(16)` (`DOC-1001`, sequence `document_code_seq` começando em 1001), `user_id`, `file_name`, `file_key`, `mime_type`, `idempotency_key VARCHAR(80)`, `broker`, `status`, `kind`, `trace_id CHAR(32)`, `current_request_id UUID`, `attempts INT`, `last_error`, `reason`, `confidence NUMERIC(5,4)`, `received_at`, `finished_at`, `created_at`, `updated_at` | UNIQUE `idempotency_key`; UNIQUE `code`; índice `(created_at DESC)`; índice `(status)` |
| `fiscal_data` | `document_id PK/FK`, `cnpj VARCHAR(18)`, `issuer`, `issue_date VARCHAR(10)`, `total NUMERIC(14,2)`, `items_sum NUMERIC(14,2)` | |
| `field_confidences` | `document_id`, `field` (`CNPJ`, `ISSUER`, `ISSUE_DATE`, `TOTAL`, `ITEMS`), `confidence NUMERIC(5,4)`, `source` | PK `(document_id, field)` |
| `document_items` | `id`, `document_id`, `line INT`, `description`, `quantity NUMERIC(12,3)`, `unit_price NUMERIC(14,2)` | |
| `extractions` | `id`, `document_id`, `request_id UUID`, `raw_result JSONB`, `ocr_engine`, `llm_model`, `input_tokens`, `output_tokens`, `created_at` | UNIQUE `request_id` |
| `validation_results` | `document_id`, `rule_code`, `passed BOOLEAN`, `detail`, `created_at` | Regravadas a cada validação |
| `reviews` | `id`, `document_id`, `reviewer_id`, `decision` (`APPROVED`/`REJECTED`), `corrections JSONB`, `reviewed_at` | Dataset para calibrar o limiar |
| `outbox_events` | `id`, `document_id`, `event_type`, `broker`, `destination`, `payload JSONB`, `traceparent`, `created_at`, `published_at` | Relay lê `published_at IS NULL` |
| `processed_events` | `event_id UUID PK`, `processed_at` | Dedup do consumidor |
| `pipeline_events` | `id`, `occurred_at`, `service`, `document_code`, `message`, `level` | Histórico do card "Eventos"; manter os últimos 500 |

> `CNPJ` é texto, não número: a Receita emite CNPJ alfanumérico desde julho de 2026.

### 6.2 Estados do documento e o que a tela mostra

`DocumentStatus` define tudo que o frontend exibe de um documento em andamento. O backend calcula `stage`, `progress` e `outcome` a partir dele — o frontend não deduz nada.

| `DocumentStatus` | `stage.label` | `stage.node` | `stage.service` | `progress` | `outcome` | `review` |
|---|---|---|---|---|---|---|
| `RECEIVED` | `Recebido` | `api` | `api` | 0.10 | null | null |
| `QUEUED` | `Na fila` | `broker` | `broker` | 0.20 | null | null |
| `OCR` | `OCR` | `worker` | `worker` | 0.45 | null | null |
| `EXTRACTING` | `Extração` | `worker` | `worker` | 0.65 | null | null |
| `RETRY_WAIT` | `Retry {n}/3` | `retry` | `broker` | 0.50 | null | null |
| `EXTRACTED` | `Validação` | `broker` | `broker` | 0.80 | null | null |
| `VALIDATING` | `Validação` | `validation` | `api` | 0.90 | null | null |
| `AUTO_APPROVED` | `Aprovado` | `validation` | `api` | 1 | `auto` | null |
| `PENDING_REVIEW` | `Em revisão` | `validation` | `api` | 1 | `review` | null |
| `APPROVED` | `Aprovado (revisão)` | `validation` | `api` | 1 | `review` | `approved` |
| `REJECTED` | `Rejeitado` | `validation` | `api` | 1 | `review` | `rejected` |
| `DEAD_LETTERED` | `DLQ` | `retry` | `broker` | 1 | `dlq` | null |

Transições permitidas (qualquer outra lança `IllegalStateTransitionException`, tratada no `@ControllerAdvice`; em listeners, a mensagem é descartada e logada):

| De | Para | Gatilho |
|---|---|---|
| — | `RECEIVED` | Upload aceito |
| `RECEIVED` | `QUEUED` | Relay publicou `documento.recebido` |
| `QUEUED`, `RETRY_WAIT` | `OCR` | `documento.progresso` stage `OCR` |
| `OCR` | `EXTRACTING` | `documento.progresso` stage `EXTRACTING` |
| `OCR`, `EXTRACTING` | `RETRY_WAIT` | `documento.progresso` stage `RETRY_WAIT` |
| `EXTRACTING` | `EXTRACTED` | `documento.extraido` recebido (antes de validar) |
| `EXTRACTED` | `VALIDATING` → `AUTO_APPROVED` / `PENDING_REVIEW` | Validação e score na mesma transação |
| `QUEUED`, `OCR`, `EXTRACTING`, `RETRY_WAIT` | `DEAD_LETTERED` | Mensagem lida da DLQ |
| `PENDING_REVIEW` | `APPROVED` / `REJECTED` | Decisão do revisor |
| `DEAD_LETTERED` | `QUEUED` | Reprocessamento |

### 6.3 Guarda de estado

Todo consumidor verifica, na mesma transação: (1) `event_id` ainda não está em `processed_events`; (2) o `requestId` da mensagem é o `current_request_id` do documento; (3) a transição é permitida. Falhou qualquer uma → descarta com log `debug` e dá ack.

### 6.4 Regras de validação (`service/validation/`)

Uma classe por regra, todas implementando:

```java
public interface DocumentRule {
    RuleCode code();
    ValidationResultDto validate(FiscalDataSnapshot data);
}
```

A ordem e os textos são os que a tela de Revisão mostra:

| `RuleCode` | `name` (exibido) | Regra | `detail` (exibido) |
|---|---|---|---|
| `CNPJ_CHECK_DIGIT` | `Dígito verificador do CNPJ` | Módulo 11, pesos `5,4,3,2,9,8,7,6,5,4,3,2` e `6,5,4,3,2,9,8,7,6,5,4,3,2`; resto < 2 → 0, senão 11 − resto. Aceita alfanumérico: cada caractere vale `ASCII − 48` | `DV 81 confere` · `DV calculado 81, lido 82` · `{n} caracteres, esperado 14` |
| `ITEMS_SUM_EQUALS_TOTAL` | `Soma dos itens = total` | `|soma(qtd × unit) − total| ≤ 0,01` | `R$ 271,30 vs R$ 283,80` |
| `ISSUE_DATE_NOT_FUTURE` | `Data não futura` | `issueDate ≤ hoje` (fuso `America/Sao_Paulo`) | `28/09/2026` |
| `REQUIRED_FIELDS_PRESENT` | `Campos obrigatórios` | cnpj, emitente, data, total e ao menos 1 item | `5 de 5 presentes` |

Valores em `R$` no `detail` usam o formato brasileiro (`R$ 1.234,56`), porque a tela exibe o texto como veio.

> O mock do frontend valida o CNPJ só com dígitos. O backend precisa aceitar o formato alfanumérico, e o teste deve incluir o exemplo oficial `12.ABC.345/01DE-35`.

### 6.5 Score e decisão (`service/scoring/`)

```text
score = cnpj·0.25 + total·0.25 + itens·0.20 + data·0.15 + emitente·0.15
```

- `ConfidenceScorer` calcula o score a partir de `field_confidences`.
- `ReviewDecider` decide, nesta ordem:
  1. Alguma regra falhou → `PENDING_REVIEW`, `reason = "Validação falhou: " + nomes separados por ", "`.
  2. `score < REVIEW_CONFIDENCE_THRESHOLD` → `PENDING_REVIEW`, `reason = "Confiança 78% abaixo do limiar 85%"`.
  3. Senão → `AUTO_APPROVED`, `reason = "Confiança 94% e validações ok"`.
- **Correção na revisão** (`PATCH`): o campo corrigido passa a ter confiança `max(atual, 0.99)` e `source = "review"`. As regras e o score são recalculados e devolvidos na hora, **sem mudar o status** (o documento continua `PENDING_REVIEW` até aprovar ou rejeitar).

### 6.6 Eventos para a tela (`service/event/`)

`PipelineEventPublisher` é o único ponto que emite eventos para fora. A cada mudança relevante ele grava em `pipeline_events`, envia ao SSE e, se ligado, ao simulador. As mensagens são as mesmas do mock, para a tela não mudar:

| Momento | `service` | `message` | `level` |
|---|---|---|---|
| Span relevante (publish/consume/ocr/llm/validate/score/update) | do span | nome do span (§9.2) | `ok` / `error` / `wait` |
| Upload duplicado | `api` | `409 duplicado · sha256:{8 hex} já processado` | `warn` |
| Aprovação automática | `api` | `documento.aprovado (automático)` | `ok` |
| Foi para revisão | `api` | `documento.em_revisao · {reason}` | `warn` |
| DLQ | `broker` | `DLQ após {n} tentativas · {erro}` | `error` |
| Revisor aprovou | `api` | `revisão humana · documento.aprovado publicado` | `ok` |
| Revisor rejeitou | `api` | `revisão humana · documento.rejeitado` | `warn` |
| Reprocessar | `api` | `POST /documentos/{code}/reprocessar · mesma idempotency_key` | `ok` |
| Troca de broker | `api` | `broker alterado para {label} · novos documentos usam {fila}` | `ok` |

`SimulatorForwarder` mantém um cliente WebSocket para `MESSAGING_SIMULATOR_URL`, envia cada evento como JSON e conta os espelhados. Se o simulador cair, tenta reconectar com backoff e não bloqueia o pipeline.

### 6.7 Tráfego de demonstração (perfil `demo`)

`DemoTrafficService` envia, a cada `DEMO_AUTO_TRAFFIC_INTERVAL_MS`, uma das amostras de `backend/src/main/resources/samples/` com sufixo no nome (`_0007`) e um byte de diferença no conteúdo, para gerar hash novo. Ligado/desligado por `PUT /api/v1/demo`. Fora do perfil `demo` o controller não existe.

---

## 7. API REST e stream de eventos

Prefixo `/api/v1`. As rotas de autenticação e perfil do template (`/api/auth/*`, `/api/profile*`, `/api/storage/presigned-url`) continuam iguais. **Todas as rotas abaixo são privadas (JWT)**. Datas em ISO 8601. Erros no formato do `@ControllerAdvice` do template.

| Método | Caminho | Resposta | Observação |
|---|---|---|---|
| POST | `/documentos` (multipart `file`) | **202** `DocumentResponse` · **409** `DuplicateDocumentResponse` · **413** · **415** | Aceita `image/*` e `application/pdf`, até `UPLOAD_MAX_SIZE_MB` |
| GET | `/documentos?limit=40` | `DocumentResponse[]` | Mais recentes primeiro; `limit` máx. 100 |
| GET | `/documentos/{id}` | `DocumentResponse` | |
| GET | `/documentos/{id}/arquivo` | `{ "url": "...", "expiresAt": "..." }` | URL pré-assinada do MinIO para o preview da Revisão |
| PATCH | `/documentos/{id}` | `DocumentResponse` | Body `{ "cnpj"?: string, "total"?: string }`; só em `PENDING_REVIEW`, senão 409 |
| POST | `/documentos/{id}/aprovar` | `DocumentResponse` | Só em `PENDING_REVIEW` |
| POST | `/documentos/{id}/rejeitar` | `DocumentResponse` | Só em `PENDING_REVIEW` |
| POST | `/documentos/{id}/reprocessar` | **202** `DocumentResponse` | Só em `DEAD_LETTERED` |
| GET | `/traces/{traceId}` | `TraceResponse` | Consulta o Jaeger (§9.3) |
| GET | `/broker` | `BrokerConfigResponse` | Broker ativo |
| PUT | `/broker` | `BrokerConfigResponse` | Body `{ "kind": "kafka" \| "rabbitmq" }` |
| GET | `/configuracao` | `{ "threshold": 0.85 }` | Limiar exibido na Revisão e no Pipeline |
| GET | `/simulador` · PUT `/simulador` | `{ "connected", "url", "mirroredEvents" }` | PUT body `{ "connected": boolean }` |
| GET | `/eventos` | `text/event-stream` | Ver §7.2 |
| PUT | `/demo` | `{ "autoTraffic": boolean }` | Só no perfil `demo` |

### 7.1 DTOs (o que o frontend recebe)

```ts
// Espelha frontend/src/api/modules/types.ts. Ajustes no frontend em §11.
type DocumentResponse = {
  id: string;                    // UUID
  code: string;                  // "DOC-1007"
  fileName: string;
  kind: string | null;           // "NF-e", "NFC-e", "NFS-e", "Recibo", "Cupom"
  issuer: string | null;
  issueDate: string | null;      // "2026-09-28"
  cnpj: string | null;
  total: string | null;          // "283.80"
  items: { description: string; quantity: string; unitPrice: string }[];
  itemsSum: string;              // "271.30"
  idempotencyKey: string;        // "sha256:9f2c…" (hash completo)
  traceId: string;               // 32 hex
  broker: 'kafka' | 'rabbitmq';
  status: DocumentStatus;        // §6.2
  stage: { label: string; node: PipelineNode; service: ServiceName };
  progress: number;              // 0..1
  outcome: 'auto' | 'review' | 'dlq' | null;
  review: 'approved' | 'rejected' | null;
  reason: string;
  attempts: number;
  lastError: string | null;
  confidence: number;            // 0..1
  fieldConfidence: Record<'cnpj' | 'issuer' | 'issueDate' | 'total' | 'items', number>;
  validations: { name: string; ok: boolean; detail: string }[];
  receivedAt: string;            // ISO 8601
  finishedAt: string | null;
  durationMs: number;            // finishedAt (ou agora) − receivedAt
};

type DuplicateDocumentResponse = {
  message: string;               // "idempotency_key sha256:9f2c… já existe"
  idempotencyKey: string;
  documentId: string;
  code: string;
};

type TraceResponse = {
  traceId: string;
  durationMs: number;
  spans: {
    service: 'api' | 'broker' | 'worker' | 'db';
    node: 'api' | 'broker' | 'retry' | 'worker' | 'validation';
    name: string;
    stage: string;
    startMs: number;             // relativo ao início do trace
    durationMs: number;
    status: 'ok' | 'error' | 'wait';
  }[];
};

type BrokerConfigResponse = {
  kind: 'kafka' | 'rabbitmq';
  label: string;                 // "Kafka" | "RabbitMQ"
  inputQueue: string;
  outputQueue: string;
  retryQueue: string;
  deadLetterQueue: string;
  consumerConfig: string;
  retryPolicy: string;
};
```

No Java, cada tipo é um `record` em `domain/dto` com MapStruct a partir das entidades. `BigDecimal` serializa como string (`@JsonFormat(shape = STRING)`).

### 7.2 Stream `GET /api/v1/eventos` (SSE)

`EventSource` não envia header `Authorization`. O endpoint aceita o JWT em `?access_token=` (só nesta rota) e o filtro JWT do template ganha esse caso.

Ao conectar, o servidor manda o estado inicial; depois, só mudanças:

| `event:` | `data:` | Quando |
|---|---|---|
| `snapshot` | `{ documents: DocumentResponse[40], events: PipelineEvent[80], broker, threshold, simulator }` | Na conexão |
| `document` | `DocumentResponse` | Toda mudança de um documento |
| `event` | `PipelineEvent` (`{ id, time: "HH:mm:ss", service, documentCode, message, level }`) | Cada evento da §6.6 |
| `envelope` | `EventEnvelope` (§5.3 resumido: `eventType, documentId, idempotencyKey, attempt, traceparent, broker`) | Cada publish/consume |
| `broker` | `BrokerConfigResponse` | Troca de broker |
| `simulator` | `{ connected, url, mirroredEvents }` | Mudança no espelhamento (no máx. 1 por segundo) |

Heartbeat (`: ping`) a cada 15 s. Implementar com `SseEmitter` e um registro de emissores em memória (uma instância). Para mais de uma instância seria preciso um pub/sub (fora do escopo).

---

## 8. Worker Python

### 8.1 Pipeline de extração

1. Consome a mensagem (ack manual), valida o envelope com Pydantic. Inválida → DLQ.
2. Restaura o contexto OTel do `traceparent` e publica `documento.progresso` (`OCR`).
3. Baixa o arquivo do MinIO (credencial só de leitura).
4. **PDF com texto:** PyMuPDF. **Imagem ou PDF escaneado:** pré-processamento OpenCV (deskew, binarização) + PaddleOCR. **NFC-e:** lê o QR Code com pyzbar.
5. Publica `documento.progresso` (`EXTRACTING`) e chama o LLM com o texto do OCR e o JSON Schema de saída. CPF de pessoa física é mascarado antes da chamada (§14).
6. Calcula a confiança por campo (tabela abaixo) e publica `documento.extraido`. Só depois dá ack.

### 8.2 Confiança por campo

O LLM não dá probabilidade calibrada, então o worker combina sinais objetivos:

| Origem do valor | Confiança |
|---|---|
| QR Code da NFC-e | 1.0 |
| Camada de texto do PDF, casando com regex do campo | 0.95 |
| Valor do LLM que aparece literalmente no texto do OCR (*grounding*) | média da confiança das palavras do PaddleOCR |
| Valor do LLM que **não** aparece no texto do OCR | 0.3 (provável alucinação) |

`itemsConfidence` = média das linhas de item, cada uma pelo mesmo critério.

### 8.3 Classificação de erro

| Classe | Exemplos | Destino |
|---|---|---|
| `TransientError` | LLM 429/5xx, timeout, MinIO indisponível | Retry (§5.5) |
| `PermanentError` | PDF protegido, MIME não suportado, imagem ilegível, resposta do LLM fora do schema após 1 nova tentativa | DLQ direto |

### 8.4 Scripts do worker

| Template (Node) | Worker |
|---|---|
| `npm run lint` | `uv run ruff check` |
| `npm run format` / `format:check` | `uv run ruff format` / `uv run ruff format --check` |
| (tipos) | `uv run mypy src` |
| testes | `uv run pytest` |

---

## 9. Observabilidade (OpenTelemetry)

### 9.1 Instrumentação

- **API:** OpenTelemetry Java agent (`-javaagent`) + spans manuais com `@WithSpan`/`Tracer`. `service.name = fiscal-api`.
- **Worker:** `opentelemetry-sdk` + instrumentação de aiokafka/aio-pika quando disponível, senão propagação manual do `traceparent`. `service.name = fiscal-worker`.
- **Outbox:** o relay publica fora do request, então `outbox_events.traceparent` é gravado no upload e restaurado como contexto pai antes de publicar. Sem isso o trace se parte em dois.
- Logs em JSON com `trace_id` e `span_id` (MDC no Java, `LoggingInstrumentor` no Python).

### 9.2 Nomes de span que a tela espera

O waterfall da tela de Traces mostra o nome do span como veio. Para ficar igual ao mock, os spans manuais usam estes nomes e todos levam os atributos `pipeline.node` e `pipeline.stage`:

| Ordem | Serviço | Nome do span | `pipeline.node` | `pipeline.stage` |
|---|---|---|---|---|
| 1 | api | `POST /api/v1/documentos` (automático) | `api` | `Recebido` |
| 2 | db | `INSERT documento ON CONFLICT (idempotency_key)` | `api` | `Recebido` |
| 3 | broker | `publish {fila de entrada}` | `broker` | `Na fila` |
| 4 | worker | `consume {fila de entrada}` (`· tentativa {n}` a partir da 2ª) | `worker` | `OCR` |
| 5 | worker | `ocr.paddleocr` | `worker` | `OCR` |
| 6 | worker | `llm.extract (json schema)` · em erro: `llm.extract → {erro}` | `worker` | `Extração` |
| 6a | broker | `retry +{n}s → {fila de retry}` (status `wait`) | `retry` | `Retry {n}/3` |
| 6b | broker | `publish {DLQ}` (status `error`) | `retry` | `DLQ` |
| 7 | broker | `publish {fila de saída}` | `broker` | `Validação` |
| 8 | api | `consume {fila de saída}` | `validation` | `Validação` |
| 9 | api | `validate cnpj.dv + soma.itens` | `validation` | `Validação` |
| 10 | api | `score.confianca` | `validation` | `Score` |
| 11 | db | `UPDATE documento SET status` | `validation` | `Score` |

O span de espera de retry (6a) é criado pelo worker na tentativa seguinte, com `start_time` = momento da falha e `end_time` = momento do novo consumo, e o atributo `pipeline.span.status = wait`.

### 9.3 `GET /api/v1/traces/{traceId}`

`TraceService` consulta `JAEGER_QUERY_URL/api/traces/{traceId}` e mapeia cada span:

- `service`: `fiscal-api` → `api`; `fiscal-worker` → `worker`; span com atributo `db.system` → `db`; span com `messaging.system` → `broker`.
- `node` / `stage`: atributos `pipeline.node` / `pipeline.stage` (spans automáticos sem atributo herdam do pai).
- `status`: `error` se o status OTel for ERROR; `wait` se `pipeline.span.status = wait`; senão `ok`.
- `startMs`: relativo ao primeiro span; ordenado por início. Spans internos sem `pipeline.node` (HTTP clients, pool de conexão) são filtrados.

---

## 10. Testes

Os nomes seguem o card "Testes de integração" da tela Plano (em inglês, pela regra do template).

| Teste | Tipo | O que prova |
|---|---|---|
| `DocumentUploadIT` | Java · `PostgreSQLContainer` + `KafkaContainer` + MinIO | Upload grava documento, outbox e mensagem na fila |
| `IdempotencyIT` | Java | Mesmo hash responde 409 e não cria documento; resultado duplicado não gera 2 extrações |
| `RetryBackoffIT` | Java + worker fake | 3 falhas transitórias levam ao DLT/DLQ; a guarda descarta resultado atrasado após reprocessar |
| `CnpjValidatorTest` | Java · unitário | Numérico válido e inválido, alfanumérico `12.ABC.345/01DE-35`, tamanho errado |
| `ItemsSumValidatorTest` | Java · unitário | Tolerância de R$ 0,01 e arredondamento |
| `test_worker_extraction.py` | Python · testcontainers `KafkaContainer` / `RabbitMqContainer` | Extração com LLM fake; erro transitório percorre o retry; erro permanente vai direto à DLQ |
| `TracePropagationIT` | Java + Python | O `traceparent` sai da API, passa pelo worker e volta no mesmo `trace_id` |

Também:

- **Contrato:** Java (`json-schema-validator`) e Python (Pydantic) validam `contracts/examples/*.json` contra `contracts/events/*.schema.json`. Mudou o contrato, os dois lados quebram juntos.
- **Os dois brokers:** os ITs de mensageria rodam parametrizados para Kafka e RabbitMQ.
- **Unitários (Mockito):** `ReviewDecider`, `ConfidenceScorer`, transições proibidas de `PipelineStateService`.
- **Avaliação da extração:** `worker/scripts/evaluate.py` roda o conjunto rotulado e mede acerto por campo. Não entra no CI (usa o LLM real).

Como o template, nada de E2E, Playwright ou snapshots. No GitHub Actions, os ITs precisam de Docker (o runner Ubuntu já tem).

---

## 11. Mudanças no frontend para o modo live

O frontend continua com o modo mock. Estas mudanças entram na fase F5:

1. **Seleção de modo:** `VITE_API_MODE=mock | live`. Cada service de `src/api/modules/` escolhe entre o `mockEngine` e a implementação real; as páginas não mudam.
2. **Fonte live do pipeline:** `GET /documentos` + `EventSource('/api/v1/eventos?access_token=…')`, aplicando os eventos `snapshot`, `document`, `event`, `envelope`, `broker` e `simulator` sobre o mesmo `PipelineSnapshot`.
3. **Tipos** (`types.ts`):
   - `PipelineDocument.id` passa de `number` para `string` (UUID).
   - `elapsedMs` sai; entra `progress` (barra de Documentos) e `durationMs` vem pronto.
   - `spans` sai do documento; a tela de Traces chama `GET /traces/{traceId}`.
   - Valores monetários chegam como `"283.80"`; um mapper converte para o formato que as telas já usam (`total` com vírgula, `itemsSum` numérico).
4. **Login:** trazer a `LoginPage` do template e proteger as rotas; o `api.ts` já tem `authStorage`.
5. **Revisão:** o preview listrado vira `<img>`/`<iframe>` com a URL de `GET /documentos/{id}/arquivo`.
6. **Sidebar:** no modo live, "Pausar" e "1×" somem (só fazem sentido na simulação); "Tráfego automático" chama `PUT /demo` e só aparece se o backend estiver no perfil `demo`.
7. **Amostras:** os botões "Enviar" da tela Upload mandam arquivos reais de `frontend/public/samples/` (os mesmos de `backend/src/main/resources/samples/`).
8. **Limiar:** vem de `GET /configuracao`, não mais do `.env` do frontend.
9. **Plano e testes:** continuam no `localStorage`. Persistir no backend é opcional e fica fora deste escopo.

---

## 12. Ordem de implementação

As fases são as mesmas da tela "Plano e tarefas" do frontend. Cada uma termina com um PR para `develop` (Conventional Commits, ex.: `feat(worker): add paddle ocr extraction`).

| Fase | Entregas | Pronto quando |
|---|---|---|
| **F1 · Fundação** | `docker-compose.dev.yml` com Postgres, Kafka, RabbitMQ, MinIO e Jaeger; backend do template (auth, storage) com pacote `com.bimd.fiscalpipeline`; Flyway `V1__init.sql`; `POST /documentos` com upload ao MinIO, SHA-256 e UNIQUE; `ARCHITECTURE.md` | Upload real devolve 202 e o reenvio devolve 409 |
| **F2 · Worker Python** | Consumers com ack manual (aiokafka / aio-pika); PyMuPDF, PaddleOCR + OpenCV, pyzbar; LLM com JSON Schema; publicar `documento.extraido` com confiança por campo e `documento.progresso` | Uma NFC-e real vira `ExtractionPayload` válido no tópico de saída |
| **F3 · Confiabilidade** | Retry 1 s · 2 s · 4 s e DLQ nos dois brokers; endpoint de reprocessamento; outbox transacional; `MessagingPort` com adapters Kafka e RabbitMQ; `PUT /broker` | `RetryBackoffIT` e `IdempotencyIT` passando nos dois brokers |
| **F4 · Validação e decisão** | As 4 `DocumentRule`; `ConfidenceScorer` + `ReviewDecider` com limiar configurável; estados `PENDING_REVIEW` / `AUTO_APPROVED`; `PATCH`, `aprovar`, `rejeitar` | Validação ponta a ponta com as 6 amostras dando o resultado do cenário |
| **F5 · Revisão e observabilidade** | Frontend em modo live (§11) com login; SSE `/eventos`; Java agent + SDK Python com `traceparent` nos headers; nomes de span da §9.2; `GET /traces/{id}` | A tela de Traces mostra um documento real em um só trace |
| **F6 · Testes e integração** | Testcontainers nos ITs Java e no worker; `TracePropagationIT`; `SimulatorForwarder`; perfil `demo`; README com diagrama e demo gravada | Checklist da §13 verde e demo gravada |

---

## 13. Checklist de validação

Antes de abrir um PR, como no template, mais as linhas do worker:

```sh
# Backend Java
cd backend && ./mvnw verify

# Worker Python
cd worker && uv run ruff check && uv run ruff format --check && uv run mypy src && uv run pytest

# Frontend
cd frontend && npm run lint && npm run format:check && npm run build
```

---

## 14. Pontos em aberto

| Item | Por que importa | Encaminhamento sugerido |
|---|---|---|
| Provedor de LLM | Recibos podem conter CPF e nome de pessoa física (LGPD) e o custo varia muito | **Decidido: Google Gemini.** Mascarar CPF antes da chamada (extrair por regex localmente); registrado no `ARCHITECTURE.md` |
| Datas como texto no banco | O README manda ISO string; `timestamptz` seria mais seguro para filtros e fuso | Seguir o README. Se mudar, mudar no template para todos os projetos, não só aqui |
| Formato de mensagem do simulador | O `SimulatorForwarder` precisa do formato que o simulador de mensageria espera em `ws://localhost:8090/events` | Conferir no repositório do simulador antes da F6 |
| Amostras sintéticas | A tela Upload e o perfil `demo` precisam de arquivos reais das 6 amostras | Gerar PDFs/imagens sintéticos (sem dados reais) na F2 |
| Conjunto rotulado | Sem ele não há como calibrar o limiar de 0.85 | Juntar 50 a 100 notas próprias, anonimizadas |
| Validação extra da chave de acesso | DV da chave de 44 dígitos e coerência com CNPJ/data | Opcional: entra como 5ª `DocumentRule` e aparece sozinha na tela, que lista o que vier |
