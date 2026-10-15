import { IS_LIVE } from '../../config';
import { liveActions } from '../live/live-actions';
import { mockEngine } from '../mock';
import type { PipelineDocument, Trace } from './types';

// GET /traces/:traceId. `null` quer dizer que o trace ainda não está disponível.
export const TraceService = {
  get(doc: PipelineDocument): Promise<Trace | null> {
    return IS_LIVE
      ? liveActions.getTrace(doc.traceId)
      : Promise.resolve(mockEngine.getTrace(doc.id));
  },
};
