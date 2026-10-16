# QueueLoginBridge

Paper plugin for the `login` backend, built with Gradle and Java 21.
Compiles against Paper 1.21.4 and the supplied AuthMe 5.7.0 fork.

## Build

The Gradle wrapper is included. Place the supplied AuthMe JAR at
`libs/AuthMe-5.7.0-FORK-Universal.jar` (already copied in this workspace), then run:

```powershell
.\gradlew.bat build
```

Output: `build/libs/QueueLoginBridge-1.0.1.jar`.
AuthMe and Paper are compile-only dependencies and are not bundled in the output JAR.

## Install

1. Install the bridge JAR in `login/plugins/` alongside AuthMe. Do not install it on Velocity or main.
2. Keep the updated `VelocityQueue-1.0.8-RELEASE.jar` on Velocity, with `queue-server: "login"`.
3. For this bridge setup, set `Hooks.velocity: false` in the login backend's AuthMe config.
   The bridge replaces AuthMe's one-shot notifications. This supersedes the old queue README's
   `Hooks.velocity: true` instruction when using this companion plugin. If another integration
   depends on AuthMe's native Velocity hook, review that dependency before disabling it.
4. Restart the login backend. Restart Velocity too if replacing its queue JAR.
5. On the login backend console, run `queuelogin <player>` to inspect the current API state.
   In-game use requires `queuelogin.admin` (operators by default).

## Behavior

On join, the bridge checks `AuthMeApi.isRegistered(username)` once and caches the account stage.
AuthMe login and registration events mark the account registered; unregister and quit events
update or clear the cache. A main-thread heartbeat checks each online player's actual
`AuthMeApi.isAuthenticated(player)` state every 20 ticks and sends the cached account stage too.
On a lagging server, 20 ticks can take longer than one second.

Login reports use `authmevelocity:main` with two `writeUTF` values: `LOGIN` or `LOGOUT`, followed
by the carrier player's name. Registration reports use `queue:account` with `REGISTERED` or
`UNREGISTERED`, followed by the same name. VelocityQueue accepts both only from that player's
current connection to its configured login backend and consumes registration reports locally.
It shows `/register <create your password> <re-type your password>` for new accounts and
`/login <password>` for existing ones. Repeated reports do not bypass priority or AntiFlood.

Being premium, registered, or merely connected is not authentication. Premium automatic login
works only if the premium integration actually marks the player authenticated in AuthMe.
The bridge does not force-login anyone or move players. It does not fix main-server read timeouts.
Protect the login backend from direct public connections as required for your proxy setup.

Unit tests verify state transitions, heartbeat packet compatibility and offline-player handling.
A live Paper + AuthMe + Velocity smoke test is still required: test manual login, restored session,
logout, reconnect, `/leave` followed by repeated heartbeats, and transfer to main.
