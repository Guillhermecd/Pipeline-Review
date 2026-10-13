import { ScreenHeader } from '../../components/ui/ScreenHeader';
import { percent } from '../../utils/format';
import { ReviewDetail } from './ReviewDetail';
import { ReviewList } from './ReviewList';
import { useReviewQueue } from './useReviewQueue';

export function ReviewPage() {
  const { queue, selected, threshold, select, approve, reject } = useReviewQueue();

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <ScreenHeader eyebrow={`LIMIAR DE CONFIANÇA ${percent(threshold)}`} title="Fila de revisão" />
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit,minmax(min(100%,440px),1fr))',
          gap: 16,
          alignItems: 'start',
        }}
      >
        <ReviewList
          queue={queue}
          selectedId={selected?.id ?? null}
          threshold={threshold}
          onSelect={select}
        />
        {selected && (
          <ReviewDetail
            doc={selected}
            threshold={threshold}
            onApprove={() => approve(selected)}
            onReject={() => reject(selected)}
          />
        )}
      </div>
    </div>
  );
}
