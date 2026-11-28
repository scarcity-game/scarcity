export interface LogDraw { for?: string; dist: string; value: unknown; [key: string]: unknown }
export interface LogChange { id: string; type: string; op: string; version?: number|null; state?: Record<string, unknown>; parent?: string }
/** Who sent an `intent`: a service (`principal`) or a user (`sub`). */
/** A resource an effect acted upon that had an action in progress; `interrupted` is false for one in recovery. */
export interface LogActedUpon { resource: string; interrupted: boolean }
export interface LogBy { kind: string; principal?: string; sub?: string }
export interface LogAction { type: string; params?: Record<string, unknown>; start?: number; end?: number; timing?: string; seq?: number }
/** `category`, `type` and `level`: how the entry reads among the messages (docs/log.md, "Messages"). */
export interface LogEntry { seq: number; area: string; time: number; wallTime?: string; kind: string; category?: string; type?: string; level?: number; cause: number|null; root: number; resource: string|null; actionInProgress?: LogAction; loggedEffect?: { type: string; params?: Record<string, unknown> }; actedUpon?: LogActedUpon[]; result?: unknown; controller?: LogBy; actionSpec?: { type: string; params?: Record<string, unknown> }; reported?: unknown; draws: LogDraw[]; changes: LogChange[] }
export interface LogPage { entries: LogEntry[]; first: number|null; last: number|null }