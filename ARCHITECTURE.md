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

## Worker: decisões da extração

- **Confiança de valor em PDF com texto: 0,95.** A camada de texto não tem score de reconhecimento. Um valor devolvido pelo LLM que aparece nela recebe 0,95 e `source = pdf_text`.
- **Comparação normalizada.** O LLM devolve `283.80` e `2026-09-28`; o documento imprime `283,80` e `28/09/2026`. O valor é procurado no texto em todas as formas impressas possíveis, sem acento e sem diferença de maiúsculas. Sem isso todo número cairia em 0,3 (alucinação).
- **Confiança em imagem.** Média do score do PaddleOCR nas linhas onde o valor aparece. Valor quebrado em mais de uma linha usa a média da página.
- **Itens.** Cada linha vale o menor entre a confiança da descrição e a do preço unitário; `itemsConfidence` é a média das linhas.
- **QR Code vence o texto.** O CNPJ lido da chave de acesso da NFC-e substitui o do LLM, com confiança 1,0.
- **CPF mascarado antes do LLM.** CPF formatado, ou 11 dígitos precedidos do rótulo "CPF", vira `***.***.***-**`. CNPJ e chave de acesso passam intactos.
- **Sem binarização.** O pré-processamento endireita a imagem e equaliza o contraste (CLAHE). A binarização prevista apaga traços finos de cupom térmico e derruba o score do OCR.
- **Erro inesperado vai para a DLQ.** Um bug no worker não pode reentregar o mesmo documento para sempre. Só falha ao publicar no broker deixa a mensagem sem ack.
- **`storage/` no worker.** Pasta a mais em relação à estrutura do `BACKEND.md`, para a leitura do arquivo no MinIO.

## Worker: dependências

- **oneDNN desligado no PaddleOCR.** O PaddlePaddle 3.3 falha em CPU com oneDNN (`ConvertPirAttribute2RuntimeAttribute not support`). Com `enable_mkldnn=False` funciona, mais lento: de 8 a 21 s por imagem nesta máquina.
- **Um só pacote OpenCV.** `opencv-contrib-python`, o mesmo que o PaddleOCR traz. Dois pacotes `opencv-*` dividem o módulo `cv2` e quebram o import.
- **Gemini sem retry do SDK.** `HttpRetryOptions(attempts=1)`: quem repete é o broker. Retries empilhados se multiplicariam.
- **Modelo padrão `gemini-3.5-flash-lite`.** `gemini-2.5-flash` foi aposentado (404). Em 13/10/2026 os modelos `flash` da série 3 levaram de 25 a 90 s por chamada, com 503 e 504 frequentes; o `flash-lite` respondeu em 1 a 2 s com o mesmo resultado nas amostras. A confiança vem da conferência do valor contra o texto do documento, não do modelo. Troca por `LLM_MODEL`.
- **Plano gratuito do Gemini.** Decisão do projeto em 13/10/2026, junto com a confirmação do `flash-lite`. No plano gratuito o Google informa que pode usar o conteúdo enviado para melhorar seus produtos. O CPF é mascarado antes do envio, mas CNPJ, emitente e itens seguem no texto. Conferir os termos vigentes antes de enviar documento real de terceiros. O limite de requisições do plano aparece como 429, que o worker trata como falha transitória (retry 1 s · 2 s · 4 s).
- **Modelos do OCR** são baixados na primeira execução (Hugging Face) e ficam em `~/.paddlex`.

## Mensageria e confiabilidade

