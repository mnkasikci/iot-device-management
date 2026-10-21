import { Injectable, signal } from '@angular/core';
import { Batch, Stats } from './models';

/**
 * The one SSE connection to `GET /api/stream` (ADR-006). Components react to {@link lastBatch}
 * and merge the changed devices/commands into what they loaded via REST.
 * `EventSource` reconnects by itself if the backend restarts.
 */
@Injectable({ providedIn: 'root' })
export class LiveStream {
  readonly connected = signal(false);
  readonly stats = signal<Stats | null>(null);
  readonly lastBatch = signal<Batch | null>(null);
  /** Server time of the last batch — a clock that ticks once per second, used for "5 s ago". */
  readonly now = signal(Date.now());

  constructor() {
    if (typeof EventSource === 'undefined') return; // unit tests (jsdom) have no EventSource
    const source = new EventSource('/api/stream');
    source.onopen = () => this.connected.set(true);
    source.onerror = () => this.connected.set(false);
    source.onmessage = (event: MessageEvent<string>) => {
      const batch = JSON.parse(event.data) as Batch;
      this.connected.set(true);
      this.stats.set(batch.stats);
      this.now.set(batch.ts);
      this.lastBatch.set(batch);
    };
  }
}
