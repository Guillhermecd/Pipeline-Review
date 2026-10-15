import { useEffect, useState } from 'react';
import { TraceService } from '../../api/modules/trace.service';
import type { PipelineDocument, Trace } from '../../api/modules/types';

const REFRESH_MS = 3000;

type TraceState = { documentId: string; trace: Trace | null; error: string | null };

// Busca os spans do documento. Enquanto ele ainda está no pipeline, ou o trace ainda não chegou
// ao Jaeger, consulta de novo a cada poucos segundos e a cada mudança do documento.
export function useTrace(doc: PipelineDocument | undefined) {
  const [state, setState] = useState<TraceState | null>(null);
  const loaded = state && doc && state.documentId === doc.id ? state : null;
  const incomplete = !!doc && (!doc.outcome || !loaded?.trace);

  useEffect(() => {
    if (!doc) return;
    let cancelled = false;
    const load = () =>
      TraceService.get(doc)
        .then((trace) => {
          if (!cancelled) setState({ documentId: doc.id, trace, error: null });
        })
        .catch((error: unknown) => {
          const message = error instanceof Error ? error.message : 'Falha ao consultar o trace';
          if (!cancelled) setState({ documentId: doc.id, trace: null, error: message });
        });
    void load();
    const timer = incomplete ? setInterval(load, REFRESH_MS) : null;
    return () => {
      cancelled = true;
      if (timer) clearInterval(timer);
    };
  }, [doc, incomplete]);

  return { trace: loaded?.trace ?? null, error: loaded?.error ?? null };
}
