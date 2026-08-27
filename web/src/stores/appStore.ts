import { create } from 'zustand';

type AppInfraState = {
  online: boolean;
  standalone: boolean;
  setOnline: (online: boolean) => void;
  setStandalone: (standalone: boolean) => void;
};

export const useAppInfraStore = create<AppInfraState>((set) => ({
  online: navigator.onLine,
  standalone: window.matchMedia?.('(display-mode: standalone)').matches ?? false,
  setOnline: (online) => set({ online }),
  setStandalone: (standalone) => set({ standalone }),
}));

export function bindAppInfraSignals() {
  const online = () => useAppInfraStore.getState().setOnline(true);
  const offline = () => useAppInfraStore.getState().setOnline(false);
  const installed = () => useAppInfraStore.getState().setStandalone(true);
  window.addEventListener('online', online);
  window.addEventListener('offline', offline);
  window.addEventListener('appinstalled', installed);
  return () => {
    window.removeEventListener('online', online);
    window.removeEventListener('offline', offline);
    window.removeEventListener('appinstalled', installed);
  };
}
