import { IS_LIVE } from '../../config';
import { liveActions } from '../live/live-actions';
import { mockEngine, SAMPLES } from '../mock';
import type { EditableField, SampleDocument } from './types';

const HASH_PREVIEW_LENGTH = 12;

const sha256Hex = async (file: File) => {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
};

// Endpoints de /api/v1/documentos. No modo mock tudo vai para o mockEngine.
export const DocumentService = {
  // Os cards de amostra. No modo live o botão envia o arquivo real de /samples.
  getSamples(): SampleDocument[] {
    return SAMPLES;
  },

  // POST /documentos (multipart) → 202 ou 409. No mock o conteúdo extraído vem de um exemplo.
  async uploadFiles(files: File[]) {
    if (IS_LIVE) return liveActions.uploadFiles(files);
    for (const file of files) {
      const hash = (await sha256Hex(file)).slice(0, HASH_PREVIEW_LENGTH);
      const sample = SAMPLES[Math.floor(Math.random() * SAMPLES.length)];
      mockEngine.ingest(sample, file.name, hash);
    }
  },

  submitSample(sample: SampleDocument) {
    if (IS_LIVE) return void liveActions.submitSample(sample);
    mockEngine.submitSample(sample);
  },

  submitAllSamples() {
    if (IS_LIVE) return void liveActions.submitAllSamples(SAMPLES);
    SAMPLES.forEach((sample) => mockEngine.submitSample(sample));
  },

  // Reenvia o mesmo arquivo do primeiro exemplo para provar a idempotência.
  resubmitSameFile() {
    if (IS_LIVE) return void liveActions.resubmitSameFile(SAMPLES[0]);
    mockEngine.resubmitFirstSample();
  },

  // PATCH /documentos/:id
  updateField(id: string, field: EditableField, value: string) {
    if (IS_LIVE) return liveActions.updateField(id, field, value);
    mockEngine.edit(id, field, value);
  },

  // POST /documentos/:id/aprovar
  approve(id: string) {
    if (IS_LIVE) return void liveActions.approve(id);
    mockEngine.review(id, 'approved');
  },

  // POST /documentos/:id/rejeitar
  reject(id: string) {
    if (IS_LIVE) return void liveActions.reject(id);
    mockEngine.review(id, 'rejected');
  },

  // POST /documentos/:id/reprocessar
  reprocess(id: string) {
    if (IS_LIVE) return void liveActions.reprocess(id);
    mockEngine.reprocess(id);
  },

  // GET /documentos/:id/arquivo. O mock não guarda arquivo, então não há o que mostrar.
  getFileUrl(id: string): Promise<string | null> {
    return IS_LIVE ? liveActions.getFileUrl(id) : Promise.resolve(null);
  },
};
