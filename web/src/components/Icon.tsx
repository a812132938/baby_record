import type { ReactNode } from 'react';

export function Icon({ type, size = 24 }: { type: string; size?: number }) {
  const common = { width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };
  const paths: Record<string, ReactNode> = {
    milk: <g><path d="M8 3h8"/><path d="M9 3v3l-2 3v10a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2V9l-2-3V3"/><path d="M7 11h10"/></g>,
    moon: <path d="M20.8 15.2A8 8 0 0 1 8.8 3.2 8.4 8.4 0 1 0 20.8 15.2Z"/>,
    poop: <g><path d="M8 9c-1.9.2-3.3 1.6-3.3 3.4 0 .7.2 1.3.6 1.8A3.4 3.4 0 0 0 7.4 20h9.2a3.4 3.4 0 0 0 2.1-5.8c.4-.5.6-1.1.6-1.8 0-1.8-1.4-3.2-3.3-3.4"/><path d="M8 9c0-2 1.3-3.7 3.2-4.2-.2 1.4.4 2.8 1.7 3.5"/><path d="M9.2 14h.01M14.8 14h.01"/></g>,
    drop: <path d="M12 3s5 5.5 5 10a5 5 0 0 1-10 0c0-4.5 5-10 5-10Z"/>,
    plus: <g><path d="M12 5v14"/><path d="M5 12h14"/></g>,
    chevron: <path d="m9 18 6-6-6-6"/>,
    close: <g><path d="m6 6 12 12"/><path d="m18 6-12 12"/></g>,
    edit: <g><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z"/></g>,
    clock: <g><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></g>,
    chart: <g><path d="M4 19V9"/><path d="M10 19V5"/><path d="M16 19v-7"/><path d="M22 19H2"/></g>,
    family: <g><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></g>,
    cloud: <g><path d="M17.5 19H7a5 5 0 1 1 1.2-9.85A6 6 0 0 1 19.8 11 4 4 0 0 1 17.5 19Z"/><path d="m9.5 14 2 2 4-4"/></g>,
    copy: <g><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></g>,
    baby: <g><circle cx="12" cy="12" r="8"/><path d="M9 10h.01M15 10h.01"/><path d="M9.5 14.2c1.5 1.1 3.5 1.1 5 0"/><path d="M12 4c0-1.4 1-2.4 2.3-2.4"/></g>,
    devices: <g><rect x="3" y="4" width="13" height="10" rx="2"/><path d="M7 20h12a2 2 0 0 0 2-2V8"/><path d="M7 17h5"/></g>,
    trash: <g><path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="m19 6-1 14H6L5 6"/><path d="M10 11v5M14 11v5"/></g>,
    back: <path d="m15 18-6-6 6-6"/>,
    check: <path d="m5 12 4 4L19 6"/>,
    history: <g><path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 4v5h5"/><path d="M12 7v5l3 2"/></g>,
    send: <g><path d="m22 2-7 20-4-9-9-4Z"/><path d="M22 2 11 13"/></g>,
  };
  return <svg {...common}>{paths[type]}</svg>;
}
