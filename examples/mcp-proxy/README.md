# Govern an existing MCP server

This example puts a scriptable MCP server behind ToolGate: register it, scan it from a Tool Host, approve two of its
tools, and call them through the Gateway. The server is the fixture in
[tests/fixtures/mcp-server](../../tests/fixtures/mcp-server/server.py), which needs only Python 3. Never use real
credentials in examples.

How the pieces fit is in [Governing existing MCP servers](../../docs/gateway/mcp-proxy.md).

## 1. Run the upstream server

The upstream must speak HTTPS, and ToolGate trusts it the way the Tool Host's system does. For a local try, create a
certificate for a host name your Tool Host trusts, and run:

```bash
python tests/fixtures/mcp-server/server.py --port 8443 --cert server.pem --key server-key.pem --script examples/mcp-proxy/orders.json
```

[orders.json](orders.json) scripts three tools, `get_order`, `list_orders` and `cancel_order`, and requires the
header `Authorization: Bearer example-token`.

## 2. Prepare the tenant

In the console, as an administrator:

1. Create or pick an **execution binding** whose Device Group contains the Tool Host that should reach the server.
   That Device Group is the server's pool.
2. Store the credential in the **vault** as `orders/mcp-token` with the value `example-token`, in exactly the
   binding's Tool Group and Device Group.

## 3. Register, scan and approve

Open **Administration → MCP servers**:

1. Register `orders` with the server's URL, the binding, the secret `orders/mcp-token` and **Bearer token**.
2. Choose **Review orders**, then **Scan orders**. The Tool Host connects, lists the tools and reports them; reload
   the review to see the snapshot.
3. Select `get_order` and `list_orders`, keep the suggested local names, and **Approve 2 tools**. ToolGate deploys
   `mcp.orders` version `1.0.1` to the pool with both tools disabled.

## 4. Enable, grant and call

Enable `mcp.orders/get-order` and `mcp.orders/list-orders` with a reviewed change, add them to the binding's Tool
Group, and grant them to an agent. The agent then sees `mcp.orders.get-order` in its catalog and calls it through
the Gateway. The result's `_meta` carries `io.toolgate/untrusted-upstream: true`.

## 5. See a change quarantined

Edit `orders.json` (for example, change the description of `get_order`) and restart the server. Within a minute
the refresh notices the new definition: `mcp.orders/get-order` is disabled and the server shows
**Quarantined: DEFINITION_CHANGED** with the evidence. Scan again, review the diff and approve again to recover.
