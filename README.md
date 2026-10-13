# Pipeline Fiscal

Pipeline de extração de documentos fiscais. O usuário envia uma nota (PDF ou imagem), um worker extrai os campos com OCR e LLM, e a API valida, pontua e decide entre aprovação automática e revisão humana.

- [BACKEND.md](BACKEND.md): especificação do backend e do worker.
- [ARCHITECTURE.md](ARCHITECTURE.md): decisões e desvios em relação ao BIMD Template.
- [frontend/README.md](frontend/README.md): frontend e modo mock.

## Estrutura

```text
backend/     Spring Boot (API, validação, orquestração)
frontend/    React + TypeScript + Vite
worker/      Python: OCR + extração (fase F2)
contracts/   JSON Schemas das mensagens (fase F2)
```

## Rodar

Requisitos: Docker, JDK 21 ou superior, Node 20 ou superior.

```sh
docker compose -f docker-compose.dev.yml up -d

cd backend
cp .env.example .env
./mvnw spring-boot:run        # http://localhost:1337

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

## Checklist antes do PR

```sh
cd backend && ./mvnw verify
cd frontend && npm run lint && npm run format:check && npm run build
```

`./mvnw verify` roda os testes unitários e os de integração (`*IT`, Testcontainers), que precisam do Docker ligado.
