# k8s-resources

Every Kubernetes object for Scarcity. It all goes into namespace **`scarcity`**
on the glowbox cluster and is served at **https://scarcity.pessimistic-it.com**.

```
kustomization.yaml     root: lists platform files + one directory per deployed module
platform/
  httproute.yaml       public routing (Gateway glowbox / listener https-scarcity)
  postgres.yaml        shared Postgres StatefulSet + postgres-service
  postgres-backup.yaml nightly pg_dump CronJob (06:00 America/Chicago) → db-dumps/scarcity → S3
<module>/              one per module with deploy: true in modules.yaml
  kustomization.yaml
  deployment.yaml
  service.yaml
```

## How CI deploys

On every push to `main`, `.github/workflows/ci.yaml`:

1. Works out which modules changed since the last successful deploy (see
   `path`/`paths` in `modules.yaml`). A change to `modules.yaml` or the
   workflow selects every module.
2. Runs the `test` command of each changed module.
3. Builds and pushes an image for each changed `deploy: true` module, tagged
   `sha-<git sha>` and `latest`.
4. In this directory, pins `<name>-image` for every deployed module:
   - a changed module gets `<image>:sha-<this sha>`;
   - an unchanged module keeps the image the cluster is running now (read from
     the live Deployment).
5. Runs `kubectl kustomize | kubectl apply -f -`. This always runs, so edits
   under `k8s-resources/` apply even when no module changed. Unchanged objects
   apply as no-ops and nothing rolls.
6. Waits on `kubectl rollout status` for each deployed module.

## Registering a module that needs a deployment

1. **Dockerfile.** Add one to the module. The container listens on **8080**
   and runs as non-root. Spring services serve actuator (health and
   `/actuator/prometheus`) on management port **8081**; others serve `/health`
   on 8080.
   - Probes point at the container port named `metrics` (8081) for Spring.
   - **Metrics:** name the port `metrics` and set the pod-template annotation
     `prometheus.io/path: /actuator/prometheus`. Prometheus job `app-pods`
     scrapes it. Keep 8081 out of the Service.
   - **Tracing:** set `MANAGEMENT_OTLP_TRACING_ENDPOINT` to
     `http://alloy.observability.svc:4318/v1/traces`, and
     `OTEL_RESOURCE_ATTRIBUTES=k8s.namespace.name=$(POD_NAMESPACE),…` from the
     downward API. Copy `auth/deployment.yaml`. Logs go to stdout as JSON with
     a `traceId` field; Alloy ships them to Loki.
2. **modules.yaml.** Add an entry with `deploy: true`, `dockerfile`, `context`
   and `image: ghcr.io/scarcity-game/scarcity-<name>`.
3. **`k8s-resources/<name>/`.** Add `kustomization.yaml`, `deployment.yaml` and
   `service.yaml`. Copy `auth/` as a template. Rules:
   - The Deployment and Service are both named `<name>`, labelled `app: <name>`.
   - The container image is the literal placeholder `<name>-image`. CI replaces it.
   - Set `resources.requests` **and** `resources.limits` on every container.
   - Set `enableServiceLinks: false`.
   - Set `imagePullSecrets: [{name: ghcr-example-image-pull-secret}]`. ghcr
     packages are private. This is the shared glowbox read:packages credential;
     the name is historical. The k8s agent owns and renews it, so don't copy,
     recreate or manage it from this repo. Put it on each Deployment, not on the
     default ServiceAccount.
   - Leave `metadata.namespace` out; the root kustomization sets it.
4. **Root `kustomization.yaml`.** Add `<name>` under `resources:` and an `images:`
   entry for `<name>-image`.
5. **Public route (optional).** If the module serves a public API, add a
   `PathPrefix: /api/<name>` rule to `platform/httproute.yaml`. WebSocket rules
   need `timeouts: {request: 0s}`.
6. **Secrets (optional).** Ask the repo agent, which asks the k8s agent to create
   them by hand. Reference them with `secretKeyRef` or a secret volume. Never
   commit secret values; the repo is public.
7. **Database (optional).** Use `postgres-service:5432`, database `scarcity`,
   credentials from `postgres-secrets`, and a schema named after your module,
   managed with Flyway.

### Hard constraints
- The deploy service account (`scarcity:github-actions`) is namespace-scoped.
  Kustomize output must not include a Namespace, ClusterRole, ClusterRoleBinding,
  CRD or StorageClass, or the apply fails.
- Don't use, overwrite or prune the pre-existing `roll` Deployment/Service.
- Reference `ghcr-example-image-pull-secret` but never define, overwrite or prune
  it. The k8s agent owns it.
- The deploy kubeconfig is the `KUBE_CONFIG` secret on the GitHub **environment**
  `production`, not a repo secret. Only `main` may deploy to that environment.
  It can read every secret in the namespace, so only the `deploy` job (push to
  `main`) uses it. Never use `pull_request_target`, and never check out PR code
  in a job that has the secret.

## Checking a change locally

```bash
cd k8s-resources
kubectl kustomize .
kubectl --kubeconfig ../.secrets/scarcity.kubeconfig apply --dry-run=server -k .
```
