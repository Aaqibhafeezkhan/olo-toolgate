<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# OLO Orgs on a laptop: two containers

`drupal` is the whole site in one container: Drupal with SQLite, the uploaded files and Drupal's own generated secrets, all on one volume. `helper` is the validation and signing helper; it alone holds the signing key, in a volume of its own. Open http://localhost:8080. This setup is for trying the site; for production use `deploy/` (docs/deployment.md).

## On Windows: one script

`marketplace\debug\start.bat` brings up everything for local testing: the ToolGate debug stack (`debug\start.bat`), one Linux debug device (`create-devices.bat -Count 1`) and these two containers, then opens http://localhost:8080. It builds the Marketplace images the first time (in a Linux builder container, so no bash or PHP is needed on Windows), creates the signing key once and waits until Drupal and the helper are healthy.

```bat
marketplace\debug\start.bat                    :: all four containers
marketplace\debug\start.bat -FocusedCheck      :: the same with debug\start.bat's focused checks
marketplace\debug\start.bat -MarketplaceOnly   :: only Drupal and the helper
marketplace\debug\start.bat -Build             :: rebuild the Marketplace images first
marketplace\debug\restart.bat                  :: rebuild and recreate
marketplace\debug\stop.bat                     :: stop all four, keeping their data (-MarketplaceOnly: just these two)
```

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
