# .NET API (.NET 8+)

Status: proposed .NET SDK authoring contract; no implemented SDK or conformance is claimed.
Finish implementation and the shared SDK conformance suite before publishing this API.

## Purpose

Exact .NET surface of the [authoring contract](../../tool-sdk/spec/authoring/README.md). Package `OloLabs.ToolGate.Sdk` (namespace `OloLabs.ToolGate.Sdk`), with the source generator and Roslyn analyzers in `OloLabs.ToolGate.Sdk.Analyzers` (referenced automatically), testing in `OloLabs.ToolGate.Sdk.Testing`, and the CLI as the .NET tool `OloLabs.ToolGate.Cli`.

## Attributes

```csharp
[assembly: ToolPackage("acme.orders", Name = "Acme Orders", Publisher = "acme")]
[assembly: Destination("acme-api", HostSetting = "AcmeApiHost", DefaultHost = "api.acme.com", Port = 443)]
[assembly: HttpAuth("acme-key", AuthType.ApiKey, Header = "X-Api-Key", Secret = "ApiKey")]
[assembly: HttpRoute("orders.get", Destination = "acme-api", Method = HttpMethod.Get,
    Path = "/orders/{orderId:[0-9]{6,12}}", Query = new[] { "since" }, Auth = "acme-key", TimeoutMs = 10_000)]

[Secret("ApiKey")]
public sealed class OrderTools
{
    [ServerTool("orders.lookup", Title = "Look up an order", Description = "Returns status and items for one order.",
        Effect = EffectSafety.ReadOnly, Routes = new[] { "orders.get" })]
    [Global("TimeoutSeconds", ValueType.Int, DefaultValue = "10")]
    [JwtClaim("UserName", Claim = "preferred_username")]
    [ErrorDecl("ORDER_NOT_FOUND", "No such order")]
    public Task<OrderResult> Lookup(
        [Param(Description = "Order number", Pattern = "^[0-9]{6,12}$")] string orderId,
        [Param(Description = "Only events after this epoch second")] int? since,
        ToolContext context) =>
        context.Http.Route("orders.get").PathVar("orderId", orderId).Query("since", since).SendAsync<OrderResult>();
}
```

- Attribute properties are the PascalCase neutral names (`UseWhen`, `MaxDurationMs`, `BusinessKeyNamespace`, …); enum members are PascalCase (`EffectSafety.NonIdempotent`, `KeyNamespace.DelegatedUser`, `RuntimeTier.OciSandbox`). The descriptor always uses the neutral upper-snake spelling.
- Package-scope declarations (destinations, routes, auth) are assembly attributes; globals, secrets and claims may be on the class or method.
- The grouped form is `[ToolVariables]` with nested `[Variable]` attributes on a method that takes only `ToolContext`, as in Java.
- Handlers return `T`, `Task<T>`, `ValueTask<T>` or `ToolResult`. A `CancellationToken` parameter is allowed and is bound to the invocation's cancellation token; it is not an input.
- `ClientToolAttribute` adds `Tiers`; `Routes`, `Tcp`, secrets and `HttpAuth` on a client tool are analyzer errors.

## ToolContext

```csharp
public interface ToolContext
{
    string GetStringValue(string name); int GetIntValue(string name); long GetLongValue(string name);
    double GetNumberValue(string name); bool GetBooleanValue(string name); DateOnly GetDateValue(string name);
    DateTimeOffset GetDateTimeValue(string name); TimeSpan GetDurationValue(string name);
    T GetObjectValue<T>(string name); IReadOnlyList<T> GetListValue<T>(string name); byte[] GetBinaryValue(string name);
    IValues Global { get; } IValues Secret { get; } IJwt JWT { get; }
    IHttpClient Http { get; } ITcpConnector Tcp { get; } Invocation Invocation { get; }
    void Progress(double fraction, string? message = null);
    bool IsCancelled { get; } CancellationToken CancellationToken { get; }
    Task<T> RequestInputAsync<T>(InputRequest<T> request);
    ILogger Log { get; }                                               // Microsoft.Extensions.Logging, redacting
}
public interface IJwt : IValues { string GetUserName(); string GetSubject(); string GetIssuer(); }
public interface IHttpClient { IRouteCall Route(string routeId); IRouteCall Get(string destinationId, string pathTemplate); /* Post, Put, Patch, Delete, Head */ }
public interface IRouteCall
{
    IRouteCall PathVar(string name, object? value); IRouteCall Query(string name, object? value);
    IRouteCall Header(string name, string value); IRouteCall Body(object? value); IRouteCall Timeout(TimeSpan timeout);
    Task<T> SendAsync<T>(); Task<RawResponse> SendRawAsync();
}
public interface ITcpConnector { Task<System.IO.Stream> ConnectAsync(string destinationId); }
```

The source generator emits typed accessors per tool (`OrderToolsVars.Global(context).TimeoutSeconds`) and the descriptor; analyzers TG0001 to TG0099 report undeclared names, wrong types, unproven convenience calls ([http.md](../../tool-sdk/spec/authoring/http.md) §3) and forbidden APIs as errors.

## Errors

`OloLabs.ToolGate.Sdk.Errors`: `ToolError : Exception` and subclasses as in [errors.md](../../tool-sdk/spec/authoring/errors.md).

## Build

MSBuild properties in the `.csproj`: `<ToolGateDescriptorGeneration>` (required), `<ToolGatePackage>true</ToolGatePackage>`. `dotnet toolgate dev | pack | check`.
