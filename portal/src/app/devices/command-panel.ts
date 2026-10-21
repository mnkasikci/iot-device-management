import { Component, computed, inject, input, output, signal } from '@angular/core';
import { DeviceApi } from '../api/device-api';
import { Command } from '../api/models';
import { CommandSpec, ParamSpec, typeSpec } from '../device-types';

/**
 * Buttons + inputs for the commands of one device type (from `device-types.ts`), plus a free-form
 * "custom" command — handy to try a command the portal doesn't know yet (the device acks ERROR
 * if it doesn't either).
 */
@Component({
  selector: 'app-command-panel',
  templateUrl: './command-panel.html',
})
export class CommandPanel {
  private readonly api = inject(DeviceApi);

  readonly deviceId = input.required<string>();
  readonly deviceType = input.required<string | null>();
  readonly online = input.required<boolean>();
  /** Emitted with the backend's answer (SENT or FAILED); the final status arrives via the stream. */
  readonly sent = output<Command>();

  readonly commands = computed(() => typeSpec(this.deviceType()).commands);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);

  /**
   * Current input values, keyed "device.command.param" (the device is part of the key because the
   * component is reused when navigating between devices). Not rendered from, so a plain object is enough.
   */
  private readonly values: Record<string, unknown> = {};

  private inputId(command: CommandSpec, param: ParamSpec): string {
    return `${this.deviceId()}.${command.name}.${param.name}`;
  }

  setValue(command: CommandSpec, param: ParamSpec, raw: string | boolean): void {
    this.values[this.inputId(command, param)] = param.kind === 'number' ? Number(raw) : raw;
  }

  send(command: CommandSpec): void {
    const params: Record<string, unknown> = {};
    for (const p of command.params) {
      params[p.name] = this.values[this.inputId(command, p)] ?? p.default;
    }
    this.post(command.name, params);
  }

  sendCustom(name: string, paramsJson: string): void {
    let params: Record<string, unknown>;
    try {
      params = paramsJson.trim() ? JSON.parse(paramsJson) : {};
    } catch {
      this.error.set('Params must be JSON, e.g. {"seconds": 5}');
      return;
    }
    this.post(name.trim(), params);
  }

  private post(name: string, params: Record<string, unknown>): void {
    if (!name) {
      this.error.set('Command needs a name');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.sendCommand(this.deviceId(), name, params).subscribe({
      next: (command) => {
        this.busy.set(false);
        this.sent.emit(command);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(`Sending failed (${e.status ?? 'network'})`);
      },
    });
  }
}
