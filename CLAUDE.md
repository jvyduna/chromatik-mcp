# CLAUDE.md

Project context for AI assistants working in this repo. See [README.md](README.md) for the architecture and [docs/build-plan.md](docs/build-plan.md) for the PR breakdown.

## Two halves

- `server/` — Node MCP server (TypeScript). Scaffolded after `/Users/danoved/Source/touchdesigner-mcp/`.
- `package/` — Java LX package (Maven). Drop-in jar. Reference LX source at `/Users/danoved/Source/LX/`.

Both communicate via the filesystem: the `.lxp` project file (data plane) and `~/.lx-mcp/status.json` (handshake).

## Composability is the prime directive

Every mutation operation lives in its own small, focused function with a narrow signature. Tool handlers compose these primitives; they do not inline mutation logic.

**Rule of thumb**: if you are about to write a tool handler that reaches into a JSON object and mutates it inline, stop. Extract a function with a name that describes the intent (`addModulator`, `setParameterValue`, `addMidiMapping`, …), put it in a module that the tool handler imports, and call it from the handler.

Why this matters here specifically: v1 backends mutate the project file on disk; v2 will hit a live HTTP endpoint on the LX side; v3 may run remote. The same primitives must be re-implementable across all three backends without changing tool handlers. If a primitive is tangled with file I/O or JSON shape, it cannot be swapped.

### Layering

```
tool handler  ──> domain operation  ──> LxClient method  ──> backend impl
(MCP-shaped)     (intent, pure)         (interface)          (file | http | remote)
```

- **Tool handlers** (`server/src/features/tools/*.ts`): parse args via Zod, call domain operations, format the result. No JSON mutation. No I/O.
- **Domain operations** (`server/src/domain/*.ts` or similar): pure functions over the in-memory project model. `addModulator(project, kind, opts) → project'`. No I/O, no async unless calling LxClient.
- **`LxClient` interface** (`server/src/lxClient/lxClient.ts`): the swap point. Read/write project, discover, (future) call live endpoints.
- **Backends** (`server/src/lxClient/fileBackend.ts`, future `httpBackend.ts`): the only place that knows *how* the project is persisted.

### Concrete example — "update a modulator's parameter"

Bad (inline, not swappable):
```ts
// tool handler
const project = JSON.parse(await fs.readFile(path, 'utf8'));
project.engine.modulation.modulators[i].parameters[name] = value;
await fs.writeFile(path, JSON.stringify(project));
```

Good (composed primitives, swappable):
```ts
// domain/modulators.ts — pure
export function setModulatorParameter(p: LxpProject, id: number, name: string, value: ParamValue): LxpProject { ... }
export function findModulatorById(p: LxpProject, id: number): Modulator | undefined { ... }

// tool handler
const project = await lxClient.readProject();
const next = setModulatorParameter(project, id, name, value);
await lxClient.writeProject(next);
```

The pure primitive (`setModulatorParameter`) is reused by every tool that touches modulator parameters and is trivially unit-testable. The `LxClient` calls become single-line swaps when the HTTP backend lands.

### When primitives multiply

If three tools each need to "find the channel by id, then walk to a parameter, then set it," extract a `setParameterByPath(project, oscPath, value)` primitive. Don't duplicate. But: only extract when the third caller appears — two callers is coincidence, three is a pattern.

### What this does **not** mean

- Don't pre-build abstraction layers that aren't used. No factories, registries, or strategy patterns until two real implementations exist.
- Don't wrap every two-line operation in a function. Composability is about *mutation primitives* and *I/O seams* — not formatting helpers or one-shot string assembly.
- Don't introduce dependency injection containers. Plain function arguments and the existing `LxClient` injection are enough.

## Code style

- TypeScript: strict mode, ESM, no `any`. Result-shaped errors (`{ success: true; data } | { success: false; error }`) at handler boundaries — see touchdesigner-mcp for the pattern.
- Java: standard Maven layout, target the LX version pinned in `lx.package`. Keep modulator lifecycle clean — register/unregister listeners symmetrically.
- Comments: only when the *why* is non-obvious. Don't narrate the *what*.
- Tests: every domain primitive gets a unit test with fixture projects. Tool handlers get an integration test (Zod schema + fileBackend against a fixture .lxp).

## References

- Node template: `/Users/danoved/Source/touchdesigner-mcp/` (server layout, transport, tool registration).
- LX source: `/Users/danoved/Source/LX/` (modulator base class, package loading, project serialization).
- Sample project for fixtures: `/Users/danoved/Source/Apotheneum/target/classes/projects/Apotheneum-Test.lxp`.

## Scope guard

Any PR larger than the slices in [docs/build-plan.md](docs/build-plan.md) is too big. If you find yourself touching both Java and Node in one PR (outside the PR-11 convergence demo), split it.
