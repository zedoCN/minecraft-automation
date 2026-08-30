# Changelog

## [Unreleased]

### Project packaging

- Named the local fork Minecraft Automation; preserved the `mcpfabric` identifiers and MIT attribution.
- Added the generated mechanical-builder icon, public-facing documentation, architecture notes and asset provenance.
- Prepared GitHub-only release automation for the primary 26.2 target; removed the upstream Modrinth publishing target.
- Read the MCP handshake version from package metadata instead of a stale source constant.
- Kept local worlds, credentials, test servers and migration history out of tracked public documentation.
- Replaced a stale hard-coded command-count test with catalog/definition correspondence checks;
  GitHub Build now runs the MCP test suite.

### Validation

- MCP typecheck/build and isolated stdio handshake passed; version `.31` and six catalog tools confirmed.
- Minecraft 26.2 build passed with tests skipped; packaged icon hash matches the source asset.
- Workflow YAML parsing and whitespace checks passed. No full automated suite, live-game restart,
  GitHub Actions run or publication was performed for this packaging change.
- Subsequent publication gate: all 13 MCP automated tests passed; Gitleaks found no leaks in
  the pending changes or the history reachable from the published branch. This is not a new
  live-game acceptance run or a guarantee that pattern scanning detects every possible secret.

## [0.3.0-zedo.31] - 2026-08-30

### Changed

- Added `baritoneAllowParkour` as a native parkour toggle. Legacy `maxGapJumpBlocks` remains
  compatible but is explicitly not a hard distance limit in Baritone. Status exposes the
  effective parkour and sprint capability and reports that no parkour distance cap is enforced.
- Clarified that native Baritone avoidance does not promise the built-in neutral-entity waiting
  behavior. Non-blocking cows and stepping over a boat are not failed obstacle-avoidance proofs.
- Added optional, normal-client-exit-only Baritone cleanup: cancel paths, request cache save,
  then terminate its executor after Minecraft closes using bounded grace/interruption periods.
  This requires a restarted client and a subsequent normal exit for runtime acceptance.

### Live baseline verification (running .30, not yet the .31 candidate)

- Survival 2-block non-sprint and 3-block sprint parkour both reached the destination at full health.
- Parkour disabled returned `no_path`; an isolated platform returned `no_path` after reachable progress.
- A 29-block route around a contained 10-by-7 lava pool reached in about 7.5 seconds at full health.
- A wall inserted during a second 29-block route caused a detour; the goal was reached in about
  7.5 seconds at full health and all 21 wall blocks remained intact.
- Neutral-entity avoidance remains unverified. The previous gap-cap observation is documented
  native behavior, not a pending hard-limit requirement.

## [0.3.0-zedo.30] - 2026-08-30

### Changed

- `backend=auto` now selects a compatible Baritone for every ordinary navigation request. It
  falls back to the built-in planner only when Baritone is unavailable, fails to start, or exact
  custom block/fluid avoidance requires built-in semantics.
- Baritone navigation accepts requested gaps up to three blocks. Its native parkour handles
  two-block gaps without sprint capability and three-block gaps with sprint capability; the
  built-in backend remains explicitly bounded to one-block gaps.
- Baritone route mutation is available through explicit `baritoneAllowBreak`,
  `baritoneAllowPlace`, `baritoneAllowInventory`, and `baritoneAllowParkourPlace` arguments. All
  remain false by default so callers opt into world or inventory mutation deliberately.
- Baritone completion follows its integer block-goal semantics. An inactive process with no goal,
  path, or calculation now reports terminal `no_path` instead of waiting until the outer timeout.

### Verified

- Explicit Baritone completed a 41-block generated-forest route in 17.8 seconds with full health.
  `auto` selected Baritone for a 54.5-block return route and completed it in 28 seconds, also with
  full health.

## [0.3.0-zedo.29] - 2026-08-30

### Added

- Navigation now has pluggable `auto`, `builtin`, and optional `baritone` backends without
  bundling Meteor Client or adding a hard Baritone dependency. The adapter discovers a compatible
  public Baritone API at runtime, and explicit `baritone` requests refuse clearly when absent.
