import { MockPipelineEngine } from './engine';
import { SAMPLES } from './samples';

const numberFromEnv = (value: string | undefined, fallback: number) => {
  const parsed = Number(value);
  return value && !Number.isNaN(parsed) ? parsed : fallback;
};

export const mockEngine = new MockPipelineEngine(
  {
    threshold: numberFromEnv(import.meta.env.VITE_MOCK_THRESHOLD, 0.85),
    failureRate: numberFromEnv(import.meta.env.VITE_MOCK_FAILURE_RATE, 0.25),
  },
  SAMPLES,
);

export { SAMPLES };
