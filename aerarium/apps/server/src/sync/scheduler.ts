import type { ConnectionsStore } from '../store/connections.ts';
import type { Logger } from '../log.ts';
import type { SyncEngine } from './engine.ts';

export interface SchedulerDeps {
  connections: ConnectionsStore;
  engine: SyncEngine;
  log: Logger;
  clock: () => Date;
  onTick?: (now: Date) => void;
}

const HOUR = 3_600_000;

export class Scheduler {
  private timer: NodeJS.Timeout | null = null;
  private lastTick: string | null = null;
  private ticking = false;

  constructor(private readonly deps: SchedulerDeps) {}

  lastTickAt(): string | null {
    return this.lastTick;
  }

  start(everyMs = 60_000): void {
    void this.tick();
    this.timer = setInterval(() => void this.tick(), everyMs);
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  async tick(): Promise<void> {
    const { connections, engine, log, clock } = this.deps;
    const now = clock();
    this.lastTick = now.toISOString();
    if (this.ticking) {
      log.debug('dewidebug scheduler tick skipped: previous tick still running');
      return;
    }
    this.ticking = true;
    try {
      this.deps.onTick?.(now);
      for (const c of connections.synced()) {
        const connector = engine.connectorFor(c);
        if (!connector) continue;
        const last = connections.lastRun(c.id);
        const lastStart = last ? Date.parse(last.startedAt) : 0;
        const dueIn = lastStart + connector.everyHours * HOUR - now.getTime();
        const retryAfterFailure =
          last?.outcome === 'failed' &&
          now.getTime() - lastStart >= connector.retryAfterHours * HOUR;
        if (dueIn <= 0 || retryAfterFailure) {
          await engine.run(
            c,
            last === null
              ? 'first sync'
              : retryAfterFailure && dueIn > 0
                ? 'retry after a failure'
                : 'due',
          );
        }
      }
    } catch (error) {
      log.error({ err: error }, 'dewidebug scheduler tick failed');
    } finally {
      this.ticking = false;
    }
  }
}
