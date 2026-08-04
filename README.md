# WorldManager

世界管理插件。本目录就是独立 Gradle 工程，源码位于 `src/`，并通过 composite build 依赖同级 `Lib`。

## 目录约定

- `plugins/WorldManager/worlds/<世界>` 是持久保存、用户可见的世界快照目录，也是用户放置地图的唯一位置。
- `plugins/WorldManager/.runtime/<世界>` 是普通世界实际加载的临时运行副本，用户不应直接修改。
- `server.properties` 中 `level-name` 指定的初始世界是例外：它直接加载服务器根目录下的同名目录。
- `initial-world-name` 必须与 `server.properties` 的 `level-name` 完全一致；它的持久快照仍放在 `worlds/<名称>`。

## 世界快照

WorldManager 将 `worlds/<世界>` 作为持久快照，并在加载前复制到内部运行目录：

- `auto-reset-worlds: true` 时，普通世界每次加载都会先清空 runtime 副本，再从持久快照复制地图；初始世界则在 Paper 加载前覆盖服务器根目录。
- `auto-reset-worlds: false` 时，普通世界只有 runtime 副本不存在或损坏时才从持久快照复制，初始根世界也不会自动覆盖。
- 执行 `/wm save <世界>` 后，当前世界目录中的地图成为新的持久快照，并原子覆盖 `worlds/<世界>`。
- 快照只包含 `level.dat`、可用的 `uid.dat` 和三个维度的 `region/*.mca`；玩家数据、背包、实体、统计、进度、锁文件和其他运行时文件不会写入持久快照。
- `clean-world-resources: true` 会先覆盖持久快照，再清理一次快照目录；不会清理正在运行的世界目录。`/wm clean` 仍只处理已卸载世界的持久快照，加载时不再重复清理。
- `auto-reset-worlds: true` 时，WorldManager 会在 Paper 创建初始世界前，将 `worlds/<initial-world-name>` 覆盖到服务器根目录的初始世界；普通世界则复制到 `.runtime/<世界>` 后再加载。

`.runtime` 是普通世界的内部目录，保存时的临时替换文件也只会短暂存在于其中并在完成后清理。用户不需要也不应该把世界放入 `.runtime`。Paper 的 `server.properties` 只应填写 `initial-world-name` 对应的根世界名，不要填写 `plugins/WorldManager/worlds/...` 或 `.runtime/...` 路径。

## 命令

```text
/worldmanager save <世界>
```
