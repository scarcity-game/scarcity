export interface LogDraw { for?: string; dist: string; value: unknown; [key: string]: unknown }
export interface LogChange { id: string; type: string; op: string; version?: number|null; state?: Record<string, unknown>; parent?: string }
/** Who sent an `intent`: a service (`principal`) or a user (`sub`). */
export interface LogBy { kind: string; principal?: string; sub?: string }
export interface LogAction { type: string; params?: Record<string, unknown>; start?: number; end?: number; timing?: string; seq?: number }
export interface LogEntry { seq: number; area: string; time: number; wallTime?: string; kind: string; cause: number|null; root: number; resource: string|null; actionInProgress?: LogAction; loggedEffect?: { type: string; params?: Record<string, unknown> }; result?: unknown; controller?: LogBy; actionSpec?: { type: string; params?: Record<string, unknown> }; reported?: unknown; draws: LogDraw[]; changes: LogChange[] }
export interface LogPage { entries: LogEntry[]; first: number|null; last: number|null }