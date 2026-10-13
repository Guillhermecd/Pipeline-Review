# Pipeline Fiscal — frontend

React + TypeScript + Vite, na estrutura do BIMD Template. Recria o handoff `design_handoff_pipeline_fiscal`.

## Rodar

```sh
cp .env.example .env
npm install
npm run dev
```

Abra `http://localhost:5173` (a tela inicial é `/pipeline`).

## Modo mock

Enquanto o backend não existe, `src/api/mock/` faz o papel da API: gera documentos, spans, retries, DLQ e eventos no navegador. As páginas não sabem disso. Elas só falam com os services de `src/api/modules/`. Para ligar o backend real, cada service troca o `mockEngine` por chamadas ao `api` (REST) e por um stream SSE em `/api/v1/eventos`. As páginas não mudam.

- `VITE_MOCK_THRESHOLD`: limiar de confiança (padrão 0.85).
- `VITE_MOCK_FAILURE_RATE`: chance de falha transitória no LLM (padrão 0.25). Aumente para ver retries e DLQ.

As regras de `src/api/mock/rules.ts` (DV do CNPJ, soma dos itens, score) são cópia das regras do backend, só para a simulação. A fonte da verdade é o Spring Boot.

## Rotas

`/plano` · `/upload` · `/pipeline` · `/revisao` · `/traces/:documentCode` · `/dlq`

## Checklist

```sh
npm run lint && npm run format:check && npm run build
```
