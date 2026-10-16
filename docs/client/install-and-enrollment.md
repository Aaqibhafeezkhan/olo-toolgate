# Install and Enrollment

For the complete current registry, execution filters, server-device identities and administrative API, use the linked reference below.

See [Device registry and tool-call controls](../control-plane/device-registry.md).

## Purpose

Normal install uses `olo-toolgate-client install --server <URL>`, browser/device authentication, device key generation and registration.

In the console, open **Devices → Enroll device**. All unexpired requests in your
tenant appear under **Devices waiting for approval**, refreshed every five seconds.
Select **Review**, compare the code and fingerprint with the client, and choose
**Until a date and time** in your local time or **Unlimited time** before selecting **Enroll device**.
You can also deny a request. Pending requests expire after ten minutes.

Connection approvals default to 24 hours. The server stores the chosen deadline
and rejects device access when it expires; certificate renewal cannot extend it.
The Clients view shows **Approval expired** after the deadline. Devices enrolled
before deadline support retain their existing approval behavior. API approval
callers that omit `connectionExpiresAtUnixMs` receive a 24-hour approval unless
they explicitly set `unlimitedConnection: true`. Unlimited approval has no access
deadline; certificates still expire after at most 24 hours and renew. An approved
key can recover a certificate after being offline past its certificate expiry,
without changing its registered owner, approval, enablement, or access deadline.

**Devices → Clients** lists registered and pending devices together, with device
names, registered users, connection status, approval, expiry and detail tooltips.
**Disable / Enable** controls directory access independently of
**Deapprove / Approve**. Temporary suspension returns HTTP 423; clients retain
their protected key and retry so access can resume after reapproval or enablement.
Permanent API revocation still rejects that key permanently.

Quickstart registers no devices of its own and runs no tools itself: every row is
an enrolled client or Tool Host, approved the same way.
