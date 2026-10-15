import { PipelineService } from '../../api/modules/pipeline.service';
import { AuthService } from '../../api/modules/auth.service';
import type { BrokerKind, SimulationSpeed } from '../../api/modules/types';
import { IS_LIVE, LOGIN_PATH } from '../../config';
import { usePipeline } from '../../hooks/usePipeline';
import { fixed, font, mono } from '../../theme';

const BROKER_OPTIONS: { kind: BrokerKind; label: string }[] = [
  { kind: 'kafka', label: 'Kafka' },
  { kind: 'rabbitmq', label: 'RabbitMQ' },
];

const outlined = {
  border: `1px solid ${fixed.sidebarBorder}`,
  background: 'transparent',
  color: fixed.sidebarText,
  cursor: 'pointer',
  padding: '7px 0',
  borderRadius: 6,
} as const;

// Rodapé da sidebar: broker ativo e, conforme o modo, controles da simulação ou saída da sessão.
export function SimulationControls() {
  const { broker, paused, speed, autoTraffic } = usePipeline();

  return (
    <div
      style={{
        marginTop: 'auto',
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
        padding: '14px 10px 0',
        borderTop: `1px solid ${fixed.sidebarActive}`,
      }}
    >
      <div style={{ ...mono(10.5), color: fixed.sidebarLabel, letterSpacing: '.08em' }}>BROKER</div>
      <div
        role="radiogroup"
        aria-label="Broker"
        style={{
          display: 'grid',
          gridTemplateColumns: '1fr 1fr',
          gap: 4,
          background: fixed.sidebarHover,
          padding: 3,
          borderRadius: 7,
        }}
      >
        {BROKER_OPTIONS.map((option) => {
          const active = broker.kind === option.kind;
          return (
            <button
              key={option.kind}
              type="button"
              role="radio"
              aria-checked={active}
              onClick={() => PipelineService.setBroker(option.kind)}
              style={{
                border: 0,
                cursor: 'pointer',
                font: 'inherit',
                fontSize: 12.5,
                padding: '6px 0',
                borderRadius: 5,
                background: active ? fixed.sidebarText : 'transparent',
                color: active ? fixed.sidebarBg : fixed.sidebarTextSoft,
              }}
            >
              {option.label}
            </button>
          );
        })}
      </div>
      {IS_LIVE ? (
        <LogoutButton />
      ) : (
        <MockControls paused={paused} speed={speed} autoTraffic={autoTraffic} />
      )}
    </div>
  );
}

function LogoutButton() {
  const logout = () => {
    AuthService.logout();
    window.location.assign(LOGIN_PATH);
  };
  return (
    <button
      type="button"
      onClick={logout}
      style={{ ...outlined, fontFamily: font.sans, fontSize: 12.5 }}
    >
      Sair
    </button>
  );
}

type MockControlsProps = { paused: boolean; speed: SimulationSpeed; autoTraffic: boolean };

// Pausa, velocidade e tráfego automático só fazem sentido na simulação.
function MockControls({ paused, speed, autoTraffic }: MockControlsProps) {
  return (
    <>
      <div style={{ display: 'flex', gap: 6 }}>
        <button
          type="button"
          onClick={PipelineService.togglePause}
          style={{ ...outlined, flex: 1, fontFamily: font.sans, fontSize: 12.5 }}
        >
          {paused ? 'Retomar' : 'Pausar'}
        </button>
        <button
          type="button"
          onClick={PipelineService.cycleSpeed}
          style={{ ...outlined, width: 52, ...mono(12) }}
          aria-label="Velocidade da simulação"
        >
          {speed}×
        </button>
      </div>
      <button
        type="button"
        onClick={PipelineService.toggleAutoTraffic}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          border: 0,
          background: 'transparent',
          color: fixed.sidebarTextSoft,
          cursor: 'pointer',
          font: 'inherit',
          fontSize: 12.5,
          padding: 0,
          textAlign: 'left',
        }}
      >
        <span
          style={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            background: autoTraffic ? fixed.dotOk : fixed.sidebarLabel,
          }}
        />
        {autoTraffic ? 'Tráfego automático ligado' : 'Tráfego automático desligado'}
      </button>
    </>
  );
}
