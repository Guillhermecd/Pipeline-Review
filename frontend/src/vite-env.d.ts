/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_MOCK_THRESHOLD?: string;
  readonly VITE_MOCK_FAILURE_RATE?: string;
  readonly VITE_MESSAGING_SIMULATOR_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
