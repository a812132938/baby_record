import React from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import './styles.css';
import {
  activateLocalScope,
  bootstrapLocalDb,
  clearActiveLocalData,
  deactivateLocalScope,
  loadLocalSnapshot,
  saveEvents,
  savePendingActions,
  saveProfile,
} from './data/localDb';
import { bindAppInfraSignals } from './stores/appStore';
import { API_BASE, apiFetch, queryApi } from './api/client';
import { queryClient } from './api/queryClient';

bindAppInfraSignals();

await bootstrapLocalDb(API_BASE || globalThis.location?.origin || 'same-origin');
const snapshot = await loadLocalSnapshot();
(window as any).__BABY_INITIAL_SNAPSHOT__ = snapshot;
(window as any).__BABY_RUNTIME_PENDING__ = snapshot.pending;
(window as any).__babyLocalRepo = {
  activateScope: activateLocalScope,
  clearScope: clearActiveLocalData,
  deactivateScope: deactivateLocalScope,
  saveEvents,
  savePending: savePendingActions,
  saveProfile,
};
(window as any).__babyApi = { apiBase: API_BASE, apiFetch, queryApi };

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>,
);