- **Topologia em um arquivo.** `contracts/topology.json` fixa nomes de tópicos e filas e os argumentos das filas de retry. Backend e worker declaram os mesmos objetos e cada lado tem um teste que compara suas constantes com o arquivo. O RabbitMQ responde 406 a quem declara uma fila com argumentos diferentes.
- **Backoff fixo pelo contrato.** 1 s, 2 s e 4 s não são mais variável de ambiente (`RETRY_BACKOFF_MS` saiu): o valor define o nome e o TTL das filas do RabbitMQ, então mudar exige alterar o contrato.
- **Contagem de tentativas no envelope.** O worker republica a mensagem como `documento.retry` com `attempt + 1`. O header `x-death` do RabbitMQ não é usado: com uma fila por atraso, cada fila conta 1.
- **Retry no Kafka.** A mensagem vai para `documentos.recebidos-retry` com o horário em `x-not-before`. Um consumer do worker (grupo `extrator-py-retry`) pausa a partição até o horário e devolve a mensagem para `documentos.recebidos`. Uma espera de 4 s segura as mensagens seguintes da mesma partição por até 4 s.
- **Relay do outbox.** A cada `OUTBOX_POLL_MS` lê a linha pendente mais antiga de cada broker com `FOR UPDATE SKIP LOCKED`, publica e só marca `published_at` depois do confirm (`acks=all` no Kafka, publisher confirms no RabbitMQ). Se cair entre publicar e marcar, a mensagem sai de novo: a entrega é "ao menos uma vez".
- **Guarda de estado.** Todo consumo no backend trava a linha do documento e confere, na mesma transação: evento ainda não aplicado (`processed_events`), `requestId` igual ao atual, transição permitida, tentativa não anterior à registrada. Falhou alguma, a mensagem é descartada com log `debug`.
- **Transições além da tabela do `BACKEND.md`.** Progresso e resultado trafegam em filas diferentes, então o resultado pode chegar antes do progresso. A máquina aceita `EXTRACTED` e `DEAD_LETTERED` a partir de `QUEUED`, `OCR`, `EXTRACTING` e `RETRY_WAIT`, e `RETRY_WAIT` a partir de `QUEUED`. Nunca volta: progresso atrasado é descartado.
- **Reprocessar só de `DEAD_LETTERED`.** A checagem é explícita, porque `RECEIVED → QUEUED` também é uma transição válida (a do relay).
- **Falha ao gravar no backend.** Se o banco cair, o listener repete a mensagem com backoff até conseguir: no Kafka o intervalo cresce até 30 s; no RabbitMQ, esgotadas 5 tentativas, a mensagem volta para a fila. Desistir perderia o resultado e deixaria o documento parado. Mensagem ilegível é descartada na primeira leitura, então não há laço com mensagem envenenada.
- **Um broker fora do ar não para o outro.** No worker cada broker tem seu laço de consumo, que reconecta a cada 5 s. No backend o relay trata cada broker em separado.
- **Textos de `retryPolicy`.** São os do mock do frontend, como o `BACKEND.md` pede. Eles citam `@RetryableTopic` e `x-death`, que a implementação não usa.

## Validação, score e revisão

- **Tudo em uma transação.** Ao receber `documento.extraido`, o backend grava a extração, os campos, os itens e as confianças, roda as regras, calcula o score e decide, na mesma transação da mudança de estado. O documento passa por `EXTRACTED` e `VALIDATING` sem que ninguém de fora veja esses estados.
- **O payload não é confiável.** `ExtractionPayloadReader` lê cada valor com defesa: ausente, fora do formato ou maior que a coluna vira nulo (campos) ou é ignorado (itens), e a confiança do campo vai a zero. Como o listener repete até conseguir, uma exceção causada pelos dados travaria a fila. Com isso o pior caso é o documento ir para revisão. O byte NUL é removido da mensagem, porque o PostgreSQL não o aceita em texto nem em `jsonb`.
- **CNPJ grande demais é descartado, nome é cortado.** Um CNPJ com mais de 18 caracteres vira nulo; um emitente com mais de 255 é truncado.
- **Textos sem definição no `BACKEND.md`.** Total ausente: `R$ 271,30 vs total ausente`. Data ausente ou ilegível: `data ausente`, e a regra falha. CNPJ com caractere que não é letra nem dígito, ou com letra no dígito verificador: `caracteres inválidos para um CNPJ`.
- **Soma dos itens.** Soma `quantidade × preço` de todas as linhas e arredonda uma vez para centavos (meio para cima), como a amostra é gerada.
- **Score com 4 casas.** O valor comparado com o limiar é o mesmo que fica em `documents.confidence`. Um score de 0,8499 aparece como 85% e ainda assim vai para revisão.
- **Itens sem confiança própria de origem.** `field_confidences` guarda `ITEMS` com origem `llm`; o contrato só traz `itemsConfidence`.
- **Correção não muda o motivo.** O `PATCH` recalcula regras e score, mas `reason` continua sendo o motivo pelo qual o documento foi para revisão.
- **Correções gravadas na decisão.** `reviews.corrections` é calculado ao aprovar ou rejeitar, comparando o valor extraído com o atual, só para campos que um revisor alterou. Formato: `{"total": {"from": "283.80", "to": "271.30"}}`.
- **O revisor dá a palavra final.** Documento aprovado automaticamente recebe `finishedAt` na decisão. Documento enviado para revisão fica com `finishedAt` nulo e só o recebe quando o revisor aprova ou rejeita; até lá `durationMs` continua contando.
- **Mensagens de erro em português.** O campo `message` do erro é escrito para quem usa a tela. Há tratamento para corpo ilegível ou valor fora da lista (400), identificador que não é UUID (400), correção vazia (400), ação de revisão em documento que não está em revisão (409), reprocessar documento fora da DLQ (409), banco indisponível (503) e falha inesperada (500, com a causa só no log). As mensagens de login são do template e seguem em inglês.
- **`GET /api/v1/configuracao`** entrou nesta fase, por expor o limiar.