- `navigation_backends` reports live backend availability and capability differences. Start and
  status results expose requested/selected backends plus bounded automatic-fallback reasons.
- The Baritone adapter applies conservative movement settings (no breaking, placing, inventory
  moves, bucket falls, or parkour placement) and retains MCPFabric timeout, damage, air, and fire
  stop guards.

### Verified

- Before backend integration, `.28` resumed the original generated-forest route beyond the low
  leaves failure, descended from Y110 to Y105, completed a rolling segment and two planned gap
  jumps with full health and no replan.

## [0.3.0-zedo.28] - 2026-08-30

### Fixed

- Downward path edges now validate the full destination column through the source player's head
  height. Natural low leaves and other overhangs can no longer produce a nominally standable but
  physically impossible descent.
- Stuck detection measures horizontal waypoint progress, so jumping in place cannot reset it.
  Ordinary blocked nodes trigger a bounded replan that excludes the exact failed cell instead of
  entering an infinite jump loop.

### Observed

- Live forest traversal found a one-block descent from Y113 to Y112 whose standing space was clear
  but whose Y114 entry clearance contained oak leaves. The old driver remained at the ledge and
  repeatedly jumped without advancing.

## [0.3.0-zedo.27] - 2026-08-30

### Fixed

- Planner-approved downward transitions temporarily release vanilla sneak so natural one-block
  descents do not stop at the ledge; same-height exposed routes retain edge guard.
- Waypoint completion now requires both horizontal centering and vertical settlement. Falling or
  stepping progress contributes to stuck detection instead of allowing a lower node to be skipped
  while the player is still standing one block above it.

### Observed

- Real generated forest terrain reproduced the old failure twice at successive one-block descents:
  movement stopped safely with full health and `stuck_on_exposed_path`, providing an exact bounded
  case for the `.27` correction.

## [0.3.0-zedo.26] - 2026-08-30

### Fixed

- Open wooden doors remain traversable during live safety revalidation even though Minecraft keeps
  the rotated door panel's thin collision shape in the original block cell.
- Hostile avoidance measures horizontal distance to the player and exact upcoming nodes instead of
  using the diagonal corners of an inflated route bounding box, allowing a successful detour to
  resume instead of entering a safe but permanent replan loop.
- Exposed-edge classification searches for support through the configured safe drop depth. Ordinary
  one-block mountain slopes therefore no longer force continuous sneaking, while deeper cliff edges
  retain edge guard.
- Ordinary collidable entities are filtered against exact upcoming path cells after a broad lookup,
  preventing bent-route bounding-box corners from causing the same false-positive loop as hostiles.
- A nearby final target whose client chunk is not synchronized can advance through shorter loaded
  rolling anchors; a loaded but genuinely unreachable final target still refuses immediately.
- Navigation clears stale held-jump input when it starts, hostile clearance accounts for large
  entity bounding boxes, and repeated entity replans retain their wait counter for a bounded cadence.

### Verified

- Live `.25` acceptance completed a one-block gap jump, native fence-gate traversal, and a 199-block
  route across eight rolling segments. Door and hostile tests produced the two bounded failures
  corrected above before `.26` packaging.

## [0.3.0-zedo.25] - 2026-08-30

### Added

- `preview_navigation` performs a read-only first-segment plan and returns bounded exact nodes with
  exposed-edge, gap-jump, and closed door/gate annotations before player movement begins.
- Rolling navigation waits and retries at a segment boundary while client chunks or the next route
  become available, with configurable wait duration and explicit wait state/counters.
- Closed wooden doors and fence gates are treated as planned native interaction actions: navigation
  approaches, stops, opens with ordinary main/offhand use, verifies collision changes, and refuses
  after a bounded failure instead of walking into the obstacle forever.
- Safe navigation expands nearby hostile mobs into dynamic avoidance regions, exposes configurable
  entity lookahead, and stops on low air or active fire in addition to health loss.
- Live status includes five upcoming exact nodes, complete safety controls, segment wait state, and
  current door/gate interaction evidence.

