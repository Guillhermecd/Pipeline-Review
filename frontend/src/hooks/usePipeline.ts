import { useSyncExternalStore } from 'react';
import { PipelineService } from '../api/modules/pipeline.service';

export function usePipeline() {
  return useSyncExternalStore(PipelineService.subscribe, PipelineService.getSnapshot);
}
