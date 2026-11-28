import { useRef, useState, type KeyboardEvent, type PointerEvent, type WheelEvent } from 'react';
import {
  FOLLOW_ZOOM,
  boxString,
  centreBox,
  centreOf,
  panBox,
  pixelsPerUnit,
  unitAt,
  zoomBox,
  type Box,
  type Point,
  type Rect,
} from './viewport.ts';

const DRAG_THRESHOLD_PX = 4; // a pointer that moves less than this is a click
const KEY_STEP = 0.1; // of the view's size, per arrow key
const ZOOM_STEP = 1.25;
const NO_RECT: Rect = { left: 0, top: 0, width: 0, height: 0 };

interface Drag {
  pointerId: number;
  x: number;
  y: number;
  moved: boolean;
}

/** Pans by what the pointer moved since the last step; takes the pointer once a drag begins. */
function dragStep(
  d: Drag | null,
  e: PointerEvent<SVGSVGElement>,
  panByPixels: (dx: number, dy: number) => void,
) {
  if (!d || d.pointerId !== e.pointerId) return;
  if (!d.moved && Math.hypot(e.clientX - d.x, e.clientY - d.y) < DRAG_THRESHOLD_PX) return;
  if (!d.moved) e.currentTarget.setPointerCapture?.(e.pointerId);
  d.moved = true;
  panByPixels(e.clientX - d.x, e.clientY - d.y);
  d.x = e.clientX;
  d.y = e.clientY;
}

/** Drag-to-pan. A drag that moved swallows the click that ends it, so it never selects or clears. */
function useDragPan(panByPixels: (dx: number, dy: number) => void) {
  const drag = useRef<Drag | null>(null);
  const swallowClick = useRef(false);
  const onPointerDown = (e: PointerEvent<SVGSVGElement>) => {
    if (e.button !== 0) return;
    swallowClick.current = false;
    drag.current = { pointerId: e.pointerId, x: e.clientX, y: e.clientY, moved: false };
  };
  const onPointerMove = (e: PointerEvent<SVGSVGElement>) => {
    dragStep(drag.current, e, panByPixels);
  };
  const onPointerEnd = () => {
    if (drag.current?.moved) swallowClick.current = true;
    drag.current = null;
  };
  const onClickCapture = (e: { stopPropagation: () => void }) => {
    if (!swallowClick.current) return;
    swallowClick.current = false;
    e.stopPropagation();
  };
  return {
    onPointerDown,
    onPointerMove,
    onPointerUp: onPointerEnd,
    onPointerCancel: onPointerEnd,
    onClickCapture,
  };
}

const ARROWS: Record<string, [number, number]> = {
  ArrowLeft: [-1, 0],
  ArrowRight: [1, 0],
  ArrowUp: [0, -1],
  ArrowDown: [0, 1],
};

interface ViewActions {
  panByUnits: (dx: number, dy: number) => void;
  zoomAround: (factor: number, focus?: Point) => void;
  fit: () => void;
}

/**
 * Arrow keys pan (unless `arrows` is off), `+`/`-` zoom, `0` fits; returns whether the key was
 * one of them.
 */
function viewKey(key: string, box: Box, actions: ViewActions, arrows: boolean): boolean {
  const arrow = arrows ? ARROWS[key] : undefined;
  if (arrow) actions.panByUnits(arrow[0] * box.w * KEY_STEP, arrow[1] * box.h * KEY_STEP);
  else if (key === '+' || key === '=') actions.zoomAround(ZOOM_STEP);
  else if (key === '-') actions.zoomAround(1 / ZOOM_STEP);
  else if (key === '0') actions.fit();
  else return false;
  return true;
}

/** What the view centres on: the followed entity's point, and how to stop following it. */
export interface MapFollow {
  point: Point;
  /** How far round the point to show, in user units (the entity's `vision`); absent: `FOLLOW_ZOOM`. */
  radius?: number;
  stop: () => void;
}

/** The size following starts at: the radius framed on the view's shorter side, else magnified. */
function followSize(base: Box, follow: MapFollow): Box {
  const r = follow.radius;
  if (r !== undefined && r > 0) return { x: 0, y: 0, w: 2 * r, h: 2 * r };
  return zoomBox(base, base, FOLLOW_ZOOM, centreOf(base));
}

function useViewState(base: Box, follow: MapFollow | null) {
  const [custom, setCustom] = useState<Box | null>(null);
  // Following centres the view on the entity at the user's zoom (framed to its vision, or
  // magnified, when it was fitted).
  const sized = custom ?? (follow ? followSize(base, follow) : base);
  const box = follow ? centreBox(sized, follow.point) : sized;
  const edit = (fn: (b: Box) => Box) => setCustom(fn(box));
  const actions: ViewActions = {
    // A manual pan ends following; zooming keeps it.
    panByUnits: (dx, dy) => {
      follow?.stop();
      edit((b) => panBox(b, base, dx, dy));
    },
    zoomAround: (factor, focus) => edit((b) => zoomBox(b, base, factor, focus ?? centreOf(b))),
    fit: () => {
      follow?.stop();
      setCustom(null);
    },
  };
  return { box, isFitted: custom === null && follow === null, actions };
}

/**
 * The map's view: a viewBox the user pans (drag, arrow keys) and zooms (wheel and trackpad pinch,
 * plus and minus keys, the buttons). No custom box means fitted: the whole room, following its size.
 * `arrowKeys: false` leaves the arrows to someone else (move mode steps with them).
 */
export function useMapView(base: Box, follow: MapFollow | null = null, arrowKeys = true) {
  const { box, isFitted, actions } = useViewState(base, follow);
  const svgRef = useRef<SVGSVGElement>(null);
  const rectOf = () => svgRef.current?.getBoundingClientRect() ?? NO_RECT;
  const drag = useDragPan((dx, dy) => {
    const ppu = pixelsPerUnit(rectOf(), box);
    actions.panByUnits(-dx / ppu, -dy / ppu);
  });
  const onWheel = (e: WheelEvent<SVGSVGElement>) => {
    const focus = unitAt(rectOf(), box, { x: e.clientX, y: e.clientY });
    actions.zoomAround(Math.exp(-e.deltaY * 0.002), focus);
  };
  const onKeyDown = (e: KeyboardEvent<SVGSVGElement>) => {
    if (viewKey(e.key, box, actions, arrowKeys)) e.preventDefault();
  };
  return {
    viewBox: boxString(box),
    /** The point under a client (screen) position, in the SVG's user units. */
    unitAt: (client: Point) => unitAt(rectOf(), box, client),
    svgProps: { ref: svgRef, ...drag, onWheel, onKeyDown },
    zoomIn: () => actions.zoomAround(ZOOM_STEP),
    zoomOut: () => actions.zoomAround(1 / ZOOM_STEP),
    fit: actions.fit,
    isFitted,
  };
}