## Observabilidade e modo live

- **Um trace por documento.** O `trace_id` do documento é o trace da requisição de upload. O outbox guarda o span pai, o relay continua a partir dele, o worker lê o `traceparent` do header, abre seus spans e devolve o próprio contexto nas mensagens que publica, e a API continua esse contexto na validação. Sem tracing ligado o `trace_id` é aleatório e nada mais muda.
- **API só com a API do OpenTelemetry.** O código usa `opentelemetry-api`; quem exporta é o agente Java, ligado pelo perfil Maven `otel` (`./mvnw spring-boot:run -Potel`). Sem o agente (testes, execução comum) as chamadas não fazem nada.
- **Worker com propagação manual.** `telemetry/tracing.py` extrai e injeta o `traceparent`. O provider sempre existe, então o trace segue pelos headers mesmo sem exportar.
- **Só traces.** Métricas e logs do agente ficam desligados: o Jaeger só recebe traces.
- **A tela de Traces mostra só spans com `pipeline.node`.** São os que o pipeline abre à mão, com os nomes da §9.2 do `BACKEND.md`. O agente grava centenas de spans automáticos por documento (JDBC, HTTP, broker), que são descartados. A §9.3 fala em herdar o nó do span pai; optei por não herdar, para o waterfall ficar igual ao do mock.
- **Serviço do span.** `db.system` vira `db`, `messaging.system` vira `broker`; o resto é `api` ou `worker` conforme o processo.
- **Espera de retry.** O span `retry +{n}s → {fila}` é criado pelo worker na tentativa seguinte, começando no horário da falha (o `occurredAt` da mensagem de retry).
- **Reprocessar** roda em um trace próprio (o da requisição), mas a mensagem volta para o trace do documento, com um span pai que não existe no Jaeger.
- **Trace recente pode não existir ainda.** Os spans chegam ao Jaeger em lotes, alguns segundos depois. `GET /traces/{id}` responde 404 nesse caso e a tela tenta de novo a cada 3 s. O Jaeger local guarda os traces em memória: reiniciar o container apaga o histórico.
- **Stream `/eventos` só depois do commit.** Mudanças viram eventos do Spring e são enviadas com `@TransactionalEventListener(AFTER_COMMIT)`. Uma tentativa que falhou e foi repetida não aparece na tela.
- **Conexões SSE em memória.** Vale para uma instância da API.
- **`pipeline_events.sequence`** (migração V2) dá a ordem de gravação; eventos do mesmo milissegundo ficavam sem ordem. A tabela é cortada nos 500 mais recentes a cada minuto.
- **401 sem token.** Requisição sem token válido responde 401 (antes 403), para o frontend saber que precisa de login.
- **Simulador de mensageria** aparece como desconectado; o espelhamento é da fase F6.
- **Frontend: dois modos.** `VITE_API_MODE=mock` (padrão) simula tudo no navegador; `live` usa login, REST e SSE. Os services escolhem a fonte; as páginas são as mesmas. `api/live/mappers.ts` converte o formato da API para o que as telas usam.
- **Correção de campo no modo live.** O campo dispara a cada tecla; o envio espera 600 ms de pausa e ignora total incompleto.
- **Amostras no modo live.** "Enviar" manda o arquivo real de `frontend/public/samples/` com bytes extras no fim, para cada envio ser um arquivo novo. "Testar idempotência" manda os bytes exatos: o primeiro envio é aceito, os seguintes respondem 409.

## Upload e idempotência

1. A API calcula o SHA-256 do arquivo. Se já existe documento com essa chave, responde 409 e nada é criado.
2. O arquivo é gravado no MinIO em `documents/{sha256}/{nome}` **antes** da transação. Um reenvio sobrescreve a mesma chave; já um documento gravado sem arquivo não teria como ser recuperado.
3. Em uma única transação: documento (`RECEIVED`), linha em `outbox_events` com o envelope completo e o evento de log. O relay publica a linha e move o documento para `QUEUED`.
4. Dois envios simultâneos do mesmo arquivo passam pela checagem do passo 1. Quem decide é a constraint `UNIQUE (idempotency_key)`: o perdedor recebe 409.
