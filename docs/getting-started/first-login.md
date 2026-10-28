# First login

Quickstart provisions separate local `admin`, `reviewer-1` and `reviewer-2`
management identities. Retrieve their generated passwords privately from the
container's `/data/credentials.json` or `/data/bootstrap-password*` files.
There is no universal default password, and passwords are not service-log output.

Sign in at the configured console URL. A first-login password change is not
mandatory; eligible accounts may change their password at login. Demo accounts
explicitly shown on the login page follow the configured demo policy.

Management access does not grant tool execution. Fresh Tools and the local Agent
start disabled; protected default groups grant no runtime access. Configure
enablement, complete grants and execution bindings before trying a tool.

See [Quickstart configuration](../deployment/quickstart.md) for initialization,
the protected setup administrator, demo accounts and credential rotation, or
[production preparation](../deployment/production.md) for external identity.
