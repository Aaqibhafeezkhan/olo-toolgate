# Monorepo Layout

```text
olo-toolgate/
├── apps/
│   ├── gateway/
│   ├── control-plane/
│   ├── tool-host/
│   ├── admin-ui/
│   ├── endpoint-client/
│   ├── marketplace-api/
│   ├── marketplace-worker/
│   └── marketplace-drupal/
├── crates/
├── backend-libs/
├── packages/
│   └── schemas/
├── templates/
├── deploy/
├── docs/
├── tests/
└── tools/
```

## Rule

Canonical cross-language schemas live under `packages/contracts/schemas/`.

Rust, Java and TypeScript types are generated or validated against those schemas.
