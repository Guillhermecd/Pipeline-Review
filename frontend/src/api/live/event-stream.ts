import { API_BASE_URL } from '../../config';
import { authStorage } from '../modules/api';

const RECONNECT_MS = 3000;
const UNAUTHORIZED = 401;

type Handlers = {
  onEvent: (name: string, data: string) => void;
  // Sessão expirada: quem chama decide o que fazer (voltar para o login).
  onUnauthorized: () => void;
};

// Lê o stream /eventos (server-sent events) com fetch em vez de EventSource.
// EventSource não envia headers e obrigaria a pôr o token na URL, que acaba gravada em logs
// de acesso e nos spans do tracing. Com fetch o token vai no header Authorization.
export class EventStream {
  private controller: AbortController | null = null;
  private retry: ReturnType<typeof setTimeout> | null = null;
  private readonly handlers: Handlers;

  constructor(handlers: Handlers) {
    this.handlers = handlers;
  }

  get isOpen() {
    return this.controller !== null;
  }

  open() {
    if (this.controller) return;
    this.controller = new AbortController();
    void this.run(this.controller.signal);
  }

  close() {
    if (this.retry) clearTimeout(this.retry);
    this.retry = null;
    this.controller?.abort();
    this.controller = null;
  }

  // Uma conexão por vez; quando cai, tenta de novo. A cada conexão o servidor manda o estado inteiro.
  private async run(signal: AbortSignal) {
    try {
      const response = await fetch(`${API_BASE_URL}/eventos`, {
        headers: { Accept: 'text/event-stream', Authorization: `Bearer ${authStorage.getToken()}` },
        signal,
      });
      if (response.status === UNAUTHORIZED) {
        this.close();
        this.handlers.onUnauthorized();
        return;
      }
      if (response.ok && response.body) await this.read(response.body, signal);
    } catch {
      // Rede fora ou conexão interrompida: cai na nova tentativa abaixo.
    }
    if (signal.aborted) return;
    this.retry = setTimeout(() => void this.run(signal), RECONNECT_MS);
  }

  private async read(body: ReadableStream<Uint8Array>, signal: AbortSignal) {
    const reader = body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = '';
    while (!signal.aborted) {
      const { done, value } = await reader.read();
      if (done) return;
      buffer += value;
      // Um evento termina em linha em branco.
      const blocks = buffer.split(/\r?\n\r?\n/);
      buffer = blocks.pop() ?? '';
      blocks.forEach((block) => this.dispatch(block));
    }
  }

  private dispatch(block: string) {
    let name = 'message';
    const data: string[] = [];
    for (const line of block.split(/\r?\n/)) {
      // Linha que começa com ":" é comentário (o ping que mantém a conexão aberta).
      if (line.startsWith('event:')) name = line.slice('event:'.length).trim();
      else if (line.startsWith('data:')) data.push(line.slice('data:'.length).trimStart());
    }
    if (data.length) this.handlers.onEvent(name, data.join('\n'));
  }
}
