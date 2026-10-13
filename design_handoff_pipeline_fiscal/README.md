# Handoff: Pipeline de Extração de Documentos Fiscais — Área de execução

## Overview
Aplicação web (React + TypeScript) que serve de "área de execução" do projeto **Pipeline de Extração de Documentos Fiscais** (event-driven, Java + Python). Junta duas coisas numa interface só:

1. **Planejamento**: roadmap em fases com tarefas marcáveis, um checklist dos requisitos de nível pleno e a suíte de testes de integração (Testcontainers).
2. **Produto**: upload de notas e recibos, pipeline ao vivo entre os serviços, fila de revisão humana, trace OpenTelemetry ponta a ponta e uma tela de retries/DLQ.

Contexto do sistema real que a UI representa:
- **Spring Boot (Java 21)**: API de upload e orquestração, validações e score.
- **Worker Python**: OCR (PaddleOCR) e extração via LLM com JSON Schema.
- **Broker**: Kafka **ou** RabbitMQ, alternável.
- **PostgreSQL**: persistência e idempotency key.
- **OpenTelemetry**: traceparent propagado entre Java e Python.
- **Simulador de mensageria do usuário**: app web que visualiza filas e consumidores; a UI espelha os eventos para ele.

## About the Design Files
Os arquivos deste pacote são **referências de design feitas em HTML**: protótipos que mostram a aparência e o comportamento pretendidos, não código de produção para copiar. A tarefa é **recriar este design no frontend do projeto** (React/TS, como diz a stack) com os padrões e bibliotecas desse ambiente. Se ainda não existe frontend, use **Vite + React + TypeScript**.

No protótipo, toda a simulação (eventos, retries, traces) roda no navegador. Na implementação real, esses dados vêm da API Spring Boot, por REST e por WebSocket/SSE (ver "State Management").

## Fidelity
**High-fidelity.** Cores, tipografia, espaçamentos e interações são finais. Recrie fiel ao protótipo.

## Layout geral
- O app ocupa o viewport inteiro (`position:fixed; inset:0`). Não há scroll da página; só a área de conteúdo (`main`) rola.
- **Sidebar fixa à esquerda** (é o único layout; o modo de abas no topo do protótipo, `layout="topbar"`, foi descartado e **não deve ser implementado**).
  - Largura 232px, fundo `#1B1B18`, padding `22px 14px 18px`, coluna com gap 26px.
  - **Topo**: eyebrow "PROJETO 05" (JetBrains Mono 11px, `#E2572B`, letter-spacing .08em), título "Pipeline Fiscal" (18px/600), subtítulo "Java + Python · event-driven" (12px, `#A9A49A`). À direita, um **botão de tema** 32×32 (borda `1px #45443E`, radius 7, hover `#2A2925`) com ícone de lua no tema claro e de sol no escuro (ícones stroke de 16px).
  - **Navegação**: grupos "EXECUÇÃO" (Plano e tarefas) e "PRODUTO" (Upload, Pipeline ao vivo, Revisão, Traces, DLQ). Rótulos de grupo em JetBrains Mono 10.5px `#8C877D`. Item: padding `9px 10px`, radius 7. Ativo: fundo `#34332E`, texto `#FFFFFF`. Inativo: texto `#C9C4B9`. Badge de contagem à direita (mono 11px; `#E2572B` quando ativo), mostrado em Revisão (pendentes) e DLQ (mensagens).
  - **Rodapé** (separado por borda `#34332E`): seletor de broker Kafka/RabbitMQ (segmented sobre `#2A2925`; a opção ativa usa fundo `#F3F1EC` e texto `#1B1B18`), botão Pausar/Retomar, botão de velocidade 1×/2×/4× e um toggle "Tráfego automático ligado/desligado" com um ponto de status.
- **Main**: `flex:1; overflow:auto`, padding `clamp(16px,2.2vw,28px) clamp(16px,2.5vw,32px) 40px`.
- **Responsivo**: todos os grids usam `auto-fit/minmax` e empilham em larguras menores. Nada pode gerar scroll horizontal a partir de 900px de largura.

