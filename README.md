# WorldManager

Paper 1.12.2 plugin for managing world snapshots and runtime copies. Requires
`Lib`.

## Storage

- `plugins/WorldManager/worlds/<world>` — persistent, user-managed snapshots
- `plugins/WorldManager/.runtime/<world>` — runtime copies for non-root worlds
- `<server-root>/<initial-world-name>` — the initial root world loaded by Paper

`initial-world-name` must match `server.properties` `level-name`. When
`auto-reset-worlds` is enabled, the configured snapshot is restored before a
world loads. `/wm save <world>` replaces the persistent snapshot with the
current map files; player and other runtime data are excluded.

## Commands

All commands require `worldmanager.admin`.

```text
/wm list
/wm load <world>
/wm unload <world> [force]
/wm clean <world>
/wm save <world>
/wm teleport <world>
```

## Build

Requires JDK 21.

```powershell
.\gradlew.bat clean build check --warning-mode all
```
