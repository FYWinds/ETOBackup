# ETOBackup

Incremental world backups and one-click rollback for Minecraft 1.7.10 servers and single-player worlds, made for
[GT New Horizons](https://github.com/GTNewHorizons/GT-New-Horizons-Modpack).

一个 GTNH（Minecraft 1.7.10）的增量备份模组：服务器和单人世界都能定时做快照，只存变化的部分，一键回档，回档也能撤销。
[中文说明](#中文说明)见下。

## What it does

- Takes a snapshot of the world on a timer (every 15 minutes while players are online), on `/etobackup now`, and
  before every rollback. Each snapshot is a complete copy of the world, but only what changed since the last one takes
  room, and the server keeps running smoothly while it is taken.
- Puts the world back to any snapshot: `/etobackup rollback <name>` or the backup screen announces it with a
  countdown that can be cancelled, keeps the world as it is now as a snapshot, and swaps the world. `/etobackup undo`
  goes back to the world before the last rollback.
- Thins out old snapshots on its own (all of the last hours, then one per hour, day, week and month).
- Players with ETOBackup on their client get a backup screen (snapshots by day with a picture of their screen at that
  moment, details, the buttons) and chat lines in their language. Players without it use the commands and get English.
- Other mods can show the backups through the API in `fyi.fyw.etobackup.api`.

### How incremental snapshots work

A snapshot is taken right after one of the game's autosaves: the server thread only starts a short hold, and
background threads wait for the saved chunks to reach the disk, then read the files. A file whose size and time did
not change since the previous snapshot is taken over without being read; any other file is cut into 64 KiB slices,
each named by its SHA-256, and only the slices the repository does not have yet are compressed with zstd and stored.
The snapshot itself is a small list of files and their slices, so every snapshot restores the whole world, while a
snapshot of a quiet server adds only a few MB.

### How retention works

After each snapshot the older ones are thinned out: every snapshot of the last `keepAllHours` hours stays, and of the
older ones the newest of each hour, day, week and month, for as many of them as `keepHourly`, `keepDaily`,
`keepWeekly` and `keepMonthly` say; `maxAgeDays` drops anything older. The snapshot taken just before a rollback stays
for `keepPreRollbackDays` days whatever the rules say, so that the rollback can be undone. Slices no remaining snapshot
uses are deleted (at most once an hour).

## Requirements

- Minecraft 1.7.10 with Forge, as in GT New Horizons (developed on GTNH 2.9.0)
- Java 17 or newer
- [Forgelin](https://github.com/GTNewHorizons/Forgelin) and UniMixins (both in GTNH already)

[Hodgepodge](https://github.com/GTNewHorizons/Hodgepodge)'s threaded world data saving is supported: a snapshot waits
for what it has queued.

## Install on a server

1. Download the jar (the one without `-dev`, `-api` or `-sources`) from
   [Releases](https://github.com/FYWinds/ETOBackup/releases) and put it into the server's `mods` folder. Clients do
   not need it; those that have it get the backup screen and pictures.
2. Start the server. `config/etobackup.cfg` is created with timed snapshots on, and the snapshots go to `eto-backups/`
   in the server directory (option `directory`).

A rollback stops the server after its countdown and the snapshot of the world as it is; when the server starts again,
ETOBackup puts the world back before the world is loaded. **ETOBackup cannot start a stopped server.** Run the server
under something that starts it again when it stops: a server panel with automatic restart, a service manager
(for example systemd with `Restart=always`), or a loop in the start script. Without that, start the server by hand
after a rollback.

If a rollback stops halfway (the machine went down during the swap), the server refuses to start with an error that
says so; starting it again finishes the rollback. The same can be done offline with `BackupCli apply-pending` (see
below), which is optional.

## Single player

Put the jar into the client's `mods` folder. Each world gets its own repository, `eto-backups/<world folder>` in the
game directory, outside the world. The owner of the world may always use `/etobackup` and the backup screen, with or
without cheats. A rollback closes the world, puts it back while a screen says so, and opens it again; LAN guests are
disconnected as when the world closes. If the game is killed during a rollback, it is finished the next time the game
starts.

The backup screen opens with a key (unbound at first: Controls, ETOBackup) or the Backups button in the pause menu.
It does not pause the game, so the world keeps running while it is open.

## Commands

| Command | Level | What it does |
| --- | --- | --- |
| `/etobackup now` | 2 | Takes a snapshot now. |
| `/etobackup list` | 2 | Lists the newest snapshots; click one to put its rollback command into the chat box. |
| `/etobackup rollback <name>` | 2 | Announces a rollback to that snapshot, with a countdown (30 s with other players online, else 5 s). |
| `/etobackup cancel` | 2 | Stops the countdown. |
| `/etobackup undo` | 2 | Rolls back to the world as it was just before the last rollback. |
| `/etobackup verify [name\|latest]` | 4 | Reads every slice of a snapshot and checks it against its hash. |
| `/etobackup restore <name\|latest>` | 4 | Writes a copy of a snapshot to `restored/<name>` in the repository, never into the running world. |
| `/etobackup reload` | 4 | Reads `config/etobackup.cfg` again. |

Level 2 and 4 are OP levels; in single player the world's owner may use all of them.

## Configuration

`config/etobackup.cfg`, also in the mod list's config screen. `/etobackup reload` reads it again; `directory` takes
effect when the server or world starts.

| Option | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Take snapshots on a timer. Off: only `/etobackup now` takes one. |
| `intervalMinutes` | `15` | Minutes between snapshots while a player is or was online since the last one. |
| `idleIntervalMinutes` | `0` | Minutes between snapshots while nobody has been online since the last one. 0: none then. |
| `directory` | `eto-backups` | The repository, relative to the server directory or absolute. In single player each world gets a repository below it. |
| `extraPaths` | empty | Files and directories backed up besides the world, relative to the server (game) directory. |
| `exclude` | empty | Paths left out with everything below them. |
| `keepAllHours` | `6` | Every snapshot of this many hours before the newest one is kept. |
| `keepHourly` | `48` | The newest snapshot of each hour is kept, for this many hours. |
| `keepDaily` | `14` | The newest snapshot of each day is kept, for this many days. |
| `keepWeekly` | `8` | The newest snapshot of each week is kept, for this many weeks. |
| `keepMonthly` | `12` | The newest snapshot of each month is kept, for this many months. |
| `maxAgeDays` | `0` | Snapshots more than this many days older than the newest one are dropped. 0: no limit. |
| `keepPreRollbackDays` | `7` | The snapshot taken before a rollback is kept this many days, so the rollback can be undone. |
| `rollbackDelaySeconds` | `30` | The countdown of a rollback while other players are online. |
| `screenshots` | `true` | Client: keep a picture of your screen for every snapshot (`etobackup/shots/` in the game directory, never sent anywhere). |
| `screenshotWidth` | `1280` | Client: the width of those pictures. |
| `pauseMenuButton` | `true` | Client: show the Backups button in the pause menu. |

## Command line

The jar is also a tool for the repository, with the server running or not:

```bash
java -cp mods/etobackup-<version>.jar fyi.fyw.etobackup.BackupCli eto-backups list
java -cp mods/etobackup-<version>.jar fyi.fyw.etobackup.BackupCli eto-backups verify <name|latest>
java -cp mods/etobackup-<version>.jar fyi.fyw.etobackup.BackupCli eto-backups restore <name|latest> <empty directory>
java -cp mods/etobackup-<version>.jar fyi.fyw.etobackup.BackupCli eto-backups apply-pending <server directory>
```

`list`, `verify` and `restore` only read the repository. `apply-pending` carries out a rollback that is waiting, as
the server does when it starts; it must not run while the server does.

Without the mod a snapshot can still be put together by hand: `snapshots/<name>.txt.zst` lists every file with its
slices, and each slice is `objects/<first two characters>/<hash>.zst`; decompress both with `zstd -d`.

## Build

```bash
./gradlew build
```

The build uses the GTNH Gradle convention plugin and needs JDK 25 for the Gradle daemon (Gradle downloads it when it
is missing). The jars are in `build/libs/`. A tag pushed to GitHub is built and published as a release by
`.github/workflows/release-tags.yml`.

## Credits

- [zstd-jni](https://github.com/luben/zstd-jni) by Luben Karavelov, with [Zstandard](https://github.com/facebook/zstd)
  by Meta: bundled unmodified in the jar for compressing the repository (BSD 2-Clause, see `LICENSE.zstd-jni` and
  `NOTICE`).
- [GT New Horizons](https://github.com/GTNewHorizons): the
  [build convention](https://github.com/GTNewHorizons/GTNHGradle) and
  [ExampleMod](https://github.com/GTNewHorizons/ExampleMod1.7.10) this project is set up from, Forgelin, and the
  release workflows.

## License

[GPL-3.0](LICENSE). Copyright (C) 2026 FYWinds. The bundled zstd-jni keeps its own license, see `NOTICE`.

## 中文说明

### 能做什么

- 定时给世界做快照（有玩家在线时每 15 分钟一份），`/etobackup now` 时做，每次回档前也做。每份快照都是完整的世界，但只有和上一份不同的部分占空间，做快照时服务器照常运行。
- 把世界回档到任意一份快照：`/etobackup rollback <名字>` 或备份界面先宣布并倒计时（可以取消），把现在的世界存成一份快照，再换掉世界。`/etobackup undo` 回到上次回档之前的世界。
- 自动清理旧快照（最近几小时全留，再往前每小时、每天、每周、每月各留一份）。
- 客户端也装了 ETOBackup 的玩家有备份界面（按天分组的快照列表、每份快照时自己画面的截图、详情和按钮），聊天消息是自己的语言；没装的玩家用命令，消息是英文。
- 其他模组可以通过 `fyi.fyw.etobackup.api` 里的接口显示备份。

### 增量快照怎么做

快照紧跟在游戏的一次自动存盘之后做：服务器线程只开始一个很短的“保持”，后台线程等这次存盘的区块写到盘上，再去读文件。大小和修改时间都和上一份一样的文件直接沿用，不再读；其余文件按 64 KiB 切片，每片用 SHA-256 命名，仓库里还没有的片才用 zstd 压缩后存下。快照本身只是一份“文件由哪些片组成”的清单，所以每份快照都能恢复出完整的世界，而服务器空闲时一份快照只多占几 MB。

### 保留规则怎么算

每做完一份快照就清理旧的：最新一份之前 `keepAllHours` 小时内的全部保留，更早的按小时、天、周、月各留最新一份，分别留 `keepHourly`、`keepDaily`、`keepWeekly`、`keepMonthly` 个；比最新一份早 `maxAgeDays` 天以上的一律删除。回档前做的那份快照不管规则至少留 `keepPreRollbackDays` 天，这样回档才能撤销。没有任何快照再用到的片会被删掉（最多每小时一次）。

### 需要

- Minecraft 1.7.10 + Forge，和 GT New Horizons 一样（在 GTNH 2.9.0 上开发）
- Java 17 或更新
- Forgelin 和 UniMixins（GTNH 自带）

支持 Hodgepodge 的多线程世界数据保存：快照会等它排队的数据写完。

### 装到服务器

1. 从 [Releases](https://github.com/FYWinds/ETOBackup/releases) 下载 jar（不带 `-dev`、`-api`、`-sources` 的那个），放进服务器的 `mods`。客户端可以不装，装了的有备份界面和截图。
2. 启动服务器。会生成 `config/etobackup.cfg`，定时快照默认开启，快照存在服务端目录下的 `eto-backups/`（选项 `directory`）。

回档时，倒计时结束、存好“回档前”快照后服务器会自己停下；下次启动时，ETOBackup 在加载世界之前把世界换好。**ETOBackup 没法把停下的服务器再启动起来。** 请让服务器跑在会自动重新启动它的环境里：带自动重启的服务器面板、服务管理器（比如 systemd 的 `Restart=always`），或者启动脚本里的循环。没有这些的话，回档后要手动启动服务器。

如果回档在换世界的中途停下（比如机器在那时断电），服务器会报错拒绝启动；再启动一次就会接着做完。也可以离线用 `BackupCli apply-pending` 做同样的事（见下），这一步是可选的。

### 单人游戏

把 jar 放进客户端的 `mods`。每个世界有自己的仓库，在游戏目录下的 `eto-backups/<世界文件夹>`，不在世界目录里。世界的主人不管开没开作弊都能用 `/etobackup` 和备份界面。回档时世界会关闭，显示一个“正在回档”的界面，换好后自动重新打开；局域网里的其他玩家会像世界关闭时一样断开。如果回档中途游戏被强行关掉，下次启动游戏时会先接着做完。

备份界面用按键打开（默认没有绑定：在“控制”里的 ETOBackup 下设置），或者点暂停菜单里的“备份”按钮。它不会暂停游戏，打开时世界照常运行。

### 命令

| 命令 | 权限 | 作用 |
| --- | --- | --- |
| `/etobackup now` | 2 | 立即做一份快照。 |
| `/etobackup list` | 2 | 列出最新的快照；点其中一行会把回档命令填进聊天框。 |
| `/etobackup rollback <名字>` | 2 | 宣布回档到这份快照并倒计时（有其他玩家在线时 30 秒，否则 5 秒）。 |
| `/etobackup cancel` | 2 | 取消倒计时。 |
| `/etobackup undo` | 2 | 回到上次回档之前的世界。 |
| `/etobackup verify [名字\|latest]` | 4 | 读出一份快照的每一片并核对哈希。 |
| `/etobackup restore <名字\|latest>` | 4 | 把一份快照恢复到仓库里的 `restored/<名字>`，不会动正在运行的世界。 |
| `/etobackup reload` | 4 | 重新读取 `config/etobackup.cfg`。 |

2 和 4 是 OP 等级；单人游戏时世界的主人可以用全部命令。

### 配置

`config/etobackup.cfg`，也可以在模组列表的配置界面里改。`/etobackup reload` 重新读取；`directory` 要等服务器或世界下次启动才生效。

| 选项 | 默认 | 含义 |
| --- | --- | --- |
| `enabled` | `true` | 定时做快照。关闭后只有 `/etobackup now` 会做。 |
| `intervalMinutes` | `15` | 上一份之后有玩家在线过时，两份快照之间的分钟数。 |
| `idleIntervalMinutes` | `0` | 上一份之后一直没人在线时的间隔分钟数。0 表示这时不做。 |
| `directory` | `eto-backups` | 仓库位置，相对服务端目录或绝对路径。单人游戏时每个世界在它下面有自己的仓库。 |
| `extraPaths` | 空 | 世界之外一起备份的文件和目录，相对服务端（游戏）目录。 |
| `exclude` | 空 | 不备份的路径（连同下面的所有内容）。 |
| `keepAllHours` | `6` | 最新一份之前这么多小时内的快照全部保留。 |
| `keepHourly` | `48` | 每小时保留最新一份，共这么多个小时。 |
| `keepDaily` | `14` | 每天保留最新一份，共这么多天。 |
| `keepWeekly` | `8` | 每周保留最新一份，共这么多周。 |
| `keepMonthly` | `12` | 每月保留最新一份，共这么多个月。 |
| `maxAgeDays` | `0` | 比最新一份早这么多天以上的快照删除。0 表示不限。 |
| `keepPreRollbackDays` | `7` | 回档前的快照至少保留这么多天，回档才能撤销。 |
| `rollbackDelaySeconds` | `30` | 有其他玩家在线时回档的倒计时秒数。 |
| `screenshots` | `true` | 客户端：每份快照留一张你当时的画面（存在游戏目录的 `etobackup/shots/`，不会发给任何人）。 |
| `screenshotWidth` | `1280` | 客户端：截图宽度。 |
| `pauseMenuButton` | `true` | 客户端：在暂停菜单里显示“备份”按钮。 |

### 命令行

jar 本身也是仓库工具，服务器开着关着都能用：

```bash
java -cp mods/etobackup-<version>.jar fyi.fyw.etobackup.BackupCli eto-backups list
java -cp mods/etobackup-<version>.jar fyi.fyw.etobackup.BackupCli eto-backups verify <名字|latest>
java -cp mods/etobackup-<version>.jar fyi.fyw.etobackup.BackupCli eto-backups restore <名字|latest> <空目录>
java -cp mods/etobackup-<version>.jar fyi.fyw.etobackup.BackupCli eto-backups apply-pending <服务端目录>
```

`list`、`verify`、`restore` 只读仓库。`apply-pending` 执行等着的回档，和服务器启动时做的一样；服务器运行时不能用它。

没有这个模组也能手动拼出快照：`snapshots/<名字>.txt.zst` 列出每个文件由哪些片组成，每片是 `objects/<前两个字符>/<哈希>.zst`，都用 `zstd -d` 解压。

### 构建

```bash
./gradlew build
```

使用 GTNH 的 Gradle 约定插件，Gradle 守护进程需要 JDK 25（没有时 Gradle 会自动下载）。jar 在 `build/libs/`。推送到 GitHub 的标签会由 `.github/workflows/release-tags.yml` 构建并发布。

### 致谢

- [zstd-jni](https://github.com/luben/zstd-jni)（Luben Karavelov，内含 Meta 的 Zstandard）：原样打包在 jar 里，用来压缩仓库（BSD 2-Clause，见 `LICENSE.zstd-jni` 和 `NOTICE`）。
- [GT New Horizons](https://github.com/GTNewHorizons)：本项目基于它的[构建约定](https://github.com/GTNewHorizons/GTNHGradle)和 [ExampleMod](https://github.com/GTNewHorizons/ExampleMod1.7.10) 搭建，还有 Forgelin 和发布用的工作流。

### 许可

[GPL-3.0](LICENSE)。Copyright (C) 2026 FYWinds。打包的 zstd-jni 保持它自己的许可，见 `NOTICE`。
