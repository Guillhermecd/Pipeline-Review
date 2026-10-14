# Pipeline Fiscal

Pipeline de extração de documentos fiscais. O usuário envia uma nota (PDF ou imagem), um worker extrai os campos com OCR e LLM, e a API valida, pontua e decide entre aprovação automática e revisão humana.

- [BACKEND.md](BACKEND.md): especificação do backend e do worker.
- [ARCHITECTURE.md](ARCHITECTURE.md): decisões e desvios em relação ao BIMD Template.
- [frontend/README.md](frontend/README.md): frontend e modo mock.

## Estrutura

```text
backend/     Spring Boot (API, validação, orquestração)
frontend/    React + TypeScript + Vite
worker/      Python: OCR + extração (PaddleOCR, PyMuPDF, Gemini)
contracts/   JSON Schemas das mensagens trocadas entre API e worker, com exemplos
```

## Rodar

Requisitos: Docker, JDK 21 ou superior, Node 20 ou superior, [uv](https://docs.astral.sh/uv/) (instala o Python 3.12).

```sh
docker compose -f docker-compose.dev.yml up -d

cd backend
cp .env.example .env
./mvnw spring-boot:run        # http://localhost:1337

cd worker
cp .env.example .env          # preencha GEMINI_API_KEY
uv sync
uv run fiscal-worker          # a primeira execução baixa os modelos do OCR

cd frontend
cp .env.example .env
npm install
npm run dev                   # http://localhost:5173
```

| Serviço | Endereço no host |
|---|---|
| PostgreSQL | `localhost:5442` (banco `pipeline_fiscal`) |
| MinIO | `http://localhost:9050` · console `http://localhost:9051` |
| Mailpit | SMTP `127.0.0.1:1035` · UI `http://localhost:8035` |
| Kafka | `localhost:9092` |
| RabbitMQ | `amqp://localhost:5673` · UI `http://localhost:15673` |
| Jaeger | UI `http://localhost:16686` · OTLP `4317` / `4318` |

Usuário inicial: `admin@oaksd.local` / `ChangeMe123!` (variáveis `INITIAL_USER_*`).

## Enviar um documento

```sh
TOKEN=$(curl -s localhost:1337/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@oaksd.local","password":"ChangeMe123!"}' | jq -r .token)

curl -i localhost:1337/api/v1/documentos -H "Authorization: Bearer $TOKEN" -F file=@nota.pdf
```

O primeiro envio responde `202`. Reenviar o mesmo arquivo responde `409`.

## Extração ponta a ponta

Com compose, backend e worker rodando:

```sh
cd worker && uv run python scripts/smoke.py            # usa a NFC-e de amostra
cd worker && uv run python scripts/smoke.py nota.pdf   # ou um arquivo seu
```

O script envia o arquivo pela API, publica o envelope na fila de entrada (papel do relay, que chega na fase F3) e espera o `documento.extraido`, validando contra `contracts/`.

As seis amostras ficam em `backend/src/main/resources/samples/` e `frontend/public/samples/`. São sintéticas e geradas por `uv run python scripts/generate_samples.py`.

## Checklist antes do PR

```sh
cd backend && ./mvnw verify
cd worker && uv run ruff check && uv run ruff format --check && uv run mypy src && uv run pytest
cd frontend && npm run lint && npm run format:check && npm run build
```

`./mvnw verify` e `uv run pytest` incluem testes de integração com Testcontainers, que precisam do Docker ligado. Para pular os lentos do worker: `uv run pytest -m "not integration and not ocr"`.
