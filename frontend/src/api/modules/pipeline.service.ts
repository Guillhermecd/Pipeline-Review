import { mockEngine } from '../mock';
import type { BrokerKind } from './types';

// Estado ao vivo do pipeline. No backend real: GET /documentos + stream /eventos (SSE).
export const PipelineService = {
  subscribe: mockEngine.subscribe,
  getSnapshot: mockEngine.getSnapshot,

  setBroker(kind: BrokerKind) {
    mockEngine.setBroker(kind);
  },
  togglePause() {
    mockEngine.togglePause();
  },
  cycleSpeed() {
    mockEngine.cycleSpeed();
  },
  toggleAutoTraffic() {
    mockEngine.toggleAutoTraffic();
  },
  toggleSimulator() {
    mockEngine.toggleSimulator();
  },
};
