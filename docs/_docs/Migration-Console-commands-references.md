Reference for migration console commands

Owner: @mikaylathompson

```
console
Usage: console [OPTIONS] COMMAND [ARGS]...

Options:
  --config-file TEXT  Path to config file
  --json
  -v, --verbose       Verbosity level. Default is warn, -v is info, -vv is
                      debug.
  --help              Show this message and exit.

Commands:
  backfill    Commands related to controlling the configured backfill...
  clusters    Commands to interact with source and target clusters

  completion  Generate shell completion script and instructions for setup.
  kafka       All actions related to Kafka operations
  metadata    Commands related to migrating metadata to the target cluster.
  metrics     Commands related to checking metrics emitted by the capture...
  replay      Commands related to controlling the replayer.
  snapshot    Commands to create and check status of snapshots of the...
```