# Authorize and run your first tool

Start from a running [Quickstart](one-minute-quickstart.md). Choose a low-risk tool
from the installed catalog, or build the [Java starter](../../TOOL-SDK-GUIDE.md).
Fresh installations do not grant access automatically.

1. Register the intended executor and review its fingerprint/owner. Installed
   clients need both enablement and current connection approval.
2. Enable the Tool and put it in its intended Tool Group. Bind that Tool Group to
   the executor's Device Group.
3. Give the requesting User's Team a complete grant for that Tool Group, Device
   Group, action and resource. For an Agent, also configure Agent Group capability
   and explicit SERVICE or delegated authority.
4. Apply configuration through the applicable review workflow. Management roles
   and device approval do not replace these runtime grants.
5. Discover and invoke the tool using the configured client/Agent identity. Inspect
   the correlated decision and result. If policy requires ASK, review the exact
   pending operation before retrying it unchanged.
6. Disable a required grant or target and confirm a subsequent call is denied.

For local files, use the enrolled client's configured HotFolder and exact allowed
paths. Do not grant arbitrary filesystem or shell access as a shortcut.

Use [device controls](../control-plane/device-registry.md),
[HotFolder instructions](../client/hotfolder.md),
[ASK approvals](../control-plane/approvals.md), or the runnable
[LangChain/device example](../../examples/langchain/README.md) for the relevant path.
