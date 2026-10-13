import { mockEngine, SAMPLES } from '../mock';
import type { EditableField, SampleDocument } from './types';

const HASH_PREVIEW_LENGTH = 12;

const sha256Hex = async (file: File) => {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
};

// Endpoints de /api/v1/documentos. No modo mock tudo vai para o mockEngine.
export const DocumentService = {
  getSamples(): SampleDocument[] {
    return SAMPLES;
  },

  // POST /documentos (multipart) → 202 ou 409. O conteúdo extraído vem de um exemplo.
  async uploadFiles(files: File[]) {
    for (const file of files) {
      const hash = (await sha256Hex(file)).slice(0, HASH_PREVIEW_LENGTH);
      const sample = SAMPLES[Math.floor(Math.random() * SAMPLES.length)];
      mockEngine.ingest(sample, file.name, hash);
    }
  },

  submitSample(sample: SampleDocument) {
    mockEngine.submitSample(sample);
  },

  submitAllSamples() {
    SAMPLES.forEach((sample) => mockEngine.submitSample(sample));
  },

  // Reenvia o mesmo arquivo do primeiro exemplo para provar a idempotência.
  resubmitSameFile() {
    mockEngine.resubmitFirstSample();
  },

  // PATCH /documentos/:id
  updateField(id: number, field: EditableField, value: string) {
    mockEngine.edit(id, field, value);
  },

  // POST /documentos/:id/aprovar
  approve(id: number) {
    mockEngine.review(id, 'approved');
  },

  // POST /documentos/:id/rejeitar
  reject(id: number) {
    mockEngine.review(id, 'rejected');
  },

  // POST /documentos/:id/reprocessar
  reprocess(id: number) {
    mockEngine.reprocess(id);
  },
};
