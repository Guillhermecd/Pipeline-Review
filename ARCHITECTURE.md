# Arquitetura — Pipeline Fiscal

Pipeline de extração de documentos fiscais: o usuário envia uma nota (PDF ou imagem), um worker extrai os campos com OCR e LLM, e a API valida, pontua e decide entre aprovação automática e revisão humana.

A especificação completa está em [BACKEND.md](BACKEND.md). Este arquivo registra as decisões e os desvios em relação ao BIMD Template.

## Decisões de arquitetura

| Decisão | Escolha | Justificativa |
|---|---|---|
| Backend | **Java 21 / Spring Boot 3** (Maven, `./mvnw`) | Regras de negócio complexas (validações, score, máquina de estados) e orquestração transacional |
| Banco | **PostgreSQL** + `spring-boot-starter-data-jpa` + Hibernate, migrações com **Flyway** | Relações documento → itens → validações; transações ACID para o outbox; `ddl-auto=validate` do template |
| Worker | **Python 3.12** com `uv` | Ecossistema de OCR e LLM |
| OCR | **PaddleOCR** + pré-processamento OpenCV; **PyMuPDF** para PDF com camada de texto; **pyzbar** para QR Code de NFC-e | PaddleOCR lida melhor com cupom térmico e foto torta |
| LLM | **Google Gemini**, saída em JSON Schema | Estrutura itens e campos a partir do texto do OCR. CPF de pessoa física é mascarado antes da chamada (LGPD) |
| Broker | **Kafka e RabbitMQ intercambiáveis** atrás de uma abstração (`MessagingPort`) | Requisito do design e diferencial do simulador de mensageria |
| Retry | 3 retries com backoff **1 s · 2 s · 4 s**; a 4ª falha vai para a DLQ | Valores mostrados na tela DLQ |
| Idempotência | `idempotency_key = SHA-256 do arquivo`, UNIQUE no banco; reenvio responde **409** | Tela de Upload |
| Score | Média ponderada da confiança por campo: CNPJ .25, total .25, itens .20, data .15, emitente .15 | Mesmos pesos do mock |
| Decisão | Validação falhou → revisão; score < limiar → revisão; senão aprovação automática. Limiar padrão **0.85**, configurável | Tela de Revisão |
| Datas | **String ISO 8601** (`2026-10-13T13:20:00.000Z`) no tráfego **e no banco** | Regra do template |
| Observabilidade | OpenTelemetry (Java agent + SDK Python), `traceparent` nos headers das mensagens, Jaeger | Tela de Traces |

## Acréscimos ao template

| Acréscimo | Onde | Por quê |
|---|---|---|
| `worker/` e `contracts/` | raiz | Serviço Python e contrato compartilhado das mensagens |
| `messaging/` | backend | Mensagens são uma porta de entrada como HTTP; isolar evita que services dependam do broker |
| `domain/enums/` | backend | Estados e tipos fechados usados por entidade, DTO e mensagens |
| `api/mock/` e `utils/` | frontend | Modo mock e formatação |
| Perfil `demo` | backend | Tráfego automático e amostras só existem em demonstração |
| Serviço do worker no deploy | infra | O deploy conhece só `frontend/` e `backend/`; o worker precisa de um serviço próprio, sem rota no Nginx |

## Desvios e observações

- **Tabela `users` com `TIMESTAMPTZ`.** O código de autenticação veio do template e usa `Instant`. As tabelas do pipeline seguem a regra de data como texto ISO 8601. A divergência fica restrita ao que é do template.
- **Imagem do MinIO.** A MinIO deixou de publicar `minio/minio` no Docker Hub. O compose e os testes usam `cgr.dev/chainguard/minio`, que distribui o mesmo servidor.
- **Portas locais deslocadas.** O compose publica Postgres em `5442`, MinIO em `9050`/`9051`, Mailpit em `1035`/`8035` e RabbitMQ em `5673`/`15673`, para conviver com outros projetos na mesma máquina. Dentro da rede do compose as portas são as padrão.
- **Testcontainers 1.21.4.** Versão fixada no `pom.xml`: o Docker Engine 29 exige API ≥ 1.44, que as versões gerenciadas pelo Spring Boot 3.3 não negociam.
- **JWT por query string só no SSE.** O filtro aceita `?access_token=` apenas em `/api/v1/eventos`, porque `EventSource` não envia `Authorization`. Nas demais rotas o token só vale no header.
- **Testes de integração com `*IT`.** Rodam no `maven-failsafe-plugin` (fase `verify`) com Testcontainers; precisam de Docker.

## Upload e idempotência

1. A API calcula o SHA-256 do arquivo. Se já existe documento com essa chave, responde 409 e nada é criado.
2. O arquivo é gravado no MinIO em `documents/{sha256}/{nome}` **antes** da transação. Um reenvio sobrescreve a mesma chave; já um documento gravado sem arquivo não teria como ser recuperado.
3. Em uma única transação: documento (`RECEIVED`), linha em `outbox_events` com o envelope completo e o evento de log.
4. Dois envios simultâneos do mesmo arquivo passam pela checagem do passo 1. Quem decide é a constraint `UNIQUE (idempotency_key)`: o perdedor recebe 409.
