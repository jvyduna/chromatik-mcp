# lx-mcp — Node MCP server

The MCP server that AI clients launch. Speaks the Model Context Protocol over stdio.

Discovers the active LX project by reading `~/.lx-mcp/status.json` (written by the Java package). Edits the `.lxp` JSON atomically; the package's file watcher reloads.

Architecture: tool handlers call into an `LxClient` interface. The v1 backend is `fileBackend` (read/write `.lxp`). A future `httpBackend` can swap in without changing tool handlers, transport, or install.

Scaffolded from the [touchdesigner-mcp](https://github.com/looking-glass-factory/touchdesigner-mcp) template.

Not yet implemented.
