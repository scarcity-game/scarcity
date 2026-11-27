# WebSocket protocol between `subscriptions` and clients

Owned by the root agent; decided 2026-11-11. Read this if your module serves or
consumes the subscription socket (`services/subscriptions`, the clients). The
version rules the client applies to what it receives are in
[`resources.md`](resources.md).

Endpoint: `wss://scarcity.pessimistic-it.com/api/subscriptions/ws?area=<area id>`.
JSON text frames, one object per frame, each with a `type`. Unknown fields are
ignored on both sides. Browsers can't set headers on a WebSocket, so the JWT
travels in the first frame.

**One area per socket** (#379). The `area` in the connect URL is the socket's
area and the key the router hashes on, so every socket of an area lands on the
same `subscriptions` pod (see "Routing"). Send the id unencoded (`?area=area:7f3c…`):
an id is query-safe, and the router hashes the value as sent, so two clients
that encoded it differently would land on different pods. Such a socket may
subscribe only to its own area. Crossing stairs is a new socket for the new
area (and closing the old one); viewing several areas (#173) is one socket each.
A socket with no `area` is a **turn-feed socket**: it serves the turn feed,
which spans areas, so it has no single owner and lands on any pod. A socket with
no `area` may still subscribe to areas (served correctly, just not routed); no
client should rely on it.

## Client → server
- `{"type":"auth","token":"<jwt>"}`: must be the first frame, within 5 s.
- `{"type":"subscribe","area":"area:…"}` / `{"type":"unsubscribe","area":"…"}`.
  On a socket for an area, only that area.
- `{"type":"subscribe","turns":true}` / `{"type":"unsubscribe","turns":true}`:
  the **turn feed**. The server subscribes the socket to every area whose
  `activeEntity` it controls, wherever the area is, now and whenever a later
  advance puts one of its entities up (an area made after the socket
  connected included). Each such area then behaves as if the socket had
  subscribed to it: `subscribed`, `checksum`, `turn`, and every later frame of
  the area. It is how an npc service (`diver`) hears its turns in every
  level (#170). An intelligence service (`microbe`) holds no socket: world
  POSTs it its turns (`actions.md`, "Intelligence services"), so no `turn`
  frame is ever sent for its entities. Unsubscribing the feed leaves the
  areas it joined.
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
- `{"type":"checksum","area":"…","checksum":"<8 hex>","logSeq":<seq>}`: the
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
- `{"type":"reload","area":"…","logSeq":<seq>,"traceparent":"…"}`: an entity
  arrived in the area by stairs (on a first crossing, the area was just
  made), or its batch was too big to send. The client drops what it holds of the area and loads
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
- `1012` (service restart): the pod is draining (see "Drain"). Reconnect.

## Edge cases (as implemented by `subscriptions`, 2026-11-11)
- An `auth` with a missing or empty token closes `4401`.
- On a socket for an area: a `subscribe` to another area gets
  `error "not this socket's area"`, and `subscribe{turns}` gets
  `error "the turn feed needs a socket with no area"`. Nothing is subscribed.
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

## Reconnecting and resume
A socket can close at any time: a drain (`1012`), a restart of the router
(Envoy is one replica, so every socket drops at once), a lost network. Except
after `4401`, a client reconnects with a back-off that doubles from about 1 s to
30 s, **each wait jittered** to a random point in its upper half, and reset once
it is subscribed again. Without the jitter, every client cut off together comes
back together.

Resuming needs no extra frame. A reconnecting client **keeps what it holds**,
authenticates and subscribes again, and compares the `checksum` (and `logSeq`)
that follows `subscribed` with its own, as it does every 30 s. If they match it
missed nothing. If they don't, it resyncs as on any mismatch (`resources.md`,
"Versions"). A fresh pod builds the area's map
from world once and shares it among the area's sockets, so a herd of
reconnects costs one world read per area, plus the resyncs of clients that
did miss something. `NOTIFY` isn't durable and there is no replay, so the
checksum is the only resume check.

Clients ping (`{"type":"ping"}`) about every 25 s and the server sends
WebSocket pings every 30 s. Nothing on the route cuts idle sockets, but the
pings keep NAT entries on the way alive.

## Drain
On shutdown (`SIGTERM`), before the web server stops, a `subscriptions` pod:
1. fails readiness, so Envoy drops it from the hash ring (about 1 s);
2. waits `drain-delay` (5 s);
3. closes its open sockets with `1012` in random order, spread over
   `drain-window` (15 s), so the clients' reconnects (each jittered too) spread
   over the surviving pods' ring.

Envoy never moves or closes an open WebSocket itself, so without step 3 a
removed pod's sockets would stay until the pod died. The same holds after a
scale-up: old sockets stay on their pod until they reconnect, so two pods
serving one area for a while is normal and correct (each keeps its own map,
#378). The Deployment's `terminationGracePeriodSeconds` must exceed the
delay plus the window (`k8s-resources/subscriptions/deployment.yaml`).

## Routing
`/api/subscriptions` has its own HTTPRoute (`scarcity-subscriptions`) with
`timeouts: {request: 0s}`, which overrides Envoy's 15 s request timeout and its
5 min stream idle timeout. Without it, WebSockets are cut. A
`BackendTrafficPolicy` on that route hashes on the `area` query parameter
(`ConsistentHash`, `QueryParams`, Maglev). Adding or removing a pod moves about
1/N of the areas. A socket without `area` (the turn feed) produces no hash and
goes to a random pod (#380). The hash is applied on the HTTP/1.1 Upgrade, so it
needs the area in the URL; a frame can't route.
