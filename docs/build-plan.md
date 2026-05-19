# lx-mcp — initial build plan, broken into small PRs

## Context

`lx-mcp` is a design-stage project ([README.md](../README.md)). Two halves to build:

- **Node MCP server** (`server/`) — scaffolds from [touchdesigner-mcp](https://github.com/looking-glass-factory/touchdesigner-mcp). Mirrors that layout: `src/cli.ts` → `TouchDesignerServer`-style class → `server.tool(name, desc, zodSchema, handler)` registration → swappable client interface (`ITouchDesignerApi` analog → `LxClient`). Adds stdio transport, Zod schemas, fileBackend.
- **Java package** (`package/`) — drop-in jar that contributes an MCP modulator to LX. Modulator extends `LXModulator`, annotated `@LXModulator.Global("MCP")`, ships with a `lx.package` JSON descriptor, runs a status-file writer and per-frame file watcher.

Chosen approach: **parallel tracks**, **small & demoable PRs**, first vertical slice is **`add_macro_knob`**, and **a dedicated .lxp-format spike PR** lands first.

The plan below is sequenced so each PR is independently mergeable, individually demoable, and the two tracks meet at the **end-to-end demo PR (PR-11)**.

## PR breakdown

### Spike (blocks everything)

**PR-1 — .lxp format discovery spike**
Write `docs/lxp-schema.md` capturing:
- Top-level shape: `{version, timestamp, model, engine, externals}` (LX.java:1065–1077).
- `engine.modulation.modulators[]` shape — fully document a `MacroKnobs` entry: `{id, class:"heronarts.lx.modulator.MacroKnobs", label, parameters:{macro1..macro8, label1..label8}}`.
- `engine.mixer.channels[]` outline (we'll deepen later).
- `engine.midi.mapping[]` shape (LXMidiMapping.java:142–150).
- Parameter address convention (`LXOscEngine.getOscAddress`, LXOscEngine.java:375–387).
- Version-pinning rule we'll enforce in the Node refuse-to-edit check.
- Cite source files + line ranges and copy in one trimmed sample (e.g. `Apotheneum-Test.lxp`).
No code, docs only — but **everything else builds on this**.

### Node track

**PR-2 — Node scaffolding**
`server/package.json`, `server/tsconfig.json` (strict, ESM, target ESNext, output `dist/`), Vitest config, Biome config, `.npmrc`. Empty `src/cli.ts` shebang entry. `npm run build` works. Mirror layout from `touchdesigner-mcp/{package.json,tsconfig.json,vitest.config.ts,biome.json}`.

**PR-3 — MCP stdio transport + empty server**
`src/server/lxMcpServer.ts` (analog of `TouchDesignerServer.ts`) and `src/transport/` (lift the stdio half of touchdesigner-mcp's `TransportFactory`). CLI starts server, registers zero tools, responds to `tools/list`. Smoke-test with `@modelcontextprotocol/inspector`.

**PR-4 — `LxClient` interface + `fileBackend` skeleton + status-file discovery**
`src/lxClient/lxClient.ts` defines interface:
```ts
interface LxClient {
  discover(): Promise<ProjectHandle>;            // reads ~/.lx-mcp/status.json
  readProject(): Promise<LxpProject>;             // parses .lxp JSON
  writeProject(p: LxpProject): Promise<void>;     // atomic temp-write + rename
}
```
`src/lxClient/fileBackend.ts` implements it. **No tools registered yet.** Unit-test against fixture `tests/fixtures/status.json` + fixture `.lxp`. Establishes the abstraction so PR-5/6 just plug in.

**PR-5 — First read tool: `get_project_info`**
Registers one tool that returns `{version, channelCount, modulatorCount}`. Proves the full read path end-to-end against a fixture. **Demoable without LX running** — point MCP Inspector at a fixture project. Establishes the tool-registration pattern (Zod schema, result formatter, error handling via `Result<T>`).

**PR-6 — First write tool: `add_macro_knob`**
- Reads project, computes next free `id` (scan existing modulator ids), appends a MacroKnobs JSON object to `engine.modulation.modulators` matching the schema documented in PR-1.
- Atomic write: write to `<file>.tmp`, `fsync`, `rename`.
- Integration test: round-trip a fixture, assert the modulator array grew and the file re-parses.
- **Demoable against a fixture .lxp** before the Java side is wired. Run the tool, diff before/after.

### Java track (parallel to Node track)

**PR-7 — Maven scaffolding for the jar**
`package/pom.xml` (LX as `provided` dependency), `src/main/resources/lx.package` JSON descriptor (`{name, author, version, lxVersion}`), source layout `src/main/java/co/chromatik/mcp/...` (or chosen package). `mvn package` produces `target/lx-mcp-*.jar`. Drop it in LX's packages folder, confirm it shows up in LX's package list — contributes nothing yet.

**PR-8 — MCP marker modulator (visible in UI)**
Add `LxMcpModulator extends LXModulator` annotated `@LXModulator.Global("MCP")`. Constructor calls `super("MCP")`. Override `computeValue(double deltaMs)` returning `0`. Drop jar, restart LX, "MCP" appears next to MacroKnobs in the modulator menu, can be dragged in. No behavior beyond presence.

**PR-9 — Status-file writer (`~/.lx-mcp/status.json`)**
On modulator instantiation, write `{pid, lxVersion, projectPath, mcpVersion, modulatorId}`. On modulator dispose / project close, delete the file. Hook lifecycle via overriding the modulator's `dispose()` plus listening for project-load events via `LX.ProjectListener` if accessible. Manual test: add modulator → file appears; remove modulator → file gone.

**PR-10 — Per-frame file watcher + project reload**
Inside `LxMcpModulator.computeValue(deltaMs)`, poll mtime of the project file (cheap path: `Files.getLastModifiedTime`). On change, and if the change post-dates the last self-write timestamp (echo suppression), call `lx.openProject(currentProjectFile)`. Manual test: edit .lxp out-of-band → LX reloads within ~1s.

### Convergence

**PR-11 — End-to-end demo: `add_macro_knob` against a live LX**
No new code (or minimal glue). Walkthrough in `docs/demo.md`:
1. Build the jar (PR-7) → drop in LX packages folder.
2. Open a project → add the MCP modulator (PR-8) → status file appears (PR-9).
3. Build/run the Node server (PR-2/3) from MCP Inspector or Claude Desktop.
4. Invoke `add_macro_knob` (PR-6) → fileBackend discovers via status file (PR-4) → writes .lxp → watcher (PR-10) reloads → knob appears in Chromatik.
This PR is where the parallel tracks meet. Capture a screen recording.

### Tool surface expansion (each its own demoable PR)

**PR-12 — `list_channels` + `add_channel`** (Mixer read/write.)
**PR-13 — `add_pattern` + `set_active_pattern`** (Pattern operations on a channel.)
**PR-14 — `wire_modulator`** (Modulation routing: source modulator parameter → target parameter via OSC path.)
**PR-15 — `add_midi_mapping`** (Append to `engine.midi.mapping`, validate channel/type/path.)
**PR-16 — `set_parameter` by OSC path** (Generic catch-all using the LXOscEngine convention.)

### Distribution

**PR-17 — Claude Desktop `.mcpb` bundle + per-client install docs**
`server/mcpb/manifest.json` (mirror touchdesigner-mcp's manifest), `docs/install/{claude-desktop,claude-code,cursor,codex}.md`. `npm run build:mcpb` produces `lx-mcp.mcpb`. README's "What you do" steps become actually executable.

## Critical files to be modified or created

- [docs/lxp-schema.md](lxp-schema.md) — PR-1 (new)
- `server/package.json`, `server/tsconfig.json` — PR-2 (new)
- `server/src/cli.ts`, `server/src/server/lxMcpServer.ts`, `server/src/transport/` — PR-3 (new)
- `server/src/lxClient/lxClient.ts`, `server/src/lxClient/fileBackend.ts` — PR-4 (new)
- `server/src/features/tools/getProjectInfo.ts` — PR-5 (new)
- `server/src/features/tools/addMacroKnob.ts` — PR-6 (new)
- `package/pom.xml`, `package/src/main/resources/lx.package` — PR-7 (new)
- `package/src/main/java/.../LxMcpModulator.java` — PR-8 (new)
- `package/src/main/java/.../StatusFileWriter.java` — PR-9 (new)
- `package/src/main/java/.../ProjectWatcher.java` — PR-10 (new)
- `docs/demo.md` — PR-11 (new)
- `server/mcpb/manifest.json`, `docs/install/` — PR-17 (new)

## Reused references (don't reinvent)

From `/Users/danoved/Source/touchdesigner-mcp/`:
- Directory layout + tsconfig + vitest config (PR-2).
- `TransportFactory` stdio path (PR-3).
- `server.tool(name, desc, zodSchema.strict().shape, handler)` registration shape + `Result<T>` error pattern + `createToolResult/handleToolError` helpers (PR-3, PR-5).
- Client-injection pattern for testability (PR-4 — `LxClient` injected into the server factory, mock in tests).
- `mcpb/manifest.json` shape (PR-17).

From `/Users/danoved/Source/LX/`:
- `LXClassLoader` + `lx.package` descriptor pattern (PR-7).
- `LXModulator` base + `@LXModulator.Global` annotation (PR-8); pattern from `MacroKnobs`/`MacroSwitches`/`MacroTriggers`.
- `lx.openProject(File)` for reload (PR-10).
- `LXMidiMapping.save` JSON shape (PR-15).
- `LXOscEngine.getOscAddress` for parameter addressing (PR-14, PR-16).

## Dependencies and parallelism

### Dependency edges

```
PR-1 (.lxp schema spike) ──┬──> PR-4 (fileBackend types)
                           ├──> PR-6 (add_macro_knob schema)
                           └──> PR-15 (MIDI mapping shape)

PR-2 (Node scaffold) ──┬──> PR-3 (server + transport)
                       └──> PR-4 (LxClient + fileBackend)

PR-3 + PR-4 ──┬──> PR-5 (get_project_info)
              └──> PR-6 (add_macro_knob)

PR-7 (Maven scaffold) ──> PR-8 (marker modulator) ──┬──> PR-9 (status writer)
                                                    └──> PR-10 (watcher/reload)

PR-6 + PR-9 + PR-10 ──> PR-11 (end-to-end demo)

PR-11 ──> PR-12, PR-13, PR-14, PR-15, PR-16  (all independent of each other)

PR-3..PR-16 stabilized ──> PR-17 (mcpb bundle + install docs)
```

### Suggested waves

**Wave 0 — start in parallel, no blockers:**
- **PR-1** (docs spike)
- **PR-2** (Node scaffold)
- **PR-7** (Maven scaffold)

**Wave 1 — kick off as their wave-0 deps merge:**
- **PR-3** (needs PR-2)
- **PR-4** (needs PR-2 + PR-1)
- **PR-8** (needs PR-7)

**Wave 2 — first real features, all parallelizable:**
- **PR-5** (needs PR-3 + PR-4)
- **PR-6** (needs PR-3 + PR-4 + PR-1)
- **PR-9** (needs PR-8)
- **PR-10** (needs PR-8)

**Wave 3 — convergence (the critical merge point):**
- **PR-11** (needs PR-6, PR-9, PR-10) — the only PR that requires all three other tracks. Treat as the milestone gate.

**Wave 4 — fan-out tool surface, fully parallel:**
- **PR-12, PR-13, PR-14, PR-15, PR-16** — each only depends on PR-11's foundation. Reviewing them as small, focused PRs in parallel is fine.

**Wave 5 — distribution:**
- **PR-17** — better after the tool surface settles so install docs reflect reality.

### Critical path

`PR-1 → PR-2 → PR-3 → PR-4 → PR-6 → PR-11` is the longest dependency chain on the Node side. The Java side `PR-7 → PR-8 → PR-9 → PR-11` (or `→ PR-10 → PR-11`) is shorter. The end-to-end demo (PR-11) cannot land until both chains complete, so the Java track has slack — if a person is rotating, prioritize PR-3/PR-4/PR-6 to keep the critical path moving.

### What you cannot parallelize

- PR-3 and PR-4 *can* technically be done in parallel (PR-4 doesn't touch the MCP server), but PR-5/PR-6 need both — so don't start the tool PRs until both are merged.
- Within the Java track, PR-9 and PR-10 are parallel after PR-8, but both touch `LxMcpModulator` lifecycle — be ready for a merge conflict between them.
- PR-11 is a convergence PR by design. Don't try to split it.

## Verification

Per-PR demos:
- **PR-1**: docs review only.
- **PR-2/3**: `npm run build` succeeds; `npx @modelcontextprotocol/inspector dist/cli.js` connects and lists 0 tools.
- **PR-4**: `npm test` — fileBackend unit tests pass against fixtures.
- **PR-5/6**: MCP Inspector against a fixture .lxp, invoke tool, inspect response and (PR-6) the on-disk diff.
- **PR-7**: drop jar in LX packages folder, restart LX, confirm package appears in LX's package list.
- **PR-8**: "MCP" appears in the modulator menu, instantiable, visible in modulation panel.
- **PR-9**: add modulator → `cat ~/.lx-mcp/status.json` shows current project; remove → file gone.
- **PR-10**: with modulator active, `jq '.engine.modulation.modulators += [...]' project.lxp | sponge`; LX reloads within ~1s.
- **PR-11**: end-to-end recording — AI client → tool call → knob appears.
- **PR-12–16**: each tool has a fixture-based integration test plus a live-LX manual check.
- **PR-17**: install the `.mcpb` in Claude Desktop, run the demo from PR-11 without manual config.

Cross-PR check: at every step after PR-11, the live demo from PR-11 must still work — no regressions in the end-to-end path.
