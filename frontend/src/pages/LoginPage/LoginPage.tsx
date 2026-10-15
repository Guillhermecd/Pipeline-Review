import { Alert, Button, Card, Form, Input, Typography } from 'antd';
import { Controller } from 'react-hook-form';
import { useTheme } from '../../hooks/useTheme';
import { color, cssVariables } from '../../theme';
import { useLoginForm } from './useLoginForm';

// Tela de login do template, usada só no modo live.
export function LoginPage() {
  const { control, onSubmit, error, loading, errors } = useLoginForm();
  const { mode } = useTheme();

  return (
    <div
      style={{
        // As variáveis de cor são definidas no AppShell, que esta tela não usa.
        ...cssVariables(mode),
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: color.bg,
        padding: 16,
      }}
    >
      <Card style={{ width: 360, maxWidth: '100%' }}>
        <Typography.Title level={3} style={{ marginTop: 0 }}>
          Pipeline Fiscal
        </Typography.Title>
        <form onSubmit={onSubmit}>
          <Form.Item
            label="E-mail"
            htmlFor="login-email"
            validateStatus={errors.email ? 'error' : ''}
            help={errors.email?.message}
          >
            <Controller
              name="email"
              control={control}
              rules={{ required: 'Informe o e-mail' }}
              render={({ field }) => (
                <Input {...field} id="login-email" type="email" autoComplete="email" />
              )}
            />
          </Form.Item>
          <Form.Item
            label="Senha"
            htmlFor="login-password"
            validateStatus={errors.password ? 'error' : ''}
            help={errors.password?.message}
          >
            <Controller
              name="password"
              control={control}
              rules={{ required: 'Informe a senha' }}
              render={({ field }) => (
                <Input.Password {...field} id="login-password" autoComplete="current-password" />
              )}
            />
          </Form.Item>
          {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}
          <Button type="primary" htmlType="submit" loading={loading} block>
            Entrar
          </Button>
        </form>
      </Card>
    </div>
  );
}
