# UC-01 — Log in and enter a workspace

- **Actor:** Owner
- **Trigger:** Open the application
- **Preconditions:** Owner credentials configured; deployment reachable over HTTPS
- **Related decisions:** ADR-005, ADR-013

## Main flow

1. Authenticate through the built-in login; rotate the session and apply CSRF protection.
2. Show the workspace picker or the last accessible workspace, clearly labeled.
3. Enter that workspace's dashboard. Every link/form carries explicit workspace context.
4. Logout invalidates the session.

## Alternative flows

- Wrong credentials show a generic error and throttling; missing bootstrap credentials prevent startup.
- Session expiry preserves a recoverable workspace draft; reauthentication never submits a publication automatically.
- A stale URL to an archived/missing workspace offers a safe picker without selecting another workspace's resources.
- Separate tabs may use different workspaces; changing one must not retarget the other.

## Postconditions and UI

An authenticated session exists; workspace access is checked independently on each operation. The owner can manage all v1 workspaces, but content queries remain scoped. No long-lived remember-me in v1. Show the workspace name/color persistently, with accessible text labels.
