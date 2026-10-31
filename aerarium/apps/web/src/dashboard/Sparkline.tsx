import type { NetWorthPoint } from '@aerarium/shared';
import { tips } from '../strings.ts';

export function Sparkline({ points }: { points: readonly NetWorthPoint[] }) {
  const w = 600;
  const h = 120;
  const values = points.map((p) => p.household);
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const span = hi - lo || 1;
  const xy = points.map((p, i) => {
    const x = points.length === 1 ? w / 2 : (i / (points.length - 1)) * w;
    const y = h - 8 - ((p.household - lo) / span) * (h - 16);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });
  return (
    <svg
      className="sparkline"
      viewBox={`0 0 ${String(w)} ${String(h)}`}
      role="img"
      aria-label={tips.history}
    >
      <polyline
        points={xy.join(' ')}
        fill="none"
        stroke="var(--brand)"
        strokeWidth="3"
        strokeLinejoin="round"
      />
    </svg>
  );
}