## Padrões visuais
- **Cabeçalho de tela**: eyebrow mono 11px muted + H1 28px/600, letter-spacing -.02em + descrição opcional (muted, max-width 640px, `text-wrap:pretty`).
- **Card**: fundo `panel`, borda `1px line`, radius 10px, padding 14–20px.
- **Card escuro** (Requisitos, Último envelope): fundo `#1B1B18` nos dois temas; eyebrow em `#E2572B`.
- **Botão primário**: fundo `btn`, texto `btnfg`, radius 6px, padding `7px 12px`. **Botão de destaque** (Enviar todos): `#E2572B` com texto branco. **Botão outline**: borda `1px ink`, fundo transparente.
- **Tag/chip**: JetBrains Mono 10.5–11px, padding `2px 7px`, radius 4px, fundo `bg`.
- **Barras de progresso**: altura 4–6px, trilho `line2`, radius 2–3px.
- Linhas de lista separadas por `border-top: 1px line2`.

## Screens / Views

### 1. Plano e tarefas
- **Header**: "PLANO DE EXECUÇÃO" / "Roadmap, requisitos e testes" / "Marque as tarefas conforme avança. Cada requisito de nível pleno acompanha as tarefas ligadas a ele." À direita fica o progresso geral `X/Y · %` com uma barra de 6px em `#E2572B` (largura 260px).
- **Grid**: `repeat(auto-fit,minmax(380px,1fr))`.
  - **Coluna esquerda, cards de fase**: id da fase (mono 12px `#E2572B`), título 15px/600 e contador `done/total` com uma mini-barra de 80px em verde. Cada tarefa tem checkbox 16×16 (radius 4, borda 1.5px `line3`; marcado: fundo e borda `ok` com ✓ branco), texto e uma tag de requisito. Tarefa concluída: texto muted e riscado.
  - **Coluna direita**:
    - Card escuro **"REQUISITOS DE NÍVEL PLENO"**: 6 itens com ponto de status (verde `#6FBF8A` = todas as tarefas ligadas concluídas; âmbar `#E0B060` = parcial; `#55534C` = nenhuma) e o texto "x/y tarefas" ou "concluído · x/y tarefas".
    - Card **"Testes de integração"**: subtítulo "Testcontainers · N testes"; após rodar, "k/N passaram". Botão "Rodar suíte". Cada linha tem o nome (mono 12.5px), a descrição e um status: "não executado", "na fila", "rodando…" (fundo `warnbg`, texto `warn`) ou "passou 2,4s" (fundo `okbg`, texto `ok`).

**Fases e tarefas (texto exato, com a tag do requisito):**
- **F1 Fundação**: docker-compose com Postgres, Kafka, RabbitMQ, MinIO e Jaeger (base) · Spring Boot: POST /api/v1/documentos com upload para MinIO (base) · Schema Postgres + migrações Flyway (base) · Idempotency key = SHA-256 do arquivo com UNIQUE constraint (eventos)
- **F2 Worker Python**: Consumer com ack manual (aiokafka / aio-pika) (eventos) · OCR com PaddleOCR + pré-processamento de imagem (base) · Extração via LLM com saída em JSON Schema (base) · Publicar documento.extraido com confiança por campo (score)
- **F3 Confiabilidade**: Retry com backoff exponencial 1s · 2s · 4s (eventos) · Dead-letter queue + endpoint de reprocessamento (eventos) · Transactional outbox no Spring Boot (eventos) · Abstração de broker: Kafka e RabbitMQ intercambiáveis (simulador)
- **F4 Validação e decisão**: Dígito verificador do CNPJ (validacao) · Soma dos itens igual ao total (tolerância R$ 0,01) (validacao) · Score de confiança ponderado + limiar configurável (score) · Fila de revisão humana (status EM_REVISAO) (score)
- **F5 Revisão e observabilidade**: Tela de revisão React/TS com edição de campos (score) · OpenTelemetry Java agent na API (otel) · OTel SDK Python + propagação do traceparent nos headers (otel) · Dashboards Jaeger/Grafana por documento (otel)
- **F6 Testes e integração**: Testcontainers: PostgreSQL + Kafka nos ITs Java (testes) · testcontainers-python no worker (testes) · Teste de propagação de trace Java → Python (testes) · Espelhar eventos no simulador de mensageria (simulador) · README com diagrama e demo gravada (base)

