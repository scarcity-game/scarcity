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

1. Reads `modules.yaml` and runs every module's `test` command.
2. Builds and pushes an image for each `deploy: true` module, tagged
   `sha-<git sha>` and `latest`.
3. In this directory, runs `kustomize edit set image <name>-image=<image>:sha-<sha>`
   for each deployed module, then `kubectl kustomize | kubectl apply -f -`.
4. Waits on `kubectl rollout status` for each module's Deployment.

Every deployed module is rebuilt and redeployed on every push. That keeps image
tags consistent, and it is cheap while the repo is small. Revisit this
(path filters plus "keep current image") once builds get slow.

## Registering a module that needs a deployment

1. **Dockerfile.** Add one to the module. The container listens on **8080** and
   serves a health endpoint (`/actuator/health` for Spring, `/health` otherwise).
   Run as non-root.
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
