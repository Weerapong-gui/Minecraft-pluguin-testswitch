# TestSwitch (Velocity plugin)

Adds `/test` and `/survival` to a Velocity proxy so approved players can hop
between the main survival server and an on-demand creative test server.

- `/test` - moves you to the `test` server. If it is not running, starts the
  systemd user unit `mc-test.service`, waits until it answers pings (up to 120s),
  then moves you.
- `/survival` - moves you back to `survival`.
- The test server is stopped after it has been empty for 10 minutes.
- Only names listed in `plugins/testswitch/approvers.txt` can use the commands
  (Bedrock/Floodgate names start with a dot). Everyone else is denied
  `velocity.command.*` (e.g. `/server`) and can never connect to `test`.

## Requirements
- Velocity 4.3+ (Java 25+)
- Servers named `survival` and `test` in `velocity.toml`, with `try = ["survival"]`
- `mc-test.service` as a systemd **user** unit, and Velocity running as the same user

## Build
```
./build.sh /path/to/velocity.jar   # -> build/TestSwitch.jar
```
Copy `build/TestSwitch.jar` into Velocity's `plugins/` folder and restart.