**Requisitos (tag → texto):**
- eventos → "A arquitetura é orientada a eventos, com idempotência, retry com backoff e dead-letter queue."
- validacao → "Os dados extraídos passam por validações determinísticas, como dígito verificador do CNPJ e soma dos itens igual ao total."
- score → "Um score de confiança decide se o documento é aprovado automaticamente ou vai para a fila de revisão."
- otel → "Há observabilidade com OpenTelemetry, rastreando um documento de ponta a ponta entre os serviços."
- testes → "Os testes de integração usam Testcontainers."
- simulador → "Diferencial: conversa diretamente com o seu simulador de mensageria."

**Testes**: DocumentoUploadIT (Java · PostgreSQLContainer + KafkaContainer), IdempotenciaIT (Java · mesmo hash não cria novo documento), RetryBackoffIT (Java · 3 falhas levam a mensagem ao DLT), CnpjValidatorTest (Java · unitário, casos válidos e inválidos), SomaItensValidatorTest (Java · unitário, tolerância de centavos), test_worker_extracao.py (Python · testcontainers KafkaContainer), TracePropagationIT (Java + Python · traceparent preservado).

### 2. Upload
- **Header**: "POST /api/v1/documentos" / "Upload de documentos" / "A API calcula o SHA-256 do arquivo como chave de idempotência, grava o documento e publica o evento no broker. Reenviar o mesmo arquivo não cria um novo processamento."
- **Dropzone**: altura 150px, borda tracejada `1.5px line3`, radius 12. Texto "Solte fotos ou PDFs de notas fiscais e recibos" e "ou clique para escolher · JPG, PNG, PDF". Aceita `image/*,application/pdf`, múltiplos arquivos.
- **Barra de ações**: "Documentos de exemplo" + botão "Testar idempotência" (reenvia o mesmo arquivo) + "Enviar todos".
- **Grid de exemplos** `auto-fill minmax(250px,1fr)`. Cada card mostra tipo (tag), cenário (verde para "Deve aprovar", âmbar para os demais), emitente, nome do arquivo (mono), total e botão "Enviar".
- **Log de envios**: hora · arquivo + detalhe (`DOC-1007 · sha256:…`) · resultado. Os resultados possíveis são "Aceito · 202" (verde) e "Duplicado ignorado" (âmbar, com o detalhe "idempotency_key sha256:… já existe", que corresponde a um HTTP 409).

### 3. Pipeline ao vivo (tela inicial)
- **Header**: "AO VIVO · {Kafka|RabbitMQ}" / "Pipeline de extração". À direita, a pílula do **Simulador de mensageria**: ponto de status, endpoint `ws://localhost:8090/events · N eventos espelhados` e o botão Conectar/Desconectar.
- **Métricas**: 5 colunas fixas (`repeat(5,minmax(0,1fr))`, separadas por 1px de `line`): Processados, Aprovação automática (%), Em revisão, Na DLQ, Latência p50. Valores em mono `clamp(16px,1.8vw,22px)`.
- **Fluxo**: grid `auto-fit minmax(180px,1fr)`.
  - 4 nós, cada um com o passo em mono, o título, o subtítulo, a contagem atual (mono 26px) e chips com os ids dos documentos naquele estágio. A borda do nó fica 1.5px na cor do serviço quando a contagem é maior que zero.
    - `01 · JAVA` API Spring Boot: "upload, idempotência, outbox"
    - `02 · KAFKA|RABBITMQ` Broker: "{fila de entrada} · retry". Os chips em retry têm o sufixo ↻ e fundo `warnbg`.
    - `03 · PYTHON` Worker de extração: "OCR + LLM com JSON Schema"
    - `04 · JAVA` Validação e score: "DV do CNPJ, soma dos itens, confiança"
  - Coluna de **resultados** com 3 botões empilhados: Aprovado automático (`ok`), Revisão humana (`warn`) e Dead-letter (`err`). Cada um tem uma faixa interna de 3px à esquerda na cor do resultado e navega para Traces, Revisão ou DLQ.
