# lx-mcp — revised build plan (Java-only, Claude Code primary)

## Context

Original plan was Node + filesystem-edited `.lxp` + Java watcher (17 PRs). Feedback on the public PR from Mark Slee (LX creator) and Tracy Scott pushed two changes that collapse the architecture:

- **Mark**: LX already has an OSC reload (`/lx/openProject`, LXEngine.java:1370). The Java per-frame watcher is unnecessary. He also flagged the `LXCommand` API as the "right" mutation path — gives undo, never produces invalid state.
- **Tracy**: putting MCP directly inside the LX package over HTTP avoids re-hoisting LX's runtime semantics into a parallel TypeScript model. The "swappable backend" hand-wave doesn't escape that maintenance tax — even v2 still needs TS types on the agent side.

Combined direction (Path A):

- **MCP server lives inside the LXPlugin jar.** Streamable HTTP MCP transport. Single language (Java), single source of truth, no `.lxp` file editing, no watcher, no reload step.
- **Mutations go through `LXCommand`** where the wrapper is worth it; direct model edits where it isn't. Undo stack works for free where we use LXCommand.
- **Primary client: Claude Code, running locally.** Multi-agent fan-out (orchestrator → channel-builder / modulation-router / MIDI-mapper) comes free via Claude Code's existing Task/subagent feature. We design the tool surface to compose well; Claude Code handles the orchestration. Claude Desktop / Cursor / Codex are also-supported, not primary.

Result: ~7 PRs instead of 17. The Node track and the file-watching track disappear entirely.

## PR breakdown

### PR-1 — Spike: Java MCP SDK + LXCommand coverage

Read-only investigation, output is `docs/spike-findings.md`. Blocks everything else.

- Does the official Java MCP SDK (`modelcontextprotocol/java-sdk`) support streamable-HTTP transport at the maturity we need? Cite versions.
- Can it be embedded inside another long-running JVM process (i.e., the LX runtime) without taking over main? Confirm with a 10-line embed test outside the LX package.
- Inventory existing `LXCommand` categories — `git grep "extends LXCommand"` in the LX source. Map each planned tool to either a matching command or "needs direct model edit + document undo-skip":
  - `add_channel`, `remove_channel`, `set_channel_param`
  - `add_pattern`, `set_active_pattern`, `set_pattern_param`
  - `add_modulator` (MacroKnobs / MacroSwitches / MacroTriggers / LFOs / Envelopes)
  - `wire_modulator` (modulation source → parameter target)
  - `add_midi_mapping`
  - `set_parameter` (generic by OSC path)
- Decide tool-surface granularity for multi-agent fan-out: are tools fine-grained enough that an orchestrator can compose them across subagents, or do we need a higher-level "scene" tool that wraps a sequence?
- Decide port-discovery story: where does the AI client learn the HTTP port? `~/.lx-mcp/status.json` with `{pid, port, projectPath, lxVersion}` written on plugin init is the default.

**Verification**: docs review; the embed test runs and accepts an `initialize` request.

### PR-2 — Maven scaffolding + LXPlugin skeleton

- `package/pom.xml` — LX as `provided`, Java MCP SDK as runtime dep, version pinned from PR-1.
- `package/src/main/resources/lx.package` — JSON descriptor (`name`, `author`, `version`, `lxVersion`).
- `package/src/main/java/<pkg>/LxMcpPlugin.java` — empty `LXPlugin` implementation that registers nothing yet.
- `mvn package` produces `target/lx-mcp-*.jar`.
- Remove the now-unused `server/` directory.

**Verification**: drop the jar in LX's packages folder, restart LX, package appears in LX's package list. No behavior yet.

### PR-3 — Embedded HTTP MCP server + status file

