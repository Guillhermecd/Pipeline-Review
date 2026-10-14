-- Eventos gravados no mesmo milissegundo ficavam sem ordem definida.
-- A sequencia registra a ordem de gravacao, que e a ordem em que a tela mostra.
ALTER TABLE pipeline_events ADD COLUMN sequence BIGINT GENERATED ALWAYS AS IDENTITY;

CREATE INDEX idx_pipeline_events_sequence ON pipeline_events (sequence DESC);