- **Abaixo**: grid `auto-fit minmax(340px,1fr)`.
  - **Documentos**: as 8 linhas mais recentes, com código, arquivo, estágio colorido e barra de progresso. Clicar abre o trace do documento.
  - **Broker**: nome, grupo/config (Kafka: "consumer group extrator-py · 3 partições"; RabbitMQ: "exchange documentos · prefetch 10") e profundidade de cada fila: entrada, retry, saída e DLQ (em vermelho quando > 0).
  - **ÚLTIMO ENVELOPE** (card escuro): JSON pré-formatado com `eventType`, `documentId`, `idempotencyKey`, `attempt`, `traceparent` (`00-{traceId}-{spanId}-01`) e `broker`.
- **Eventos**: as 10 linhas mais recentes, com hora · serviço (na cor do serviço) · documento · mensagem. Erro em `err`; aviso ou espera em `warn`.

**Nomes de filas por broker**
- Kafka: `documentos.recebidos`, `documentos.extraidos`, `documentos.recebidos-retry`, `documentos.recebidos.DLT`
- RabbitMQ: `extracao.q`, `validacao.q`, `extracao.retry (TTL)`, `extracao.dlq`

### 4. Revisão
- **Header**: "LIMIAR DE CONFIANÇA 85%" / "Fila de revisão".
- **Grid**: `auto-fit minmax(min(100%,440px),1fr)`.
  - **Lista**: os pendentes primeiro, depois os já revisados (com opacidade .6). Cada item mostra código, tipo, confiança (colorida), emitente e motivo. O item selecionado tem fundo `soft` e uma faixa interna de 3px em `#E2572B`.
  - **Detalhe**:
    - Cabeçalho com código, tipo, status, emitente (19px/600), motivo em `err` e confiança grande (mono 30px).
    - Preview do documento (170px, altura 220, fundo listrado; trocar pela imagem real) e o botão "trace {id curto}…".
    - Campos: CNPJ, Emitente, Emissão, Total (R$) e Itens, cada um com uma barra de confiança. **CNPJ e Total são editáveis**; editar recalcula as validações na hora e eleva a confiança do campo para ≥ 99%.
    - 4 cards de validação (✓ com fundo `okbg` / ✕ com fundo `errbg`) com detalhe em mono.
    - Tabela de itens (Item, Qtd, Unit., Subtotal) com "soma dos itens" no rodapé.
    - Ações: "Rejeitar" (outline, texto `err`) e "Aprovar e publicar" (primário). Depois da ação, a seleção passa ao próximo pendente.
- Cor da confiança: `ok` se ≥ limiar, `warn` se ≥ limiar − 10pp, `err` abaixo disso.

### 5. Traces
- **Header**: "OPENTELEMETRY · W3C TRACEPARENT" / "Trace ponta a ponta".
- **Grid**: `auto-fit minmax(min(100%,460px),1fr)`.
  - **Lista**: os 16 documentos mais recentes, com ponto de status, código e duração (com "…" quando ainda está em andamento).
  - **Detalhe**: "{código} · {arquivo}", `trace_id`, e os números de duração, spans e resultado. Abaixo, a legenda dos serviços e o **waterfall** com colunas `84px minmax(0,150px) minmax(0,1fr) 48px` (serviço, span, barra, duração) e marcas de tempo em 0, 50% e 100%.
  - Barra posicionada por `left = start/total`, `width = dur/total` (mínimo 0.4%). Spans com erro em `err`; espera de retry em `waitbar`. Span em andamento com opacidade .55.

### 6. DLQ
- **Header**: "{nome da DLQ} · {broker}" / "Retries e dead-letter queue".
- **Card de política**: a sequência em chips `tentativa 1 → +1s → tentativa 2 → +2s → tentativa 3 → +4s → tentativa 4 → {DLQ}` e um texto que muda com o broker:
  - Kafka: "Retry topics não bloqueantes (@RetryableTopic no Spring, retry topic no consumer Python) com backoff exponencial. Após a 3ª falha a mensagem vai para o DLT com os headers da exceção e o traceparent original, e pode ser reprocessada pela API."
  - RabbitMQ: "Fila de espera com TTL por tentativa e dead-letter-exchange apontando de volta para extracao.q. O header x-death conta as tentativas; após a 3ª falha a mensagem é roteada para extracao.dlq e pode ser reprocessada pela API."
