import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { JsonPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { DeviceApi } from '../api/device-api';
import { LiveStream } from '../api/live-stream';
import { Batch, Command, DeviceSummary, TelemetryPoint } from '../api/models';
import { typeSpec } from '../device-types';
import { Sparkline } from '../shared/sparkline';
import { timeAgo } from '../shared/time-ago';
import { CommandPanel } from './command-panel';

const HISTORY_SIZE = 100; // same as the backend's ring buffer

/**
 * One device: live values with sparklines, command panel, command history.
 * Loads once via REST, then merges what the SSE stream reports for this device.
 */
@Component({
  selector: 'app-device-detail',
  imports: [RouterLink, Sparkline, CommandPanel, JsonPipe],
  templateUrl: './device-detail.html',
})
export class DeviceDetail {
  private readonly api = inject(DeviceApi);
  protected readonly live = inject(LiveStream);

  /** Route parameter `:id` (bound by `withComponentInputBinding`). */
  readonly id = input.required<string>();

  readonly device = signal<DeviceSummary | null>(null);
  readonly history = signal<TelemetryPoint[]>([]);
  readonly commands = signal<Command[]>([]);
  readonly error = signal<string | null>(null);

  readonly spec = computed(() => typeSpec(this.device()?.deviceType ?? null));
  /** One sparkline per numeric metric of this device type. */
  readonly series = computed(() =>
    this.spec().metrics.map((metric) => ({
      metric,
      values: this.history()
        .map((p) => p.data[metric.key])
        .filter((v): v is number => typeof v === 'number'),
    })),
  );

  protected readonly timeAgo = timeAgo;

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.load(id));
    });
    effect(() => {
      const batch = this.live.lastBatch();
      if (batch) untracked(() => this.apply(batch));
    });
  }

  onSent(command: Command): void {
    this.mergeCommands([command]);
  }

  private load(id: string): void {
    // Reset first: the template (and the command panel in it) is rebuilt for the new device.
    this.device.set(null);
    this.history.set([]);
    this.commands.set([]);
    this.error.set(null);
    this.api.get(id).subscribe({
      next: (detail) => {
        this.device.set(detail.device);
        this.history.set(detail.history);
      },
      error: (e) =>
        this.error.set(e.status === 404 ? `Unknown device ${id}` : 'Could not load device'),
    });
    this.api.commands(id).subscribe({ next: (commands) => this.mergeCommands(commands) });
  }

  private apply(batch: Batch): void {
    const id = this.id();
    const changed = batch.devices.find((d) => d.id === id);
    if (changed && this.device()) {
      this.device.set(changed);
      const latest = changed.latest;
      // The stream carries only the latest point per second; append it if it's new.
      if (latest && latest.ts !== this.history().at(-1)?.ts) {
        this.history.update((h) => [...h, latest].slice(-HISTORY_SIZE));
      }
    }
    const commands = batch.commands.filter((c) => c.deviceId === id);
    if (commands.length > 0) this.mergeCommands(commands);
  }

  /** Insert or replace by id, newest first. */
  private mergeCommands(updates: Command[]): void {
    this.commands.update((current) => {
      const byId = new Map(current.map((c) => [c.id, c]));
      updates.forEach((c) => byId.set(c.id, c));
      return [...byId.values()].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));
    });
  }
}
