# service-controller

The shared half of a service that plays entities, such as `orbital`: it gets
a token from auth with its API key, keeps a WebSocket open to subscriptions,
and when one of its entities is the active entity, reads it from world,
draws one of the service's choices by weight and posts it as an intent. The
service only says who it is (`controller.principal`) and what its choices
are (`Choice` beans).

It runs in one of two modes (`controller.mode`): `socket` (the default) as above, or
`endpoint`, for an intelligence service such as `microbe`, which has no socket and
no key: world POSTs it each turn (`POST /turn`, authenticated by world's token) and
it answers with the intent's action. AGENTS.md has "Adding an intelligence service".

See [AGENTS.md](AGENTS.md) for a turn, the socket, the configuration and the
tests.

```
mvn -B -f ../../pom.xml -pl libs/service-controller -am verify
```
