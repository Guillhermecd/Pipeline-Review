import { PipelineService } from '../../api/modules/pipeline.service';
import { Button } from '../../components/ui/Button';
import { Dot } from '../../components/ui/Primitives';
import { ScreenHeader } from '../../components/ui/ScreenHeader';
import { usePipeline } from '../../hooks/usePipeline';
import { color, mono } from '../../theme';
import { EventsCard } from './EventsCard';
import { FlowSection } from './FlowSection';
import { BrokerCard, DocumentsCard, EnvelopeCard, MetricsStrip } from './PipelinePanels';
import { IS_LIVE } from '../../config';
import { usePipelineView } from './usePipelineView';

const SIMULATOR_URL = import.meta.env.VITE_MESSAGING_SIMULATOR_URL ?? 'ws://localhost:8090/events';

function SimulatorPill({ connected, mirrored }: { connected: boolean; mirrored: number }) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        flexWrap: 'wrap',
        background: color.panel,
        border: `1px solid ${color.line}`,
        borderRadius: 8,
        padding: '6px 6px 6px 12px',
      }}
    >
      <Dot fill={connected ? color.ok : color.line3} />
      <span style={{ fontSize: 13 }}>Simulador de mensageria</span>
      <span style={{ ...mono(11.5), color: color.muted }}>
        {connected ? `${SIMULATOR_URL} · ${mirrored} eventos espelhados` : 'desconectado'}
      </span>
      {/* No modo live o espelhamento é ligado no backend (fase F6), não pela tela. */}
      {!IS_LIVE && (
        <Button
          onClick={PipelineService.toggleSimulator}
          style={{ fontSize: 12.5, padding: '6px 11px', borderRadius: 5 }}
        >
          {connected ? 'Desconectar' : 'Conectar'}
        </Button>
      )}
    </div>
  );
}

export function PipelinePage() {
  const snapshot = usePipeline();
  const { nodes, metrics, queues, outcomes } = usePipelineView(snapshot);
  const { broker } = snapshot;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <ScreenHeader
        eyebrow={`AO VIVO · ${broker.label}`}
        title="Pipeline de extração"
        aside={
          <SimulatorPill
            connected={snapshot.simulatorConnected}
            mirrored={snapshot.mirroredEvents}
          />
        }
      />
      <MetricsStrip metrics={metrics} />
      <FlowSection nodes={nodes} outcomes={outcomes} />
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit,minmax(340px,1fr))',
          gap: 12,
          alignItems: 'start',
        }}
      >
        <DocumentsCard documents={snapshot.documents} />
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <BrokerCard label={broker.label} consumerConfig={broker.consumerConfig} queues={queues} />
          <EnvelopeCard envelope={snapshot.lastEnvelope} />
        </div>
      </div>
      <EventsCard events={snapshot.events} />
    </div>
  );
}