### Fixed

- One-block gap jumps retain the `.24` sprint-priming correction while the expanded navigation
  features ship together, avoiding another restart between jump and long-distance acceptance.

## [0.3.0-zedo.24] - 2026-08-30

### Fixed

- Gap jumps now use a one-tick forward/sprint priming phase before committing a bounded held jump.
  This replaces the insufficient low-momentum launch observed when leaving automatic bridge sneak;
  navigation status exposes `priming` versus `airborne` for live diagnosis.

## [0.3.0-zedo.23] - 2026-08-30

### Added

- Distant `navigate_to` targets are automatically split into loaded rolling A* segments with
  configurable length, adaptive shorter-anchor fallback, overall timeout, and continuous hazard
  and entity revalidation across segment boundaries.
- Navigation status reports the current segment goal, whether it is final, configured segment
  length, and completed segment count separately from dynamic replan count.
- Safe navigation stops immediately after player damage rather than continuing an autonomous task
  through an unknown combat, fall, or environmental hazard.
- A* can emit explicit one-block gap-jump edges only when the launch headroom, complete flight
  volume, same-height landing, and configured hazard rules are safe. The driver centers on the
  launch block, commits a bounded jump input, tracks completed jumps, and aborts a failed landing.

### Changed

- Safe and balanced route costs now prefer an interior dry lane beside a hazard over a platform
  edge, while still allowing a one-wide bridge when it is the only route.
- Entity detection looks three path nodes ahead, giving exposed routes more stopping distance before
  a collidable player, mob, armor stand, boat, or minecart.

## [0.3.0-zedo.22] - 2026-08-30

### Added

- Navigation safety profiles now classify fluids, lava, damaging contact blocks, hot ground,
  adjacent hazards, exposed edges, configurable maximum drops, and caller-supplied exact mod
  block/fluid hazard IDs during A* planning.
- Live navigation revalidates each upcoming node and replans if the world changes underneath it.
- Collidable entities are treated as dynamic obstacles: open terrain can be replanned around, while
  exposed one-wide routes stop and wait rather than pushing an entity or the player into a fall.
- Exposed-edge traversal automatically disables sprint, holds sneak, aims at exact block centers,
  tightens waypoint completion, and forbids the old blind stuck-jump recovery.
- `navigation_status` exposes the active safety profile, edge guard, safety reason, entity wait,
  blocking entity types, and replan counters.

## [0.3.0-zedo.21] - 2026-08-30

### Changed

- Survival `break_block` now waits for synchronized completion by default, supports an explicit
  timeout/non-blocking mode, and reports when a newer mining request interrupted the target.

### Fixed

- Exact goal-based container transfers refuse partial moves from output-only slots before sending
  input when the native menu cannot return the remainder to its source.
- Completed transfers derive `actualCount` and aggregate `movedCount` from synchronized source-slot
  deltas, and stop with explicit mismatch evidence instead of trusting the preflight plan.

## [0.3.0-zedo.20] - 2026-08-30

### Added

- `use_block_at` now defaults to `hand: "auto"`, reproducing Minecraft's ordinary main-hand then
  offhand interaction order. It falls back only after a native `Pass`, never after a consumed or
  failed interaction, and reports every attempted hand plus the final hand and result.

## [0.3.0-zedo.19] - 2026-08-30

### Added

- `find_screen_widgets` filters nested vanilla or mod GUI controls by class, visible message,
  current text value, focus, enabled state, and visibility while returning exact stable paths and
  hitboxes.
- `click_screen_widget` now accepts the same semantic selectors, rejects ambiguous matches, can
  click an exact relative point inside a control, and reports compact before/after screen identity.
- `set_screen_text` can resolve an edit box semantically instead of requiring a fragile numeric
  widget path.
- `use_block_at` targets an exact normalized point on a chosen block face with either hand,
  optional atomic sneak, optional yaw/pitch, interaction-range validation, and integrated-server
  pose settlement before sending the native interaction.

## [0.3.0-zedo.18] - 2026-08-30

### Added

