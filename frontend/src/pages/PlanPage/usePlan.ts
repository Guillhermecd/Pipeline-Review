import { useSyncExternalStore } from 'react';
import { PlanService } from '../../api/modules/plan.service';
import { fixed } from '../../theme';

export type RequirementProgress = { text: string; status: string; dot: string };

export function usePlan() {
  const snapshot = useSyncExternalStore(PlanService.subscribe, PlanService.getSnapshot);
  const done = new Set(snapshot.completedTasks);

  const phases = PlanService.phases.map((phase) => ({
    ...phase,
    done: phase.tasks.filter((task) => done.has(task.id)).length,
  }));

  const allTasks = PlanService.phases.flatMap((phase) => phase.tasks);
  const completed = allTasks.filter((task) => done.has(task.id)).length;

  const requirements: RequirementProgress[] = PlanService.requirements.map((requirement) => {
    const linked = allTasks.filter((task) => task.requirement === requirement.key);
    const finished = linked.filter((task) => done.has(task.id)).length;
    const complete = finished === linked.length;
    return {
      text: requirement.text,
      status: complete
        ? `concluído · ${finished}/${linked.length} tarefas`
        : `${finished}/${linked.length} tarefas`,
      dot: complete ? fixed.dotOk : finished > 0 ? fixed.dotPartial : fixed.dotNone,
    };
  });

  const passed = snapshot.testRuns.filter((run) => run.status === 'passed').length;

  return {
    phases,
    isDone: (taskId: string) => done.has(taskId),
    completed,
    total: allTasks.length,
    requirements,
    tests: PlanService.tests.map((test, index) => ({ ...test, run: snapshot.testRuns[index] })),
    testsSummary: passed
      ? `${passed}/${PlanService.tests.length} passaram`
      : `${PlanService.tests.length} testes`,
    running: snapshot.running,
    toggleTask: PlanService.toggleTask,
    runSuite: PlanService.runSuite,
  };
}
