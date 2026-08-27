export type BreastSide = 'LEFT' | 'RIGHT';

export function normalizeBreastLastSide(leftSeconds: number, rightSeconds: number, preferred?: unknown): BreastSide {
  if (leftSeconds <= 0) return 'RIGHT';
  if (rightSeconds <= 0) return 'LEFT';
  return preferred === 'RIGHT' ? 'RIGHT' : 'LEFT';
}
