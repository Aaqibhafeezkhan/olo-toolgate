# Python API (3.10+)

## Purpose

Exact Python surface of the [authoring contract](README.md). Distribution `toolgate-sdk`, import package `toolgate`.

## Declarations

```python
from toolgate import (tool_package, server_tool, client_tool, Param, tool_variables, Variable, Global, Secret, JwtClaim,
                      destination, http_auth, http_route, tcp_destination, idempotency, reconcile, example,
                      ErrorDecl, EffectSafety, KeyNamespace, RuntimeTier, ValueType, ToolContext)

tool_package(id="acme.orders", name="Acme Orders", publisher="acme")          # once, in the package's __init__ or toolgate_package.py

@destination(id="acme-api", host_setting="AcmeApiHost", default_host="api.acme.com", port=443)
@http_auth(id="acme-key", type="API_KEY", header="X-Api-Key", secret="ApiKey")
@http_route(id="orders.get", destination="acme-api", method="GET", path="/orders/{orderId:[0-9]{6,12}}",
            query=["since"], auth="acme-key", timeout_ms=10_000)
class OrderTools: ...                                                       # class-level scope; module-level decorators also allowed

@server_tool(name="orders.lookup", title="Look up an order", description="…",
             effect=EffectSafety.READ_ONLY, routes=["orders.get"], errors=[ErrorDecl("ORDER_NOT_FOUND", "No such order")])
@Global(name="TimeoutSeconds", type=ValueType.INT, default_value="10")
@JwtClaim(name="UserName", claim="preferred_username")
def lookup(order_id: Annotated[str, Param(name="orderId", description="Order number", pattern=r"^[0-9]{6,12}$")],
           since: Annotated[int | None, Param(description="Only events after this epoch second")] = None,
           *, context: ToolContext) -> OrderResult: ...
```

`reconciliation=Reconciliation.MANUAL` (or `DOWNSTREAM_IDEMPOTENCY`, `STATUS_LOOKUP`) on `server_tool` / `client_tool` follows the
same rules as Java.

`@authorization(action="memory.read", resources="olo://{tenant}/conversation/{conversationId}")` below `@server_tool` or
`@client_tool` is the Python form of Java `@Authorization`, with the same rules.

Attribute names are the snake_case forms of the neutral names (`use_when`, `max_duration_ms`, `business_key_namespace`, …). `Param.name` defaults to the Python parameter name; give `name=` to keep a camelCase input name. Return types: dataclass, `TypedDict`, Pydantic model, `dict`, or `ToolResult`. `async def` handlers are supported.

## ToolContext

```python
class ToolContext(Protocol):
    def get_str(self, name: str) -> str: ...      # get_int, get_long, get_number, get_bool, get_date, get_datetime,
                                                  # get_duration, get_object(name, type), get_list(name, type), get_bytes
    globals: Values; secrets: Values; jwt: Jwt     # Values has the same get_* family; Jwt adds user_name, subject, issuer
    http: HttpClient; tcp: TcpConnector; invocation: Invocation
    def progress(self, fraction: float, message: str | None = None) -> None: ...
    def is_cancelled(self) -> bool: ...
    cancellation: CancellationToken
    def request_input(self, request: InputRequest[T]) -> T: ...
    log: logging.Logger                            # redacting adapter

class HttpClient(Protocol):
    def route(self, route_id: str) -> RouteCall: ...
    def get(self, destination_id: str, path_template: str) -> RouteCall: ...   # post, put, patch, delete, head
class RouteCall(Protocol):
    def path_var(self, name: str, value: object) -> "RouteCall": ...
    def query(self, name: str, value: object) -> "RouteCall": ...
    def header(self, name: str, value: str) -> "RouteCall": ...
    def body(self, value: object) -> "RouteCall": ...
    def timeout(self, seconds: float) -> "RouteCall": ...
    def send(self, response_type: type[T]) -> T: ...
    def send_raw(self) -> RawResponse: ...
```

`context` is a keyword-only parameter so it is never mistaken for an input. Async handlers use `await context.http.route(...).send_async(T)`.

## Errors

`toolgate.errors`: `ToolError(Exception)` and subclasses as in [errors.md](errors.md).

## Build

`toolgate` CLI (`toolgate dev`, `toolgate pack`, `toolgate check`) reading `[tool.toolgate]` in `pyproject.toml`, where `descriptor_generation` is required.
