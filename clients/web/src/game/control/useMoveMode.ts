import { useCallback, useEffect, useRef, useState } from 'react';
import { intentErrorMessage, postStep } from '../../api/intents.ts';
import { useAuth } from '../../auth/AuthProvider.tsx';
import { positionOf, type Point } from '../../components/shapes.ts';
import type { Resource } from '../../resources/cache.ts';
import { useClearAfter } from '../useWait.ts';
import type { OwnEntity } from '../turn.ts';
import { arrowTarget, isArrowKey, stepPreview, type StepPreview } from './stepPreview.ts';

/** What the map draws and tells in move mode, on the entity's turn (world units). */
export interface MoveOverlay {
  from: Point;
  /** The entity's `stepReach`: the circle; absent when the entity doesn't publish one. */
  reach: number | undefined;
  radius: number;
  preview: StepPreview | null;
  /** The point under the pointer, null when it leaves the map. */
  onHover: (target: Point | null) => void;
  /** A click on the map: step toward it. */
  onStep: (target: Point) => void;
}

export interface MoveMode {
  active: boolean;
  toggle: () => void;
  /** Null outside move mode, and when it isn't the entity's turn. */
  overlay: MoveOverlay | null;
  busy: boolean;
  error: string | null;
}

function numberOf(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

interface Outcome {
  busy: boolean;
  error: string | null;
  /** The entity version the last accepted step left it at; the next waits for the view to hold it. */
  settled: number;
}

/**
 * Posting `step` for the entity on the version the view holds, one post at a time; `ready` once
 * the view has caught up with the last one.
 */
function useStepPost(entity: Resource | undefined) {
  const { authRequest } = useAuth();
  const [outcome, setOutcome] = useState<Outcome>({ busy: false, error: null, settled: 0 });
  const id = entity?.id;
  const version = entity?.version;
  const post = useCallback(
    (to: Point) => {
      if (id === undefined || version === undefined) return;
      setOutcome((o) => ({ ...o, busy: true, error: null }));
      postStep(authRequest, id, to, version).then(
        (result) => setOutcome({ busy: false, error: null, settled: result.version }),
        (err: unknown) =>
          setOutcome((o) => ({ ...o, busy: false, error: intentErrorMessage(err) })),
      );
    },
    [authRequest, id, version],
  );
  const clearError = useCallback(() => setOutcome((o) => ({ ...o, error: null })), []);
  useClearAfter(outcome.error, clearError);
  const ready = !outcome.busy && (version ?? 0) >= outcome.settled;
  return { busy: outcome.busy, error: outcome.error, ready, post };
}

function isFormField(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && target.closest('input, select, textarea') !== null;
}

function isPlainKey(e: KeyboardEvent): boolean {
  return !(e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey || e.shiftKey);
}

/**
 * While move mode is on: Esc leaves it, and, when `onArrow` is given (the entity's turn), an arrow
 * key anywhere but a form field steps. The map's own arrow keys pan only outside that.
 */
function useMoveKeys(active: boolean, onArrow: ((key: string) => void) | null, leave: () => void) {
  const latest = useRef({ onArrow, leave });
  latest.current = { onArrow, leave };
  useEffect(() => {
    if (!active) return;
    const onKey = (e: KeyboardEvent) => {
      if (!isPlainKey(e) || isFormField(e.target)) return;
      if (e.key === 'Escape') latest.current.leave();
      if (!isArrowKey(e.key) || !latest.current.onArrow) return;
      e.preventDefault();
      latest.current.onArrow(e.key);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [active]);
}

interface Armed {
  entity: Resource;
  from: Point;
}

/** The entity and its position, when move mode can step now: on, the entity's turn, placed. */
function armedOf(active: boolean, own: OwnEntity | null): Armed | null {
  if (!active || !own?.myTurn) return null;
  const from = positionOf(own.entity);
  return from ? { entity: own.entity, from } : null;
}

function previewOf({ entity, from }: Armed, target: Point): StepPreview | null {
  return stepPreview(from, target, numberOf(entity.stepReach), numberOf(entity.speed));
}

/**
 * Move mode for the user's entity: the action bar's Move toggles it; on the entity's turn the map
 * shows its reach and a preview of the step under the pointer, a click steps there (pulled back
 * to the reach) and an arrow key steps 1 m.
 */
export function useMoveMode(own: OwnEntity | null): MoveMode {
  const [active, setActive] = useState(false);
  const [target, setTarget] = useState<Point | null>(null);
  const step = useStepPost(own?.entity);
  const armed = armedOf(active, own);
  const stepTo = (to: Point | null) => {
    const preview = armed && to && step.ready ? previewOf(armed, to) : null;
    if (preview) step.post(preview.to);
  };
  const onArrow = armed ? (key: string) => stepTo(arrowTarget(key, armed.from)) : null;
  useMoveKeys(active, onArrow, () => setActive(false));
  const overlay: MoveOverlay | null = armed && {
    from: armed.from,
    reach: numberOf(armed.entity.stepReach),
    radius: numberOf(armed.entity.radius) ?? 0,
    preview: target && previewOf(armed, target),
    onHover: setTarget,
    onStep: stepTo,
  };
  const toggle = () => setActive((a) => !a);
  return { active, toggle, overlay, busy: step.busy, error: step.error };
}