- `place_block_at` and ordered structure placements can temporarily sneak against interactive
  supports and restore the previous sneak state after the server-confirmed interaction.
- Placement confirmation can explicitly accept a different non-air final block id when a mod
  transforms a newly placed block while forming a multiblock.

### Fixed

- Partial exact moves from output-only slots now choose a native-compatible ordinary player slot
  for the remainder before mutation instead of stranding it on the carried cursor.
- Exact and goal-based container moves no longer reject actively ticking machines merely because
  synchronized progress data changed between planning and native input.
- Command batches stop on successful RPC envelopes whose command result reports
  `completed: false`, while intentional dry runs remain successful.
- Bridge calls reserve transport headroom beyond command-level `timeoutMs`, allowing long
  `wait_screen` calls to return their domain result instead of a misleading unreachable error.

## [0.3.0-zedo.17] - 2026-08-30

### Fixed

- `select_hotbar_slot` now waits for stable integrated-server confirmation before returning instead
  of briefly exposing a client/server selected-slot mismatch. Confirmation timeouts preserve
  packet-sent evidence and explicitly forbid blind retries; remote multiplayer remains client-cache
  confirmed because its authoritative inventory is unavailable.

## [0.3.0-zedo.16] - 2026-08-30

### Added

- `reconcile_inventory` replaces stale creative/client prediction with one integrated-server
  authoritative snapshot and returns exact before/after mismatches.
- Screen snapshots identify whether the visible GUI is actually bound to its container menu.

### Fixed

- Container clicks, buttons, transactions, exact moves, and goal-based transfers now refuse to
  mutate the hidden player `InventoryMenu` behind non-container mod screens such as dashboards.

## [0.3.0-zedo.15] - 2026-08-30

### Added

- `transfer_container_items` turns player-to-machine/storage and machine/storage-to-player goals
  into a bounded preflight plan of component-aware native moves, with exact-count refusal,
  equipment protection, optional slot allowlists, dry runs, and partial-failure evidence.

### Changed

- Condition-based `wait_screen` results keep complete slots internally but return only the requested
  slot/data evidence, screen identity, and matched conditions instead of the complete menu.

## [0.3.0-zedo.14] - 2026-08-30

### Fixed

- `wait_screen` now retains empty slots in its internal snapshot whenever slot conditions are
  requested, so explicit `empty: true` and zero-count conditions can match real menu slots.

## [0.3.0-zedo.13] - 2026-08-30

### Added

- Local inventory snapshots now include an integrated-server authoritative snapshot, consistency
  status, and exact mismatch slots, making stale creative-menu copies and client prediction visible.
- `wait_screen` accepts generic slot/data conditions and selectable full, slot, or identity stability
  scopes so automation can wait on actively ticking vanilla and mod machines.
- Raw GUI input now covers mouse hover movement, horizontal/vertical wheel scrolling, double clicks,
  and key releases for mod lists, recipe views, scrolling panels, and held controls.

### Fixed

- Confirmed container transactions now stabilize on continuously satisfied postconditions rather
  than requiring the complete menu JSON to stop changing.
- High-level moves into consumptive machine slots no longer falsely time out when the machine
  immediately consumes the inserted item after the source move has been server-confirmed.

## [0.3.0-zedo.12] - 2026-08-30

### Added

- Item snapshots now encode the full non-default data-component patch, expose a canonical SHA-256
  component fingerprint, and bound unusually large component payloads without losing identity.
- Container snapshots expose synchronized integer data values, GUI origins, and absolute 16x16 slot
  hitboxes in addition to menu-local coordinates.
- Slot and data-value pre/postconditions can participate in confirmed transactions; transaction
  deltas now distinguish slot, carried-stack, and synchronized-data changes.
- `move_container_item` performs capacity-checked, component-aware whole or partial stack moves with
  automatic native click planning, source-remainder restoration, screen identity guards, explicit
  cleanup, and stable server confirmation.

### Changed

- Access to private vanilla container layout/data fields uses small Fabric Mixin accessors rather
  than menu-specific class names, allowing the same protocol to describe compatible mod menus.

