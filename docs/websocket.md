# WebSocket protocol between `subscriptions` and clients

Owned by the repo agent; decided 2026-11-11. Read this if your module serves or
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
- `{"type":"ping"}`.

## Server → client
- `{"type":"ready","user":"<sub>","username":"…"}` after a valid `auth`.
- `{"type":"subscribed","area":"…"}`, immediately followed by a `digest`.
- `{"type":"digest","area":"…","versions":{"<id>":<version>,…},"logSeq":<seq>}`: the server's
  current versions of the area and everything in it. Sent on subscribe and
  about every 30 s. The client applies the version rule to each entry (higher
  than cached → `GET`). The server is the source of truth; the client never
  sends its own version map.
  `logSeq` is the highest one the server has seen in a batch for the area; it
  is left out until it has seen one (`log.md`, "Following the log live").
- `{"type":"changes","area":"…","changes":[{"id","type","version","patch","children","deleted"}],"logSeq":<seq>,"traceparent":"…"}`:
  one per tick, the batch from `LISTEN/NOTIFY` as is. Resources can be created
  and destroyed (`children`, `deleted`); the rules are in `resources.md`,
  "Change notifications". The server's own version map follows them: it adds a
  resource on its first change and removes it on `deleted`, so a digest lists
  exactly what exists.
- `{"type":"unsubscribed","area":"…"}`, `{"type":"pong"}`,
  `{"type":"error","message":"…"}` (the socket stays open).

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
  `subscribed` + `digest`.
- A `world` 404 gives `error "unknown area"`; a `world` 401 closes `4401`;
  other `world` failures give `error "could not load area: …"`.
- Any `Origin` is accepted (no cookies are involved).

## How the server knows the versions
On the first subscribe to an area the server calls `world`'s resource API with
the **subscribing user's JWT** (the area, then all pages of its children), then
keeps the map current from the notifications. It never reads `world`'s tables.

## Routing
The HTTPRoute rule for `/api/subscriptions` needs `timeouts: {request: 0s}`;
Envoy's default 15 s request timeout kills WebSockets otherwise.
