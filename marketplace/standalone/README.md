<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO Orgs on a laptop: two containers

`drupal` is the whole site in one container: Drupal with SQLite, the uploaded files and Drupal's own generated secrets, all on one volume. `helper` is the validation and signing helper; it alone holds the signing key, in a volume of its own. Open http://localhost:8080. This setup is for trying the site; for production use `deploy/` (docs/deployment.md).

## From images you were given

```bash
docker load -i olo-orgs-images.tar.gz        # olo-orgs-standalone:local and olo-toolgate-marketplace-helper:local
docker compose --profile init-key run --rm init-key   # once: creates the signing key, prints the public key
docker compose up -d
docker compose logs -f drupal                 # first start installs the site and publishes the 18 examples
docker compose exec drupal cat /var/lib/olo-orgs/secrets/admin_password   # sign in as admin
```

`OLO_ORGS_PORT=9090 docker compose up -d` serves on another port.

## Building the images yourself

```bash
scripts/build-images.sh --core-only --standalone   # from marketplace/; --core-only until contrib modules are locked
cd standalone && docker compose --profile init-key run --rm init-key && docker compose up -d
```

`docker compose down` keeps the data; `docker compose down -v` deletes the site and the signing key.
