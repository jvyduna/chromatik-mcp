# lx-mcp — Java package

The drop-in LX package. Ships as a single jar that LX's class loader picks up from the packages folder.

Contains:
- The MCP modulator (a marker modulator the user drops into a project to enable AI editing).
- The project-file watcher (driven from the modulator's per-frame `run()` method — no engine-loop edits needed in LX).
- The status-file writer (`~/.lx-mcp/status.json`).

Build with `mvn package`. Drop `target/lx-mcp-*.jar` into LX's packages folder.

Not yet implemented.
