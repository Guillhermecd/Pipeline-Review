// `mock` simula o backend no navegador; `live` fala com a API real (login, REST e SSE).
export type ApiMode = 'mock' | 'live';

export const API_MODE: ApiMode = import.meta.env.VITE_API_MODE === 'live' ? 'live' : 'mock';

export const IS_LIVE = API_MODE === 'live';

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1';

// As rotas de autenticação do template ficam fora do prefixo /api/v1.
export const AUTH_BASE_URL = import.meta.env.VITE_AUTH_BASE_URL ?? '/api/auth';

export const LOGIN_PATH = '/login';
