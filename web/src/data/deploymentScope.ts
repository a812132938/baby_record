export function normalizeDeploymentKey(value?: string, fallbackOrigin?: string) {
  const fallback = fallbackOrigin || (typeof location === 'undefined' ? 'same-origin' : location.origin);
  const raw = value?.trim() || fallback;
  try {
    let url: URL;
    try { url = new URL(raw); }
    catch { url = new URL(raw, fallback); }
    const pathname = url.pathname.replace(/\/+$/, '');
    return `${url.origin}${pathname}`;
  } catch {
    return raw.replace(/[?#].*$/, '').replace(/\/+$/, '') || raw;
  }
}
