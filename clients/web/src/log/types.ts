export interface LogDraw { for?: string; dist: string; value: unknown; [key: string]: unknown }
export interface LogChange { id: string; type: string; op: string; version?: number|null; state?: Record<string, unknown>; parent?: string }
export interface LogAction { type: string; params?: Record<string, unknown>; start?: number; end?: number; effect?: string; seq?: number }
export interface LogEntry { seq: number; area: string; time: number; wallTime?: string; kind: string; cause: number|null; root: number; resource: string|null; action?: LogAction; effect?: { type: string; params?: Record<string, unknown> }; result?: unknown; reason?: string; draws: LogDraw[]; changes: LogChange[] }
export interface LogPage { entries: LogEntry[]; first: number|null; last: number|null }