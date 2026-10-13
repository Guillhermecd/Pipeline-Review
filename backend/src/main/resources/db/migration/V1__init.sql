-- Template (auth). Mantida como o template define.
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email ON users (email);

-- Pipeline fiscal. Datas em ISO 8601 UTC como texto (BACKEND.md §1 e §14).
CREATE SEQUENCE document_code_seq START WITH 1001;

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    code VARCHAR(16) NOT NULL,
    user_id UUID NOT NULL REFERENCES users (id),
    file_name VARCHAR(255) NOT NULL,
    file_key VARCHAR(512) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    broker VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    kind VARCHAR(32),
    trace_id CHAR(32) NOT NULL,
    current_request_id UUID NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT,
    reason TEXT,
    confidence NUMERIC(5, 4),
    received_at VARCHAR(24) NOT NULL,
    finished_at VARCHAR(24),
    created_at VARCHAR(24) NOT NULL,
    updated_at VARCHAR(24) NOT NULL,
    CONSTRAINT uq_documents_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uq_documents_code UNIQUE (code)
);

CREATE INDEX idx_documents_created_at ON documents (created_at DESC);
CREATE INDEX idx_documents_status ON documents (status);

CREATE TABLE fiscal_data (
    document_id UUID PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    -- Texto: a Receita emite CNPJ alfanumérico desde julho de 2026.
    cnpj VARCHAR(18),
    issuer VARCHAR(255),
    issue_date VARCHAR(10),
    total NUMERIC(14, 2),
    items_sum NUMERIC(14, 2)
);

CREATE TABLE field_confidences (
    document_id UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    field VARCHAR(16) NOT NULL,
    confidence NUMERIC(5, 4) NOT NULL,
    source VARCHAR(16) NOT NULL,
    PRIMARY KEY (document_id, field)
);

CREATE TABLE document_items (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    line INT NOT NULL,
    description VARCHAR(500) NOT NULL,
    quantity NUMERIC(12, 3) NOT NULL,
    unit_price NUMERIC(14, 2) NOT NULL
);

CREATE INDEX idx_document_items_document ON document_items (document_id, line);

CREATE TABLE extractions (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    raw_result JSONB NOT NULL,
    ocr_engine VARCHAR(32),
    llm_model VARCHAR(64),
    input_tokens INT,
    output_tokens INT,
    created_at VARCHAR(24) NOT NULL,
    CONSTRAINT uq_extractions_request_id UNIQUE (request_id)
);

CREATE INDEX idx_extractions_document ON extractions (document_id);

CREATE TABLE validation_results (
    document_id UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    rule_code VARCHAR(40) NOT NULL,
    passed BOOLEAN NOT NULL,
    detail VARCHAR(255) NOT NULL,
    created_at VARCHAR(24) NOT NULL,
    PRIMARY KEY (document_id, rule_code)
);

CREATE TABLE reviews (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    reviewer_id UUID NOT NULL REFERENCES users (id),
    decision VARCHAR(16) NOT NULL,
    corrections JSONB,
    reviewed_at VARCHAR(24) NOT NULL
);

CREATE INDEX idx_reviews_document ON reviews (document_id);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    event_type VARCHAR(40) NOT NULL,
    broker VARCHAR(16) NOT NULL,
    destination VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    traceparent VARCHAR(55) NOT NULL,
    created_at VARCHAR(24) NOT NULL,
    published_at VARCHAR(24)
);

-- O relay lê só o que ainda não saiu.
CREATE INDEX idx_outbox_events_pending ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    processed_at VARCHAR(24) NOT NULL
);

CREATE TABLE pipeline_events (
    id UUID PRIMARY KEY,
    occurred_at VARCHAR(24) NOT NULL,
    service VARCHAR(16) NOT NULL,
    document_code VARCHAR(16) NOT NULL,
    message VARCHAR(500) NOT NULL,
    level VARCHAR(8) NOT NULL
);

CREATE INDEX idx_pipeline_events_occurred_at ON pipeline_events (occurred_at DESC);
