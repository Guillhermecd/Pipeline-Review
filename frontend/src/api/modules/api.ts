import { API_BASE_URL, LOGIN_PATH } from '../../config';

const TOKEN_KEY = 'pipefiscal.token';
const UNAUTHORIZED = 401;
const NO_CONTENT = 204;
const GENERIC_ERROR = 'Não foi possível concluir a operação';

export const authStorage = {
  getToken: () => localStorage.getItem(TOKEN_KEY),
  setToken: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
};

type RequestOptions = RequestInit & { auth?: boolean; baseUrl?: string };

export class ApiError extends Error {
  readonly status: number;
  // Corpo da resposta, quando veio em JSON (o 409 de duplicata traz o documento existente).
  readonly body: unknown;

  constructor(status: number, message: string, body: unknown = null) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

// A API devolve `{ message }` escrito para quem usa a tela; é esse texto que aparece.
const readError = async (response: Response) => {
  const text = await response.text();
  try {
    const body = JSON.parse(text) as { message?: string };
    return new ApiError(response.status, body.message || GENERIC_ERROR, body);
  } catch {
    return new ApiError(response.status, text || GENERIC_ERROR);
  }
};

// Sessão expirada ou token inválido: volta para o login.
const endSession = () => {
  authStorage.clear();
  if (window.location.pathname !== LOGIN_PATH) window.location.assign(LOGIN_PATH);
};

// Cliente HTTP central do modo live.
export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { auth = true, baseUrl = API_BASE_URL, headers, ...init } = options;
  const token = authStorage.getToken();
  const isForm = init.body instanceof FormData;
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: {
      ...(isForm ? {} : { 'Content-Type': 'application/json' }),
      ...(auth && token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
  });
  if (!response.ok) {
    if (response.status === UNAUTHORIZED && auth) endSession();
    throw await readError(response);
  }
  return response.status === NO_CONTENT ? (undefined as T) : ((await response.json()) as T);
}
