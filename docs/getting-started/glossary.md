# Beginner glossary

ToolGate decides whether a person or an AI agent may run a tool on a device.
This page explains the words you will see in the console and the docs. The
[full glossary](../GLOSSARY.md) covers packages, permits and other advanced terms.

## How the pieces fit

Users, Agents, Tools and Devices are **entities**. Every entity belongs to at least
one **group**: Users join Teams, and Agents, Tools and Devices join Agent Groups,
Tool Groups and Device Groups. Access is never set on an individual entity.
Instead, groups hold the access mappings: grants, Tool Group to Device Group
bindings and ALLOW, ASK or BLOCK policies. A request succeeds only when the
mappings for every group involved permit the same operation.

Being in a group is not the same as having access. Each kind of group has a
protected default group, such as `default-tools`, so a new entity is never left
without a group. Default groups grant no runtime access. Approving a device lets
it connect, but device approval alone never authorizes a tool to run on it.

## Actors and resources

| Term | Meaning |
|---|---|
| User | A person who signs in to ToolGate. A User receives access only through the Teams they belong to; enabling or signing in does not grant access by itself. |
| Team | A group of Users. Teams represent people: roles and access grants are assigned to a Team and apply to its enabled members. |
| Agent | An AI program or automation that calls tools with its own identity. It has a human owner, but it receives access only through its Agent Groups. |
| Agent Group | A group of Agents. It describes which Tools and Device Groups its Agents may use and in which modes. |
| Tool | A capability an AI can call, such as reading a file or querying a service. Installing a Tool does not permit anyone to run it. |
| Tool Group | A group of Tools. Each Tool belongs to exactly one Tool Group, and the group is bound to the Device Group where its Tools may run. |
| Device | A machine that runs Tools, for example a laptop with the ToolGate client or a server executor. It has a registered identity and an approved key. |
| Device Group | A group of Devices. It sets the boundary of machines a Tool Group's Tools may run on. A Device can belong to several Device Groups. |

## Platform components

| Term | Meaning |
|---|---|
| Control | The Control Plane, which runs the console and stores users, groups, grants and policies. It makes every runtime authorization decision and fails closed when it cannot decide. |
| Gateway | The entry point that AI clients connect to. It authenticates each call, asks Control for a decision and routes allowed calls to the right executor; it never authorizes calls on its own. |

## Policy decisions

| Term | Meaning |
|---|---|
| ALLOW | The operation may run, provided a complete grant already covers it. ALLOW never creates a grant. |
| ASK | A human reviewer must approve this exact operation before it runs. Approval cannot fix a missing grant. |
| BLOCK | The operation is denied. When several policies apply, BLOCK wins. |

## Next steps

- [Run Quickstart](one-minute-quickstart.md) and [authorize your first tool](first-tool.md).
- Read the [enterprise access-control model](../security/enterprise-access-control.md)
  and the [initial group configuration](../../config/initial/README.md).
