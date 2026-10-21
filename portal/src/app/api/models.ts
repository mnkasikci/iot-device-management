// Shapes of the backend's JSON (see backend `api` package and PLAN.md wire protocol).

export interface TelemetryPoint {
  ts: number; // epoch millis
  data: Record<string, unknown>;
}

export interface DeviceSummary {
  id: string;
  deviceType: string | null;
  firmware: string | null;
  online: boolean;
  firstSeen: string | null; // ISO instant
  lastSeen: string | null;
  latest: TelemetryPoint | null;
}

export interface DeviceDetail {
  device: DeviceSummary;
  history: TelemetryPoint[]; // oldest first
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Stats {
  connected: number;
  totalDevices: number;
  messagesReceived: number;
  messagesPerSecond: number;
}

export type CommandStatus = 'PENDING' | 'SENT' | 'ACKED' | 'FAILED' | 'TIMEOUT';

export interface Command {
  id: string;
  deviceId: string;
  name: string;
  params: Record<string, unknown>;
  status: CommandStatus;
  message: string | null;
  createdAt: string;
  sentAt: string | null;
  completedAt: string | null;
}

/** One SSE message from `GET /api/stream`: only what changed in the last second. */
export interface Batch {
  ts: number;
  stats: Stats;
  devices: DeviceSummary[];
  commands: Command[];
}
