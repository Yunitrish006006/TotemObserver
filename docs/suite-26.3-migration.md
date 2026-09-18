# Minecraft 26.3 source migration

Status: local source migration under validation, not a published release. Owner: Codex parent session. Date: 2026-09-16.

## Platform and source versions

Minecraft 26.3, Java 25, Fabric Loader 0.19.5, Fabric API 0.160.5+26.3, Loom 1.17.12. Mapped development modules use modern-yarn 26.3+build.1; Observer and VanillaTweaks retain their official-namespace builds. Consumer metadata requires Core `>=0.7.22 <0.8.0`.

| Module | Source version |
| --- | --- |
| Core | 0.7.22 |
| Alchemy | 0.1.52 |
| Automata | 0.1.27 |
| DiscordBridge | 0.1.14 |
| Enchanting | 0.1.14 |
| Excavation | 0.1.15 |
| Locksmith | 0.1.12 |
| Nexus | 0.3.24 |
| Observer | 0.1.4 |
| Remnant | 0.2.26 |
| VanillaTweaks | 0.1.30 |
| Villagers | 0.1.38 |

DeadRecall is compatibility history, not an active module to release.

## Migration boundaries

- Updated vanilla item-drop prediction, registry lookup, input constants, client test connection helpers, swing/render extraction, feature registration, structure/trade APIs, inventory-copy APIs and advancement positions/codecs.
- Alchemy uses 26.3 recipe-manager brewing, 48 data recipes and bounded material-property synchronization instead of removed PotionBrewing. Remnant compiles against Trinkets 4.2.0+26.3 and its declared Yumi dependency.
- Migrated affected advancement criteria/backgrounds and both loot tables. Kept canonical item IDs, save data ownership, provider protocols and server-authoritative permissions unchanged.
- Named-runtime Fabric API transformations now create atomic, content-addressed build-local copies, retain producer-task dependencies and cover custom Loom runs. They never rewrite shared cached artifacts or production JARs. Current 26.3 cache originals were restored and independently checked against all 45 expected SHA-1 identities.
- Observer's moving-window prerequisites and native Sign/Advancements adaptation are retained. See [Observer closeout](26.3-closeout.md). `play:false` remains; no claim of complete browser gameplay.

## Review and validation ownership

TotemWorkspace resolve/orchestrate/context preceded edits; impact and test-plan cover all twelve modules and their optional consumers. The serial source-write constraint was observed. Planck implemented Observer slices, Mencius implemented/reviewed Alchemy, and Beauvoir independently reviewed API, mixin, build isolation and data migrations; the parent coordinated and validated. Review claims do not replace runtime results.

Independent checks include all 48 brewing recipe mappings against the old 16 transformations × three containers and all 279 vanilla recipes (no missing/extra mappings or collisions), Observer semantic snapshot/cursor review, native sign color/side reconstruction, loot modifier ordering, and Gradle cache isolation. Recipe-set SHA-256: `d3f02ffab29041e5545c7277054b550dba1e383e84d810c6906233b853a0ba8d`.

Local Gradle builds use the Core wrapper where a module has none. Module-owned `build/reports/tests/`, GameTest logs/screenshots and production/E2E runners are the evidence sources. Successful checks are reused only for unchanged inputs; source and artifact identities must be recorded before treating this as release evidence.

Validation is still in progress; this document does not assert that unlisted or pending client/E2E checks passed.

## Release gate

The initial source-update request did not authorize publication. The user subsequently explicitly authorized completing Modrinth publication, including the required source submission and validation. Existing exact lockstep pins must now be replaced with real migrated dependency commits, never invented SHAs or moving branches. Old pinned checkouts must fail closed until repinned. Release work must commit/push dependencies, obtain owning CI/runtime evidence, verify immutable artifact metadata and SHA-512, then use the owned publish workflows. Authorization does not establish release success.

Do not update the canonical workspace release snapshot from these uncommitted local changes. Historical backfill workflows and previous release notes retain their original versions.
