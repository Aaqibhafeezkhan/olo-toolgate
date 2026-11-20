# TypeScript API (Node 20+, TypeScript 5+)

Status: proposed TypeScript SDK authoring contract; no implemented SDK or conformance is claimed.
Finish implementation and the shared SDK conformance suite before publishing this API.

## Purpose

Exact TypeScript surface of the [authoring contract](../../tool-sdk/spec/authoring/README.md). Package `@olo-labs/toolgate-sdk` (ESM with CommonJS export map); testing helpers in `@olo-labs/toolgate-sdk-testing`; CLI in `@olo-labs/toolgate-cli`.

## Declarations

TypeScript has no runtime annotations, so declarations are plain function calls whose arguments **must** be object literals the packager can read statically.

```typescript
import { toolPackage, serverTool, clientTool, destination, httpAuth, httpRoute, tcpDestination,
         global, secret, jwtClaim, errorDecl, example, z, type ToolContext } from "@olo-labs/toolgate-sdk";

export default toolPackage({ id: "acme.orders", name: "Acme Orders", publisher: "acme" });   // toolgate.package.ts

// toolgate.routes.ts
export const acmeApi = destination({ id: "acme-api", hostSetting: "AcmeApiHost", defaultHost: "api.acme.com", port: 443 });
export const acmeKey = httpAuth({ id: "acme-key", type: "API_KEY", header: "X-Api-Key", secret: "ApiKey" });
export const ordersGet = httpRoute({ id: "orders.get", destination: "acme-api", method: "GET",
  path: "/orders/{orderId:[0-9]{6,12}}", query: ["since"], auth: "acme-key", timeoutMs: 10_000 });

export const lookup = serverTool({
  name: "orders.lookup", title: "Look up an order", description: "Returns status and items for one order.",
  effect: "READ_ONLY", routes: ["orders.get"],
  globals: [global({ name: "TimeoutSeconds", type: "INT", defaultValue: "10" })],
  secrets: [secret({ name: "ApiKey" })],
  jwt: [jwtClaim({ name: "UserName", claim: "preferred_username" })],
  errors: [errorDecl({ code: "ORDER_NOT_FOUND", description: "No such order" })],
  input: z.object({
    orderId: z.string().regex(/^[0-9]{6,12}$/).describe("Order number"),
    since: z.number().int().optional().describe("Only events after this epoch second"),
  }),
  output: OrderResultSchema,                                       // optional Zod schema; inferred return type otherwise
  run: async (args, ctx) => ctx.http.route("orders.get").pathVar("orderId", args.orderId)
                                  .query("since", args.since).send<OrderResult>(),
});
```

- Attribute names are exactly the neutral names (`useWhen`, `maxDurationMs`, `businessKeyNamespace`, …). Enums are string literal unions (`"READ_ONLY" | "IDEMPOTENT" | "NON_IDEMPOTENT" | "UNKNOWN"`).
- `input` is a Zod object schema re-exported as `z` from the SDK (pinned Zod 3.x major, so descriptor output is stable). Each property **must** call `.describe()`. The packager converts it with the SDK's own converter (never a third-party converter whose output can change); only the Zod constructs listed in the [descriptor spec](../../tool-sdk/spec/descriptor/README.md) §5 are allowed, others fail packaging.
- The grouped form is `variables: { inputs: [variable({...})], globals, jwt, secrets }` with `run: (ctx) => ...`. It **must** produce the same descriptor as the equivalent `input` schema.
- `clientTool({...})` accepts the same object plus `tiers`, and rejects `routes`, `tcp`, `secrets` and HTTP auth at type level and at packaging.
- Declarations **must** be exported module-level constants. A declaration built in a loop, conditional or from computed values fails packaging.

## ToolContext

```typescript
export interface ToolContext {
  getString(name: string): string; getInt(name: string): number; getLong(name: string): number;
  getNumber(name: string): number; getBoolean(name: string): boolean; getDate(name: string): string;
  getDateTime(name: string): string; getDuration(name: string): string;
  getObject<T>(name: string): T; getList<T>(name: string): T[]; getBinary(name: string): Uint8Array;
  readonly global: Values; readonly secret: Values; readonly jwt: Jwt;
  readonly http: HttpClient; readonly tcp: TcpConnector; readonly invocation: Invocation;
  progress(fraction: number, message?: string): void;
  isCancelled(): boolean; readonly signal: AbortSignal;              // the language cancellation token
  requestInput<T>(request: InputRequest<T>): Promise<T>;
  readonly log: ToolLogger;
}
export interface Values { getString(n: string): string; /* same typed getter family */ }
export interface Jwt extends Values { readonly userName: string; readonly subject: string; readonly issuer: string; }
export interface HttpClient { route(routeId: string): RouteCall; get(destinationId: string, pathTemplate: string): RouteCall; /* post, put, patch, delete, head */ }
export interface RouteCall {
  pathVar(n: string, v: unknown): RouteCall; query(n: string, v: unknown): RouteCall; header(n: string, v: string): RouteCall;
  body(v: unknown): RouteCall; timeout(ms: number): RouteCall;
  send<T>(): Promise<T>; sendRaw(): Promise<RawResponse>;
}
export interface TcpConnector { connect(destinationId: string): Promise<import("node:net").Socket>; }
```

When the declarations are typed constants, the SDK's generic types narrow `ctx.global.getInt`, `ctx.secret` and `ctx.jwt` to declared names, so an undeclared name or wrong type is a compile error. The packager's AST analyser repeats the check for JavaScript sources.

## Errors

Exported classes `ToolError` and subclasses as in [errors.md](../../tool-sdk/spec/authoring/errors.md); `instanceof` works across the CJS and ESM builds because both resolve to one module instance.

## Build

`toolgate dev | pack | check` reads the `"toolgate"` key in `package.json`, where `descriptorGeneration` is required. The packager bundles the tool with the SDK runtime (esbuild, fixed options) into one JS artifact.
