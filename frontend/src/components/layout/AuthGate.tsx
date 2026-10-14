import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { AuthService } from '../../api/modules/auth.service';
import { IS_LIVE, LOGIN_PATH } from '../../config';
import { LoginPage } from '../../pages/LoginPage';

// No modo live toda a aplicação exige login; no mock não existe sessão.
export function RequireAuth({ children }: { children: ReactNode }) {
  if (IS_LIVE && !AuthService.isAuthenticated()) return <Navigate to={LOGIN_PATH} replace />;
  return children;
}

// Quem já entrou, ou está no modo mock, não tem o que fazer na tela de login.
export function LoginRoute() {
  if (!IS_LIVE || AuthService.isAuthenticated()) return <Navigate to="/" replace />;
  return <LoginPage />;
}
