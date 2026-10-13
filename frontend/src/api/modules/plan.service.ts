import {
  DEFAULT_COMPLETED_TASKS,
  INTEGRATION_TESTS,
  PHASES,
  REQUIREMENTS,
} from '../mock/plan-fixtures';

export type { IntegrationTest, PlanPhase, PlanTask, Requirement } from '../mock/plan-fixtures';

export type TestStatus = 'idle' | 'queued' | 'running' | 'passed';
export type TestRun = { status: TestStatus; duration?: string };
export type PlanSnapshot = { completedTasks: string[]; testRuns: TestRun[]; running: boolean };

const STORAGE_KEY = 'pipefiscal.tasks.v1';
const TEST_STEP_MS = 700;

const loadCompleted = (): string[] => {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored ? (JSON.parse(stored) as string[]) : DEFAULT_COMPLETED_TASKS;
  } catch {
    return DEFAULT_COMPLETED_TASKS;
  }
};

const saveCompleted = (tasks: string[]) => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(tasks));
  } catch (error) {
    console.warn('Não foi possível salvar as tarefas', error);
  }
};

const listeners = new Set<() => void>();
let snapshot: PlanSnapshot = {
  completedTasks: loadCompleted(),
  testRuns: INTEGRATION_TESTS.map(() => ({ status: 'idle' })),
  running: false,
};

const update = (patch: Partial<PlanSnapshot>) => {
  snapshot = { ...snapshot, ...patch };
  listeners.forEach((listener) => listener());
};

const setTestRun = (index: number, run: TestRun) =>
  update({ testRuns: snapshot.testRuns.map((current, i) => (i === index ? run : current)) });

const randomDuration = () => `${(Math.random() * 3 + 0.6).toFixed(1).replace('.', ',')}s`;

// Plano de execução. Hoje fica no localStorage; depois vira PATCH /api/v1/plano/tarefas/:id.
// A suíte é simulada aqui; com CI ligado, a tela só mostra o resultado do último run.
export const PlanService = {
  phases: PHASES,
  requirements: REQUIREMENTS,
  tests: INTEGRATION_TESTS,

  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },

  getSnapshot: () => snapshot,

  toggleTask(taskId: string) {
    const done = snapshot.completedTasks.includes(taskId);
    const completedTasks = done
      ? snapshot.completedTasks.filter((id) => id !== taskId)
      : [...snapshot.completedTasks, taskId];
    saveCompleted(completedTasks);
    update({ completedTasks });
  },

  runSuite() {
    if (snapshot.running) return;
    update({ running: true, testRuns: INTEGRATION_TESTS.map(() => ({ status: 'queued' })) });
    const runStep = (index: number) => {
      if (index >= INTEGRATION_TESTS.length) {
        update({ running: false });
        return;
      }
      setTestRun(index, { status: 'running' });
      setTimeout(() => {
        setTestRun(index, { status: 'passed', duration: randomDuration() });
        runStep(index + 1);
      }, TEST_STEP_MS);
    };
    runStep(0);
  },
};
