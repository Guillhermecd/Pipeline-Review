import { AUTH_BASE_URL } from '../../config';
import { api, authStorage } from './api';

type AuthResponse = { token: string };

// Endpoints de /api/auth (template).
export const AuthService = {
  async login(payload: { email: string; password: string }) {
    const response = await api<AuthResponse>('/login', {
      method: 'POST',
      auth: false,
      baseUrl: AUTH_BASE_URL,
      body: JSON.stringify(payload),
    });
    authStorage.setToken(response.token);
  },

  logout() {
    authStorage.clear();
  },

  isAuthenticated: () => !!authStorage.getToken(),
};
