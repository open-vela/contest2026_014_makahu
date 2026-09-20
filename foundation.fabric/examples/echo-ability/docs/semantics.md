# Semantics

Each peer may send bounded reliable messages. Receivers return the exact opaque payload. Duplicate business requests are identified by the Ability payload, and reconfiguration switches atomically to the new epoch.
