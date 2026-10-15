import { IS_LIVE } from '../../config';
import { liveActions } from '../live/live-actions';
import { liveStore } from '../live/live-store';
import { mockEngine } from '../mock';
import type { BrokerKind } from './types';

const source = IS_LIVE ? liveStore : mockEngine;

// Estado ao vivo do pipeline. No modo live: snapshot + eventos do stream /eventos (SSE).
export const PipelineService = {
  subscribe: source.subscribe,
  getSnapshot: source.getSnapshot,

  // PUT /broker
  setBroker(kind: BrokerKind) {
    if (IS_LIVE) return void liveActions.setBroker(kind);
    mockEngine.setBroker(kind);
  },

  // Os controles abaixo são da simulação e só existem no modo mock.
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
