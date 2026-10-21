import { TelemetryPoint } from './api/models';

/**
 * Everything the portal knows about device types and commands — the one place to touch when a
 * device type or command is added (names/params match the wire-protocol table in PLAN.md).
 * Min/max are input hints only: the device validates and acks ERROR for bad values (ADR-013).
 */

export interface ParamSpec {
  name: string;
  label: string;
  kind: 'number' | 'boolean';
  default: number | boolean;
  min?: number;
  max?: number;
}

export interface CommandSpec {
  name: string;
  label: string;
  params: ParamSpec[];
}

export interface MetricSpec {
  key: string; // key in telemetry `data`
  label: string;
  unit: string;
}

export interface DeviceTypeSpec {
  label: string;
  metrics: MetricSpec[]; // numeric values: shown with a sparkline
  commands: CommandSpec[];
  /** Short text for the device list, e.g. "21.4 °C" or "on · 61 W". */
  keyValue(data: Record<string, unknown>): string;
}

const COMMON_COMMANDS: CommandSpec[] = [
  {
    name: 'setInterval',
    label: 'Telemetry interval',
    params: [{ name: 'seconds', label: 'seconds', kind: 'number', default: 5, min: 1, max: 3600 }],
  },
  { name: 'reboot', label: 'Reboot', params: [] },
];

export const DEVICE_TYPES: Record<string, DeviceTypeSpec> = {
  thermostat: {
    label: 'Thermostat',
    metrics: [
      { key: 'temperature', label: 'Temperature', unit: '°C' },
      { key: 'humidity', label: 'Humidity', unit: '%' },
      { key: 'targetTemp', label: 'Target', unit: '°C' },
    ],
    commands: [
      {
        name: 'setTargetTemp',
        label: 'Target temperature',
        params: [{ name: 'target', label: '°C', kind: 'number', default: 21, min: 5, max: 30 }],
      },
      ...COMMON_COMMANDS,
    ],
    keyValue: (d) => `${num(d['temperature'])} °C`,
  },
  relay: {
    label: 'Relay',
    metrics: [{ key: 'power', label: 'Power', unit: 'W' }],
    commands: [
      {
        name: 'setRelay',
        label: 'Relay',
        params: [{ name: 'on', label: 'on', kind: 'boolean', default: true }],
      },
      ...COMMON_COMMANDS,
    ],
    keyValue: (d) => `${d['on'] ? 'on' : 'off'} · ${num(d['power'])} W`,
  },
};

/** Unknown types (a newer device than this portal) still get the common commands. */
export function typeSpec(deviceType: string | null): DeviceTypeSpec {
  return (
    DEVICE_TYPES[deviceType ?? ''] ?? {
      label: deviceType ?? 'unknown',
      metrics: [],
      commands: COMMON_COMMANDS,
      keyValue: (d) => JSON.stringify(d),
    }
  );
}

export function keyValue(deviceType: string | null, latest: TelemetryPoint | null): string {
  return latest ? typeSpec(deviceType).keyValue(latest.data) : '—';
}

function num(value: unknown): string {
  return typeof value === 'number' ? value.toFixed(1) : '—';
}
