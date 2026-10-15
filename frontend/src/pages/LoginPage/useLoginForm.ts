import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '../../api/modules/api';
import { AuthService } from '../../api/modules/auth.service';

type LoginFormValues = {
  email: string;
  password: string;
};

const UNAUTHORIZED = 401;

// A API de login vem do template e responde em inglês; a tela fala português.
const messageFor = (error: unknown) => {
  if (error instanceof ApiError && error.status === UNAUTHORIZED)
    return 'E-mail ou senha inválidos';
  if (error instanceof ApiError) return error.message;
  return 'Não foi possível falar com a API. Ela está no ar?';
};

export function useLoginForm() {
  const { control, handleSubmit, formState } = useForm<LoginFormValues>({
    defaultValues: { email: '', password: '' },
  });
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const onSubmit = handleSubmit(async (values) => {
    setError(null);
    setLoading(true);
    try {
      await AuthService.login(values);
      navigate('/', { replace: true });
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setLoading(false);
    }
  });

  return { control, onSubmit, error, loading, errors: formState.errors };
}