- **Tabela**: Documento, Arquivo, Último erro (mono, `err`), Tentativas e botão "Reprocessar". Quando vazia: "Nenhuma mensagem na DLQ. Aumente a taxa de falha nos Tweaks para forçar retries."

## Interactions & Behavior
- **Navegação** pela sidebar (estado `screen`; o padrão é `pipe`). Na implementação real, use rotas: `/plano`, `/upload`, `/pipeline`, `/revisao`, `/traces/:id`, `/dlq`.
- **Tema claro/escuro**: o botão na sidebar alterna e a escolha é persistida (localStorage `pipefiscal.theme`). Implemente com CSS custom properties no root (ver tokens). A sidebar e os cards escuros não mudam.
- **Broker**: trocar Kafka/RabbitMQ muda os nomes de filas, o texto da política e o rótulo, e registra um evento. Na implementação real, isso consulta a config ativa do backend (`GET /api/v1/broker`) ou alterna o perfil em ambiente de demo.
- **Tarefas**: clicar alterna o check, persistido (localStorage `pipefiscal.tasks.v1` no protótipo; pode virar `PATCH /api/v1/plano/tarefas/:id`).
- **Rodar suíte**: executa os testes em sequência (no protótipo, ~700ms por teste). Na implementação real, só exibe o resultado do último run do CI.
- **Pausar e velocidade** existem só no modo demo/simulação.

### Regras de domínio (implementar no backend; a UI só exibe)
- **Idempotência**: `idempotency_key = SHA-256 do arquivo`, com UNIQUE constraint. Um reenvio responde 409 e não cria documento.
- **Retry**: até 3 retries com backoff de 1s, 2s e 4s. A 4ª falha manda para a DLQ. Reprocessar reutiliza a mesma key e o mesmo trace.
- **DV do CNPJ**: pesos `5,4,3,2,9,8,7,6,5,4,3,2` (1º dígito) e `6,5,4,3,2,9,8,7,6,5,4,3,2` (2º dígito). Calcule `soma % 11`: se o resto for menor que 2, o dígito é 0; senão, `11 − resto`. Mensagens exibidas: "DV 81 confere" / "DV calculado 81, lido 82".
- **Soma dos itens = total**, com tolerância de R$ 0,01.
- **Data não futura** e **campos obrigatórios** (cnpj, emitente, data, total, itens).
- **Score** = `cnpj·.25 + total·.25 + itens·.20 + data·.15 + emitente·.15`.
- **Decisão**: qualquer validação falha → revisão ("Validação falhou: …"). Score abaixo do limiar → revisão ("Confiança 78% abaixo do limiar 85%"). Caso contrário, aprovação automática. O limiar é configurável (padrão 0.85).

### Spans do trace (ordem)
`POST /api/v1/documentos` (api) → `INSERT documento ON CONFLICT (idempotency_key)` (db) → `publish {in}` (broker) → `consume {in}` (worker) → `ocr.paddleocr` (worker) → `llm.extract (json schema)` (worker). Em caso de falha: `llm.extract → {erro}` e depois `retry +Ns → {retry}`, repetindo. Depois vêm `publish {out}` (broker) → `consume {out}` (api) → `validate cnpj.dv + soma.itens` (api) → `score.confianca` (api) → `UPDATE documento SET status` (db).

## State Management / dados reais
- `GET /api/v1/documentos?limit=40`: lista com `code`, `file`, `stage`, `progress`, `outcome` (`auto|review|dlq`), `review` (`aprovado|rejeitado|null`) e `confidence`.
- `GET /api/v1/documentos/:id`: campos extraídos, confiança por campo, validações, itens e `traceId`.
- `PATCH /api/v1/documentos/:id` (correção de cnpj/total), seguido de `POST …/aprovar` ou `POST …/rejeitar`.
- `POST /api/v1/documentos` (multipart), que responde 202 ou 409.
- `POST /api/v1/documentos/:id/reprocessar`.
- `GET /api/v1/traces/:traceId`: spans `{service, name, startMs, durationMs, status}`, consultados no Jaeger/Tempo.
- **Stream de eventos**: WebSocket/SSE `/api/v1/eventos`, com `{time, service, documentId, message, level}`. Ele alimenta métricas, nós, filas, envelope e log. O mesmo stream é encaminhado ao simulador de mensageria (`ws://localhost:8090/events`).
- Estado local de UI: `screen`, `theme`, `selectedReviewId`, `selectedTraceId`.

