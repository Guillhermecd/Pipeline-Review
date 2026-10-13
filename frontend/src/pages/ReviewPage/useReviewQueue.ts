import { useState } from 'react';
import { DocumentService } from '../../api/modules/document.service';
import type { PipelineDocument } from '../../api/modules/types';
import { usePipeline } from '../../hooks/usePipeline';
import { isPendingReview } from '../../utils/pipeline';

export function useReviewQueue() {
  const { documents, threshold } = usePipeline();
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const pending = documents.filter(isPendingReview);
  const reviewed = documents.filter((doc) => doc.outcome === 'review' && doc.review);
  const queue = [...pending, ...reviewed];
  const selected = queue.find((doc) => doc.id === selectedId) ?? pending[0] ?? queue[0] ?? null;

  const moveToNextPending = (current: PipelineDocument) => {
    const next = pending.find((doc) => doc.id !== current.id);
    setSelectedId(next?.id ?? null);
  };

  const approve = (doc: PipelineDocument) => {
    DocumentService.approve(doc.id);
    moveToNextPending(doc);
  };

  const reject = (doc: PipelineDocument) => {
    DocumentService.reject(doc.id);
    moveToNextPending(doc);
  };

  return { queue, selected, threshold, select: setSelectedId, approve, reject };
}
