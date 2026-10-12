<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Deploying OLO Orgs

OLO Orgs is two images and three backing services:

| Part | Image or service | State |
|---|---|---|
| Drupal site, 2+ replicas | `olo-orgs-marketplace-drupal` | none: read-only root, `/tmp` only |
| Validation and signing helper, 2+ replicas | `olo-toolgate-marketplace-helper` | none; the signing key is a secret mount |
| Database | PostgreSQL 16 or 17 (MariaDB/MySQL also work) | users, publishers, packages, versions, sessions |
| Cache, locks, flood | Redis | disposable |
| Files | S3-compatible object storage (MinIO locally) | uploads, signed packages, avatars, CSS/JS aggregates |

Every Drupal replica serves every request: sessions live in the database, files in object storage and cache in Redis, so there are no sticky sessions. Install, updates, config import and the example seed run in a **deploy job**, once per release, never in a web replica. Cron is its own job.

## The signing key

The Ed25519 key that signs every release exists only in the helper's secret mount. It is never in Git, Drupal, the database or a backup of either, and nothing generates it automatically. Create it once per Marketplace, then back it up with your operator procedure:

```bash
scripts/init-signing-key.sh deploy/secrets/marketplace_signing_key   # refuses to replace an existing key
```

It prints the `keyId`, public key and fingerprint that each ToolGate's `TOOLGATE_CONTROL_MARKETPLACE_KEYS` needs. The public keys are also served at `/.well-known/toolgate-marketplace-keys.json`.

## Compose (one host)

```bash
cp deploy/.env.example deploy/.env                     # then edit: base URL, site address, passwords
mkdir -p deploy/secrets && chmod 700 deploy/secrets
openssl rand -hex 32 > deploy/secrets/helper_token
openssl rand -hex 32 > deploy/secrets/drupal_hash_salt
openssl rand -base64 18 > deploy/secrets/admin_password
scripts/compose.sh --profile init-key run --rm init-key   # the signing key, once (or scripts/init-signing-key.sh)
scripts/compose.sh up -d --build
```

`scripts/compose.sh` is the only way to run `deploy/compose.yaml`: it resolves `TOOLGATE_SRC`, builds the example packages with the seed stage into `deploy/.seed`, and passes every argument to `docker compose`. The stack is Caddy (TLS and the only published ports), two Drupal replicas, the deploy job, cron, two helper replicas, PostgreSQL (with `pg_trgm`), Redis and MinIO. Caddy finds Drupal replicas by DNS and health-checks `/health`, so `scripts/compose.sh up -d --scale drupal=4` needs no other change. It serves public files from `/<bucket>/s3fs-public/` straight from MinIO and never the private prefix.

Check the deploy job's result, including the seed:

```bash
scripts/compose.sh logs deploy
scripts/compose.sh run --rm --no-deps deploy drush toolgate-marketplace:seed-status
```

### Statelessness test

```bash
scripts/stateless-test.py --compose
```

With two replicas behind Caddy and no sticky sessions, it logs in through replica A, uploads through replica B, publishes through A, downloads the exact bytes through every replica, kills a replica mid-session and checks the session survives, then scales to 3 and back to 2. `--replica URL --replica URL` runs the first two steps against any two replicas of one site.

## Kubernetes (Helm)

`deploy/helm/olo-orgs-marketplace` runs the Drupal Deployment (HPA, minimum 2, PodDisruptionBudget, probes on `/health` with the public `Host`), the deploy Job, the cron CronJob and the helper Deployment (HPA, minimum 2, PDB, and a NetworkPolicy that admits only Drupal pods). PostgreSQL, Redis and object storage are external. Secrets come from Kubernetes Secrets, never from values:

```bash
kubectl create secret generic olo-orgs-drupal \
  --from-literal=hash_salt="$(openssl rand -hex 32)" --from-file=helper_token=helper_token \
  --from-literal=db_password=… --from-literal=s3_access_key=… --from-literal=s3_secret_key=… \
  --from-literal=admin_password="$(openssl rand -base64 18)"
kubectl create secret generic olo-orgs-helper \
  --from-file=helper_token=helper_token --from-literal=s3_access_key=… --from-literal=s3_secret_key=…
kubectl create secret generic olo-orgs-signing-key --from-file=marketplace_signing_key=marketplace_signing_key

helm install olo-orgs deploy/helm/olo-orgs-marketplace -f my-values.yaml
```

The deploy Job runs after the first install (when the helper exists to sign the seed) and before each upgrade. Don't pass `--wait` to the first `helm install`: Drupal's readiness probe only passes once the deploy Job has installed the site. Upgrades can use `--wait`.

Public files: by default the site's own origin must route `/<bucket>/s3fs-public/` to the bucket (Caddy does this in Compose). Set `marketplace.s3.publicDomain` to a host that serves `<publicDomain>/s3fs-public/` instead, such as a CDN in front of the bucket.

## Settings

All settings are environment variables, listed with their defaults in [deploy/.env.example](../deploy/.env.example). Any secret can come from a file instead: `<NAME>_FILE` (for example `DRUPAL_HASH_SALT_FILE`, `MARKETPLACE_HELPER_TOKEN_FILE`, `MARKETPLACE_DB_PASSWORD_FILE`, `MARKETPLACE_S3_SECRET_KEY_FILE`) wins over `<NAME>`. Configuration comes only from `drupal/config/sync` in the image; `config_readonly` blocks changes in the UI, and the deploy job imports it.

## Images

Both images are built and pushed by `ci/publish-images.sh`, the last step of the two release workflows, with the tags `dev-<sha12>`, `<VERSION>-<sha12>` and `dev`. The Drupal image is built only from a committed `drupal/composer.lock`; until that file exists the step publishes the helper alone and says so.