## [0.3.0-zedo.11] - 2026-08-30

### Fixed

- Screen snapshots no longer call the unsupported menu-type constructor path used by the player's
  screen-less inventory menu; menu type is now optional for menus that cannot expose one.
- Screen fingerprints are always calculated from the same complete canonical slot state, so
  omitting empty slots from a response cannot create a false GUI-change signal.

### Changed

- Confirmed container transactions and transaction failures return compact identity snapshots plus
  exact changed-slot evidence instead of duplicating the complete menu twice.

## [0.3.0-zedo.10] - 2026-08-30

### Added

- Generic recursive GUI discovery exposes nested vanilla and mod controls through stable widget
  paths, while retaining legacy top-level widget indexes.
- Self-describing container snapshots now include menu type, state id, slot/container classes,
  item limits, player-inventory roles, and a deterministic full-screen fingerprint.
- `wait_screen` waits for constrained, stable GUI transitions; `probe_container_slot` invokes the
  active vanilla or mod slot implementation to test real item acceptance.
- `container_transaction` guards native click sequences with GUI identity and slot preconditions,
  waits for stable server-synchronized postconditions, reports exact item deltas, rejects concurrent
  screen changes, and only performs cursor cleanup into an explicitly supplied safe slot.

## [0.3.0-zedo.9] - 2026-08-30

### Fixed

- Creative instant removal now uses Minecraft's native start-destroy protocol instead of invoking
  the client prediction helper directly. Structure replacement/removal and `break_block` therefore
  reach the server before authoritative air confirmation.

## [0.3.0-zedo.8] - 2026-08-30

### Added

- `inspect_structure` compares up to 4096 expected blocks against authoritative integrated-server
  state or the synchronized remote client cache. It classifies missing, unexpected, wrong-block,
  and wrong-state differences, supports bounded `unspecifiedAsAir`, and returns path-validated work
  site suggestions for targets outside the player's current reach.
- `edit_structure` incrementally repairs up to 64 differences instead of rebuilding matching blocks.
  It performs reach, destructive-intent, creative-mode, block-entity, and rollback-capability checks
  before the first mutation.
- Each edit revalidates its inspected before-state immediately before mutation, refusing concurrent
  changes. Integrated-server edits can snapshot exact block states and automatically roll back after
  an execution or final-postflight failure.

## [0.3.0-zedo.7] - 2026-08-30

### Fixed

- `build_structure` now performs an authoritative postflight over every completed placement after
  neighbor updates settle. Later pistons, fluids, redstone, gravity, or other updates can no longer
  silently invalidate an earlier placement while the overall build still reports success.
- Final-state mismatches return `structure_unconfirmed` with the exact placement index, target,
  expected state, and actual state.

## [0.3.0-zedo.6] - 2026-08-30

### Fixed

- Exact placement now waits until the integrated server has observed a changed player pose before
  sending the use packet, preventing orientation-sensitive blocks from using the previous yaw.
- Exact placement rejects supports outside the server's real interaction range before mutating.
- Requested confirmation mismatches now return a structured `placement_unconfirmed` RPC failure,
  including predicted and actual state, instead of looking like a successful command.
- Structure preflight reports `support_out_of_reach`, and per-placement RPC error data is preserved.

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.0-zedo.5] - 2026-08-30

### Added
- `place_block` accepts optional `yaw` and `pitch`, allowing orientation-sensitive placement to
  publish the intended player pose atomically with the interaction.
- `place_block_at` targets the destination coordinate directly, optionally selects an exact hotbar
  item, discovers an adjacent support, and applies pose plus placement as one natural editing action.
- State-semantic placement: `expectedProperties` lets callers request block states such as
  `facing=east`; MCPFabric searches placement poses before committing the interaction.
- Dynamic placement confirmation reads the authoritative integrated-server block state when
  available, or requires stable client-cache observations on remote multiplayer.
- `build_structure` preflights and executes up to 64 dependency-ordered placements with exact
  per-block outcomes, material/support checks, confirmation, and stop-on-first-failure behavior.
