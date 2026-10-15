-- Fim do processamento automático: decisão do pipeline (aprovação, revisão) ou DLQ.
-- finished_at continua marcando o fim do documento, que na revisão é a decisão humana.
ALTER TABLE documents ADD COLUMN processed_at VARCHAR(24);

-- Documentos antigos: a DLQ grava finished_at; a decisão do pipeline usa o mesmo instante
-- da gravação da extração. As validações não servem: a correção do revisor as regrava.
UPDATE documents d
SET processed_at = CASE
    WHEN d.status = 'DEAD_LETTERED' THEN d.finished_at
    ELSE (SELECT max(e.created_at) FROM extractions e WHERE e.document_id = d.id)
END
WHERE d.status IN ('AUTO_APPROVED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'DEAD_LETTERED');
