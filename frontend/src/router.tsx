import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AppShell } from './components/layout/AppShell';
import { DlqPage } from './pages/DlqPage';
import { PipelinePage } from './pages/PipelinePage';
import { PlanPage } from './pages/PlanPage';
import { ReviewPage } from './pages/ReviewPage';
import { TracesPage } from './pages/TracesPage';
import { UploadPage } from './pages/UploadPage';

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/pipeline" replace /> },
      { path: 'plano', element: <PlanPage /> },
      { path: 'upload', element: <UploadPage /> },
      { path: 'pipeline', element: <PipelinePage /> },
      { path: 'revisao', element: <ReviewPage /> },
      { path: 'traces', element: <TracesPage /> },
      { path: 'traces/:documentCode', element: <TracesPage /> },
      { path: 'dlq', element: <DlqPage /> },
      { path: '*', element: <Navigate to="/pipeline" replace /> },
    ],
  },
]);