## Design Tokens
**Fontes** (Google Fonts): **Instrument Sans** 400/500/600/700 (UI) e **JetBrains Mono** 400/500/600 (códigos, números, eventos). Tamanho base 14px.

| token | claro | escuro |
|---|---|---|
| bg | #F3F1EC | #121210 |
| panel | #FFFFFF | #1C1B18 |
| line | #E2DED5 | #2E2C28 |
| line2 | #EEEBE4 | #262522 |
| line3 | #C9C3B6 | #4A4740 |
| ink | #1B1B18 | #ECE8DF |
| ink2 | #3A3832 | #C9C4B9 |
| muted | #6B675E | #9A958A |
| track | #F6F4EF | #25241F |
| soft | #FBF9F5 | #24231F |
| stripe | #ECE8E0 | #1F1E1B |
| ok | #2E7D4F | #6FBF8A |
| warn | #B7791F | #E0B060 |
| err | #C0392B | #F08A7E |
| okbg | #E3F1E8 | rgba(111,191,138,.14) |
| warnbg | #FBEBD8 | rgba(224,176,96,.15) |
| errbg | #FBECEA | rgba(240,138,126,.14) |
| c-api (Java) | #3F7D3A | #8FC27F |
| c-broker | #9A6B1F | #E0B060 |
| c-worker (Python) | #2F5DA8 | #86A9E6 |
| c-db (Postgres) | #6B4FA0 | #B9A2E3 |
| waitbar | #E8D3AE | #5A4A2C |
| btn / btnfg | #1B1B18 / #F3F1EC | #ECE8DF / #1B1B18 |

Fixos (não mudam com o tema): acento `#E2572B`; sidebar `#1B1B18`, `#2A2925`, `#34332E`, `#45443E`; textos da sidebar `#F3F1EC`, `#C9C4B9`, `#A9A49A`, `#8C877D`.

- **Radius**: 4 (tags), 5–6 (botões, inputs), 7 (nav), 8 (validações), 10 (cards), 12 (dropzone).
- **Sombras**: nenhuma. A seleção usa `inset 3px 0 0 #E2572B`.
- **Espaçamento**: gaps 4/6/8/10/12/16/18/20/22/24.

## Assets
Não há imagens. Os ícones de lua/sol são SVG stroke de 16px (pode trocar por lucide `Moon`/`Sun`). O preview do documento é um placeholder listrado; troque pela imagem ou PDF real (URL pré-assinada do MinIO).

## Files
- `Pipeline Fiscal.dc.html`: página de entrada (monta o app em tela cheia; tweaks de limiar e taxa de falha).
- `Pipeline Fiscal App.dc.html`: o app completo, com o template e a lógica da simulação (classe `Component`). É a fonte de verdade para textos, estilos e regras. Ignore o ramo `layout="topbar"`.
- `support.js`: runtime necessário para abrir os `.dc.html` no navegador. Não faz parte da implementação.

Para ver o protótipo, sirva a pasta com um servidor estático (`npx serve .`) e abra `Pipeline Fiscal.dc.html`.

## Sugestão de ordem para o Claude Code
1. Criar `frontend/` com Vite + React + TS, tokens em CSS variables (claro/escuro) e as fontes.
2. Fazer o layout com sidebar, as rotas e o toggle de tema.
3. Implementar as telas na ordem Pipeline → Revisão → Traces → DLQ → Upload → Plano.
4. Criar uma camada `api/` com tipos TS e um **modo mock**, portando a simulação de `Pipeline Fiscal App.dc.html` para rodar sem backend.
5. Ligar ao backend real (REST + SSE/WebSocket) e encaminhar os eventos ao simulador de mensageria.
