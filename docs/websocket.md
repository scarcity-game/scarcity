# WebSocket protocol between `subscriptions` and clients

Owned by the root agent; decided 2026-11-11. Read this if your module serves or
consumes the subscription socket (`services/subscriptions`, the clients). The
version rules the client applies to what it receives are in
[`resources.md`](resources.md).

Endpoint: `wss://scarcity.pessimistic-it.com/api/subscriptions/ws`. JSON text
frames, one object per frame, each with a `type`. Unknown fields are ignored on
both sides. Browsers can't set headers on a WebSocket, so the JWT travels in
the first frame.

## Client → server
- `{"type":"auth","token":"<jwt>"}`: must be the first frame, within 5 s.
- `{"type":"subscribe","area":"area:…"}` / `{"type":"unsubscribe","area":"…"}`.
- `{"type":"subscribe","turns":true}` / `{"type":"unsubscribe","turns":true}`:
  the **turn feed**. The server subscribes the socket to every area whose
  `activeEntity` it controls, wherever the area is, now and whenever a later
  advance puts one of its entities up (an area made after the socket
  connected included). Each such area then behaves as if the socket had
  subscribed to it: `subscribed`, `checksum`, `turn`, and every later frame of
  the area. It is how a service that plays mobs in every level hears its
  turns (#170). Unsubscribing the feed leaves the areas it joined.
- `{"type":"ping"}`.

## Server → client
The socket carries changes, turns and control frames, never a resource's
state. A client loads the area, its entities and anything else by `GET`
(`resources.md`, "Generic resource API"), then keeps them current from the
deltas below (#186).

- `{"type":"ready","user":"<sub>","username":"…"}` after a valid `auth`. A
  service principal's token (`kind=service`, root `AGENTS.md` "Auth") has no
  username: `"username":null`, and `user` is the principal.
- `{"type":"subscribed","area":"…"}`, immediately followed by a `checksum`.
  The client then loads the area by `GET` if it doesn't hold it already.
  `{"type":"subscribed","turns":true}` answers the turn feed; the areas it
  joins follow, each with its own `subscribed`.
- `{"type":"checksum","area":"…","checksum":"<16 hex>","logSeq":<seq>}`: the
  checksum of the server's versions of the area and everything in it
  (`resources.md`, "Versions"). Sent on subscribe and about every 30 s. The
  client computes the same over what it holds and resyncs on a mismatch.
  `logSeq` is the highest the server knows for the area, so a client whose
  last `logSeq` is lower has also missed a batch and resyncs. It is left out
  until the server knows one (`log.md`, "Following the log live"). The server
  is the source of truth; the client never sends its versions.
- `{"type":"changes","area":"…","changes":[<update | created | deleted | stub>],"fromLogSeq":<seq>,"logSeq":<seq>,"part":<n>,"parts":<n>,"traceparent":"…"}`:
  one per advance (or per part, when a batch is split), the batch from
  `LISTEN/NOTIFY` as is. The deltas, parts and the logSeq chain are in
  `resources.md`, "Change notifications". A client whose last `logSeq` for
  the area isn't the frame's `fromLogSeq` has missed a batch: it applies what
  it can of this one by the version rule, then resyncs.
- `{"type":"reload","area":"…","logSeq":<seq>,"traceparent":"…"}`: the area
  was made in this advance (a first crossing into a new level), or its batch
  was too big to send. The client drops what it holds of the area and loads
  it again by `GET`: the area, then its children, paged.
- `{"type":"turn","area":"…","entity":"entity:…","version":<v>,"since":<time>}`:
  the entity is the area's `activeEntity` and this socket is its controller, so the game is
  waiting for it (`actions.md`, "Turns"). Only the active entity's controller
  gets it; a pending one gets nothing until it is active. The controller's
  sockets are: a user socket for `{"kind":"user","sub"}` matching its `sub`,
  a service socket for `{"kind":"service","principal"}` matching its `sub`. `version` is the entity's version in the server's map;
  send it as the intent's `ifVersion`. Sent after `subscribed` + `checksum`,
  after each `changes` or `reload` frame of the area, and again after every
  periodic `checksum` (which repairs a lost notification). Duplicates are
  expected: act on a turn once; a second intent on the same version gets a
  409. The entity's `actions` say what it can do.
- `{"type":"unsubscribed","area":"…"}` (or `"turns":true`), `{"type":"pong"}`,
  `{"type":"error","message":"…"}` (the socket stays open).
- Reserved, not built yet: `chat` (players talking) and `system` (server
  messages such as maintenance), both from #261. A client ignores them until
  their contract is written.

## Close codes
- `4401`: auth missing, invalid, late or expired. The server closes when the
  JWT's `exp` passes.
- `4400`: malformed frame before `auth`.
- `1003`: binary frame. `1009`: frame over 64KB.

## Edge cases (as implemented by `subscriptions`, 2026-11-11)
- An `auth` with a missing or empty token closes `4401`.
- After `auth`: a non-JSON frame gets `error "malformed frame"`; a missing or
  unknown `type`, or a second `auth`, gets `error "unknown type"`; a non-`area:`
  id gets `error "invalid area id"`.
- `unsubscribe` always answers `unsubscribed`. Subscribing twice resends
  `subscribed` + `checksum`.
- A `world` 404 gives `error "unknown area"`; a `world` 401 closes `4401`;
  other `world` failures give `error "could not load area: …"`.
- Any `Origin` is accepted (no cookies are involved).

## How the server knows the versions
On the first subscribe to an area the server reads, with the **subscribing
user's JWT**, the area's versions (`GET …/resources/{area}/versions`) and the
area itself (for its `activeEntity`). It keeps its version map, and the
checksum computed from it, current from the batches: an update sets the
resource's version, `created` adds it, `deleted` removes it, and a `reload`
reads the area again. It keeps the area's `activeEntity` from that read, then
from the area's updates (`set.activeEntity`, or `unset`), never from an older
area version. For the turn feed it first lists every area
(`GET …/resources?type=area`, all pages) with the subscriber's JWT, and joins
the socket to each area its entity is up in, then to the area of every batch
that puts one up. It never reads `world`'s tables.

## Routing
The HTTPRoute rule for `/api/subscriptions` needs `timeouts: {request: 0s}`;
Envoy's default 15 s request timeout kills WebSockets otherwise.