- On `LXPlugin.initialize(lx)`: start the Java MCP server on a free port, register zero tools.
- Write `~/.lx-mcp/status.json` with `{pid, port, projectPath, lxVersion, mcpVersion}`. Update on project change. Delete on plugin teardown.
- All server lifecycle owned by the plugin (no separate modulator yet — the "drop the MCP modulator to opt in" gesture can come back as a follow-up if we want explicit per-project opt-in, but it's not blocking).

**Verification**: with LX running, `cat ~/.lx-mcp/status.json` shows the port. `claude mcp add lx --transport http http://localhost:<port>` followed by `tools/list` returns an empty list.

### PR-4 — First tool: `get_project_info` (read-only)

- Read directly from `lx.engine` (no JSON parsing, no .lxp file). Return `{version, channelCount, modulatorCount, projectPath}`.
- Establishes the tool-registration pattern: schema, domain operation, result formatter, error type.
- Composability: the schema-extraction primitive (e.g. `summarizeProject(LX) → ProjectInfo`) is its own function, called by the tool handler, never inlined.

**Verification**: from Claude Code, invoke the tool, assert the response shape and values against a known project.

### PR-5 — First mutation: `add_macro_knob` via LXCommand

- Find/use the existing `LXCommand` category for adding a global modulator (PR-1 confirms which one). Call `lx.command.perform(new LXCommand...AddModulator(MacroKnobs.class))`.
- UI updates immediately (no reload). Undo stack records the operation.
- Composable primitive: `addGlobalModulator(LX, Class<? extends LXModulator>) → LXModulator` — handler is a one-liner around it.

**Verification**: from Claude Code, invoke `add_macro_knob`. Knob appears live in Chromatik UI. Cmd-Z removes it. No `.lxp` file was touched on disk.

### PR-6 — Tool surface expansion (parallel sub-PRs)

Each tool gets its own small PR; all depend on PR-5's pattern. Group by LXCommand category for review efficiency. Each tool is one composable domain primitive + one handler.

- **PR-6a** — `add_channel`, `remove_channel`, `set_channel_param`
- **PR-6b** — `add_pattern`, `set_active_pattern`, `set_pattern_param`
- **PR-6c** — `add_modulator` (other macro types + LFOs + envelopes)
- **PR-6d** — `wire_modulator` (modulation source → parameter target via OSC path)
- **PR-6e** — `add_midi_mapping`, `remove_midi_mapping`, `list_midi_mappings`
- **PR-6f** — `set_parameter` by OSC path (generic fallback)

Any tool where PR-1 found no matching `LXCommand` falls back to direct in-memory model edits with an explicit note in the tool description ("does not participate in undo").

**Verification**: per-tool, invoke from Claude Code against a fresh project, watch the UI update, verify undo where applicable.

### PR-7 — Install docs + multi-agent usage examples

- `docs/install/claude-code.md` — **primary** install path. `claude mcp add lx --transport http $(jq -r '"http://localhost:\(.port)"' ~/.lx-mcp/status.json)` or equivalent.
- `docs/install/claude-desktop.md`, `docs/install/cursor.md`, `docs/install/codex.md` — also-supported clients, snippet each.
- `docs/multi-agent.md` — usage doc showing how to set up Claude Code subagents on top of lx-mcp. Example: an orchestrator agent that decomposes "build a 3-channel show with one modulated parameter per channel" into specialist subagents (channel-builder, modulation-router) that each call lx-mcp tools. **No new infrastructure** — this is documentation of how Claude Code's existing Task/subagent feature composes with our tool surface.
- Update root `README.md`: replace the Node+file-watcher architecture diagram with the in-process HTTP-MCP picture, list Claude Code as the primary client.

**Verification**: a new contributor following `docs/install/claude-code.md` can run the PR-5 demo end-to-end without prior context. The multi-agent example actually works (recorded run).

## Dependencies and parallelism

```
PR-1 (spike) ──> PR-2 (scaffold) ──> PR-3 (HTTP MCP + status) ──> PR-4 (read tool) ──> PR-5 (first mutation)
                                                                                       │
                                                                                       ├──> PR-6a..f (parallel)
                                                                                       │
                                                                                       └──> PR-7 (docs)
```

PR-1 gates everything. Once it lands, the rest is mostly a sequential critical path (PR-2 → PR-3 → PR-4 → PR-5) because each step depends on the last working. The fan-out is in PR-6 — six sub-PRs that can land in parallel, each its own small mergeable unit. PR-7 can start in parallel with PR-6 once PR-5 demos.

## Critical files to be created

- `docs/spike-findings.md` — PR-1
- `package/pom.xml`, `package/src/main/resources/lx.package` — PR-2
- `package/src/main/java/<pkg>/LxMcpPlugin.java` — PR-2/3
- `package/src/main/java/<pkg>/mcp/HttpServer.java` (or similar) — PR-3
- `package/src/main/java/<pkg>/mcp/StatusFile.java` — PR-3
- `package/src/main/java/<pkg>/tools/GetProjectInfo.java` — PR-4
- `package/src/main/java/<pkg>/domain/Modulators.java` — PR-5 (composable primitives)
- `package/src/main/java/<pkg>/tools/AddMacroKnob.java` — PR-5
- `package/src/main/java/<pkg>/tools/*` — PR-6 (one file per tool group)
- `docs/install/claude-code.md`, `docs/multi-agent.md` — PR-7
- `README.md` — architecture diagram + client list update (PR-7)
- `CLAUDE.md` — needs update to drop the Node/TS code-style guidance and the touchdesigner-mcp reference (PR-2)

## Composability discipline (from CLAUDE.md, restated)

Every mutation is its own small Java function with a narrow signature. Tool handlers compose these primitives; they never inline `LXCommand` construction or model edits. Example:

```java
// domain/Modulators.java — composable primitive
public static LXModulator addGlobalModulator(LX lx, Class<? extends LXModulator> kind) {
  lx.command.perform(new LXCommand.Modulation.AddModulator(kind));
  return lx.engine.modulation.modulators.get(lx.engine.modulation.modulators.size() - 1);
}

// tools/AddMacroKnob.java — handler
public class AddMacroKnob extends Tool<AddMacroKnobArgs, ModulatorInfo> {
  protected Result<ModulatorInfo> handle(AddMacroKnobArgs args) {
    LXModulator m = Modulators.addGlobalModulator(lx, MacroKnobs.class);
    return Result.ok(ModulatorInfo.from(m));
  }
}
```

The primitive (`addGlobalModulator`) is reused by every tool that adds a global modulator. The tool handler is one line and contains no `LXCommand` knowledge. If we later need to swap `LXCommand` for direct model edits in some path, only the primitive changes.

## Verification — end-to-end demo (PR-5)

1. `mvn package` in `package/`.
2. Drop `target/lx-mcp-*.jar` into LX's packages folder.
3. Start LX, open any project.
4. `cat ~/.lx-mcp/status.json` — confirm port is written.
5. `claude mcp add lx --transport http http://localhost:<port>` in a fresh Claude Code session.
6. Prompt: "add a macro knob to the project."
7. Watch the MacroKnobs modulator appear in Chromatik's modulation panel within a second.
8. In Chromatik, Cmd-Z → modulator removed.

For multi-agent verification (PR-7): same setup, prompt: "build a 3-channel show: one solid pattern per channel, a macro knob wired to each channel's brightness." Claude Code's orchestrator decomposes this across subagents that each call `add_channel`, `add_pattern`, `add_modulator`, `wire_modulator`. Final result visible live in Chromatik.

## What we explicitly drop from the original plan

- Node server scaffolding (`server/` directory becomes unused; remove from repo in PR-2).
- TypeScript types mirroring LX's schema.
- `.lxp` JSON manipulation (read or write).
- Atomic temp-write + rename of project file.
- Java per-frame file watcher; mtime polling; echo suppression.
- OSC `/lx/openProject` reload trigger (no longer needed — edits are in-process).
- Claude Desktop `.mcpb` bundle as primary distribution (kept as a secondary install option in PR-7).
