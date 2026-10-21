import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DeviceApi, DeviceFilter } from '../api/device-api';
import { LiveStream } from '../api/live-stream';
import { DeviceSummary } from '../api/models';
import { DEVICE_TYPES, keyValue, typeSpec } from '../device-types';
import { timeAgo } from '../shared/time-ago';

/**
 * Device table. Paging and filtering happen on the server (works for thousands of devices);
 * live changes from the SSE stream are merged into the rows of the current page.
 */
@Component({
  selector: 'app-device-list',
  imports: [RouterLink],
  templateUrl: './device-list.html',
})
export class DeviceList {
  private readonly api = inject(DeviceApi);
  protected readonly live = inject(LiveStream);

  readonly pageSize = 50;
  readonly types = Object.keys(DEVICE_TYPES);

  readonly online = signal<boolean | null>(null);
  readonly type = signal<string | null>(null);
  readonly search = signal('');
  readonly page = signal(0);

  readonly rows = signal<DeviceSummary[]>([]);
  readonly total = signal(0);
  readonly error = signal<string | null>(null);
  readonly pages = computed(() => Math.max(1, Math.ceil(this.total() / this.pageSize)));

  private lastTotalDevices: number | null = null;

  protected readonly keyValue = keyValue;
  protected readonly typeSpec = typeSpec;
  protected readonly timeAgo = timeAgo;

  constructor() {
    // (Re)load when a filter or the page changes. The short delay debounces typing in the search box.
    effect((onCleanup) => {
      const filter: DeviceFilter = {
        online: this.online(),
        type: this.type(),
        search: this.search(),
      };
      const page = this.page();
      const timer = setTimeout(() => this.load(filter, page), 200);
      onCleanup(() => clearTimeout(timer));
    });

    // Live: replace rows of this page that changed in the last second.
    effect(() => {
      const batch = this.live.lastBatch();
      if (!batch || batch.devices.length === 0) return;
      const changed = new Map(batch.devices.map((d) => [d.id, d]));
      untracked(() => this.rows.update((rows) => rows.map((row) => changed.get(row.id) ?? row)));
    });

    // A new device registered somewhere: reload so it can appear on this page.
    effect(() => {
      const totalDevices = this.live.stats()?.totalDevices;
      if (totalDevices === undefined) return;
      if (this.lastTotalDevices !== null && totalDevices !== this.lastTotalDevices) {
        untracked(() => this.reload());
      }
      this.lastTotalDevices = totalDevices;
    });
  }

  setOnline(value: string): void {
    this.online.set(value === '' ? null : value === 'true');
    this.page.set(0);
  }

  setType(value: string): void {
    this.type.set(value === '' ? null : value);
    this.page.set(0);
  }

  setSearch(value: string): void {
    this.search.set(value);
    this.page.set(0);
  }

  goTo(page: number): void {
    this.page.set(Math.min(Math.max(page, 0), this.pages() - 1));
  }

  private reload(): void {
    this.load({ online: this.online(), type: this.type(), search: this.search() }, this.page());
  }

  private load(filter: DeviceFilter, page: number): void {
    this.api.list(filter, page, this.pageSize).subscribe({
      next: (result) => {
        this.rows.set(result.items);
        this.total.set(result.total);
        this.error.set(null);
      },
      error: () => this.error.set('Could not load devices — is the backend running?'),
    });
  }
}
