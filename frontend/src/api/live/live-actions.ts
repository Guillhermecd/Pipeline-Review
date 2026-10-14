import { message } from 'antd';
import type {
  BrokerConfig,
  BrokerKind,
  EditableField,
  SampleDocument,
  Trace,
} from '../modules/types';
import { api, ApiError } from '../modules/api';
import type { ApiDocument, ApiDuplicate, ApiFileLink, ApiTrace } from './api-types';
import { liveStore } from './live-store';
import { toApiMoney, toTrace } from './mappers';

const CONFLICT = 409;
const NOT_FOUND = 404;
const HASH_PREVIEW_LENGTH = 12;
const EDIT_DEBOUNCE_MS = 600;
const SAMPLES_PATH = '/samples';

// Erros de ação aparecem como aviso na tela, com o texto que a API escreveu.
const report = (error: unknown) => {
  message.error(error instanceof Error ? error.message : 'Não foi possível concluir a operação');
};

const send = async (path: string, init: RequestInit) => {
  try {
    liveStore.applyResponse(await api<ApiDocument>(path, init));
  } catch (error) {
    report(error);
  }
};

const shortKey = (idempotencyKey: string) =>
  idempotencyKey.replace('sha256:', '').slice(0, HASH_PREVIEW_LENGTH);

// POST /documentos (multipart) → 202 com o documento ou 409 com o que já existia.
const upload = async (file: Blob, fileName: string) => {
  const body = new FormData();
  body.append('file', file, fileName);
  try {
    const doc = await api<ApiDocument>('/documentos', { method: 'POST', body });
    liveStore.applyResponse(doc);
    liveStore.recordUpload(fileName, true, `${doc.code} · sha256:${shortKey(doc.idempotencyKey)}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === CONFLICT) {
      const duplicate = error.body as ApiDuplicate;
      liveStore.recordUpload(fileName, false, duplicate.message);
      return;
    }
    liveStore.recordUpload(fileName, false, error instanceof Error ? error.message : 'falha');
  }
};

const fetchSample = async (sample: SampleDocument) => {
  const response = await fetch(`${SAMPLES_PATH}/${sample.fileName}`);
  if (!response.ok) throw new Error(`Amostra ${sample.fileName} não encontrada`);
  return response.blob();
};

// A chave de idempotência é o hash do arquivo. Bytes a mais no fim tornam cada envio de amostra
// um arquivo novo; leitores de PDF e de imagem ignoram o que vem depois do marcador de fim.
const asNewFile = (file: Blob) =>
  new Blob([file, `\n% amostra ${crypto.randomUUID()}\n`], { type: file.type });

const submitSample = async (sample: SampleDocument, sequence: number) => {
  try {
    const suffix = String(sequence).padStart(4, '0');
    const fileName = sample.fileName.replace(/(\.\w+)$/, `_${suffix}$1`);
    await upload(asNewFile(await fetchSample(sample)), fileName);
  } catch (error) {
    report(error);
  }
};

let sampleSequence = 0;
const pendingEdits = new Map<string, ReturnType<typeof setTimeout>>();

export const liveActions = {
  async uploadFiles(files: File[]) {
    for (const file of files) await upload(file, file.name);
  },

  submitSample(sample: SampleDocument) {
    return submitSample(sample, ++sampleSequence);
  },

  async submitAllSamples(samples: SampleDocument[]) {
    for (const sample of samples) await submitSample(sample, ++sampleSequence);
  },

  // Os mesmos bytes, sem alteração: o primeiro envio é aceito e os seguintes respondem 409.
  async resubmitSameFile(sample: SampleDocument) {
    try {
      await upload(await fetchSample(sample), sample.fileName);
    } catch (error) {
      report(error);
    }
  },

  // PATCH /documentos/:id. O campo dispara a cada tecla, então só o último valor de cada pausa
  // na digitação é enviado, e um total ainda incompleto ("283,") não é enviado.
  updateField(id: string, field: EditableField, value: string) {
    const key = `${id}:${field}`;
    clearTimeout(pendingEdits.get(key));
    pendingEdits.set(
      key,
      setTimeout(() => {
        pendingEdits.delete(key);
        const corrected = field === 'total' ? toApiMoney(value) : value.trim();
        if (!corrected) return;
        void send(`/documentos/${id}`, {
          method: 'PATCH',
          body: JSON.stringify({ [field]: corrected }),
        });
      }, EDIT_DEBOUNCE_MS),
    );
  },

  approve: (id: string) => send(`/documentos/${id}/aprovar`, { method: 'POST' }),
  reject: (id: string) => send(`/documentos/${id}/rejeitar`, { method: 'POST' }),
  reprocess: (id: string) => send(`/documentos/${id}/reprocessar`, { method: 'POST' }),

  async setBroker(kind: BrokerKind) {
    try {
      const broker = await api<BrokerConfig>('/broker', {
        method: 'PUT',
        body: JSON.stringify({ kind }),
      });
      liveStore.setBroker(broker);
    } catch (error) {
      report(error);
    }
  },

  // GET /traces/:traceId. O Jaeger recebe os spans em lotes: um trace recente pode ainda não
  // estar lá (404), e isso não é erro.
  async getTrace(traceId: string): Promise<Trace | null> {
    try {
      return toTrace(await api<ApiTrace>(`/traces/${traceId}`));
    } catch (error) {
      if (error instanceof ApiError && error.status === NOT_FOUND) return null;
      throw error;
    }
  },

  // GET /documentos/:id/arquivo → link temporário do arquivo, para o preview da Revisão.
  async getFileUrl(id: string) {
    const link = await api<ApiFileLink>(`/documentos/${id}/arquivo`);
    return link.url;
  },
};