- Precise placement can temporarily swap required materials from main inventory slots into the
  active hotbar slot, then restore both the inventory layout and original selection after confirmation.

### Fixed
- `command_batch` now inserts a bounded 100 ms server-settle barrier after look and placement
  commands. Live integrated-server acceptance showed that back-to-back orientation changes could
  otherwise make the following placement use the previous command's yaw.
- The client now stops the embedded JDK HTTP server during `CLIENT_STOPPING`, preventing its
  non-daemon `HTTP-Dispatcher` from keeping the JVM alive until Minecraft's post-main shutdown
  watchdog reports a false crash.
- `look` and `look_at` now immediately queue a multiplayer rotation packet instead of waiting for
  the next vanilla player tick. A following placement in the same `command_batch` therefore no
  longer reaches the server with stale yaw/pitch.
- `place_block` re-publishes the current rotation immediately before its interaction packet and
  reports the pose used in its result.

## [0.3.0-zedo.3] - 2026-08-30

### Added
- Read-only client-cache block, region, block-search, and entity queries for remote multiplayer
  servers where no integrated or dedicated MCPFabric server is present.
- Deterministic `use_block` and explicit `use_item_in_air` commands. `use_item` now performs an
  ordinary right-click against the crosshair target before falling back to air.
- Live command availability metadata and `availableOnly` catalog filtering.
- `command_describe_many` for retrieving up to 32 exact schemas in one round-trip.

### Changed
- `minecraft_status` now merges live client player, level, dimension, connection, and GUI status.
- Placement and use results report their resolved target and held item for easier postcondition
  checks.

### Fixed
- `use_item` no longer claims to interact with the crosshair target while only using the held item
  in air.
- Region scans now enforce their documented volume cap before entering the scan loop, preventing a
  sparse oversized request from monopolizing a game thread.
- The MCP instructions no longer claim server write/command capabilities that are absent in remote
  multiplayer sessions.

## [0.2.1] - 2026-07-30

### Fixed
- The bot's per-tick input driver forced movement key state every client tick, even when idle,
  permanently overriding the player's own WASD/jump/sneak/sprint input once the mod started
  ticking. Keys are now only forced while a movement command or navigation is active, and
  released back to the keyboard once it stops.

### Added
- A production-ready project icon for Fabric metadata, Modrinth, and GitHub presentation.
- Repository community files and a canonical Modrinth listing guide.

### Changed
- Project links now point to the canonical `Etoryx/mcpfabric` repository.
- GitHub Actions are pinned to immutable commit SHAs and release publishing fails closed when the
  Modrinth token is unavailable.
- The MCP server lockfile version now matches the package version.
- The MCP SDK and vulnerable transitive packages were updated; `npm audit` reports zero known
  vulnerabilities.

### Security
- The bridge bearer token is no longer printed to logs; read it from
  `config/mcpfabric.config.json`.

## [0.2.0] - 2026-06-19

### Added
- **Multi-version support** via [Stonecutter](https://stonecutter.kikugie.dev/): the mod now builds
  for Minecraft 1.21.1–1.21.11 and the 26.x line (26.1.x, 26.2) from a single source tree.
  Per-version jars are produced as `mcpfabric-<modVersion>+<mcVersion>.jar`.
- `./gradlew chiseledBuild` to build every supported version; per-version configuration lives in
  `versions/<mcVersion>/gradle.properties`.
- GitHub Actions CI building all versions and type-checking the MCP server.
- Automated releases: pushing a `v*` tag builds every supported version, publishes them to Modrinth
  (one version per Minecraft release), and creates a GitHub Release with all jars attached.
- `CONTRIBUTING.md`, `SECURITY.md`, `docs/RELEASING.md`, issue/PR templates, Dependabot config.

### Changed
- Build upgraded to Fabric Loom 1.17.x and Gradle 9.5.x.
- README is now in English.

## [0.1.0]

- Initial single-version (Minecraft 1.21.8) release: Fabric mod with an embedded HTTP bridge and a
  TypeScript MCP server exposing ~50 tools for full read & control of Minecraft.
