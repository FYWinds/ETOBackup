package fyi.fyw.etobackup

import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.gameevent.PlayerEvent
import cpw.mods.fml.common.gameevent.TickEvent
import fyi.fyw.etobackup.api.Usage
import io.netty.util.concurrent.GenericFutureListener
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.command.ICommandSender
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.network.play.server.S40PacketDisconnect
import net.minecraft.server.MinecraftServer
import net.minecraft.util.ChatComponentText
import net.minecraft.util.ChatComponentTranslation
import net.minecraft.util.IChatComponent
import net.minecraft.world.WorldServer
import net.minecraft.world.storage.ThreadedFileIOBase
import net.minecraftforge.common.DimensionManager

/**
 * Takes the snapshots, leaving the server thread as little as possible. A snapshot that is due waits for the game's
 * next autosave, which serializes every changed chunk anyway; in that tick the server thread only starts a hold
 * ([BackupGate]) and returns. Without an autosave in time, or on `/etobackup now`, it saves the world itself first.
 *
 * The reader thread then waits until the chunks that save queued are on disk, closes the gate for chunk writes and
 * reads the files that changed: the state of that one save, without saving being turned off. The slices that differ
 * from the last snapshot go through a bounded queue to the writer thread, which compresses and stores them and then
 * publishes the snapshot.
 *
 * During the hold the game unloads no chunk, and a world save (autosave, `save-all`, another mod's backup, stopping)
 * waits for the reader.
 *
 * It runs for a dedicated server and for the integrated server of a single-player world, which has a repository of
 * its own ([PendingRollbacks.singlePlayerRepository]). A rollback stops a dedicated server, which puts the world back
 * when it is started again; a single-player world is closed, put back by the client and opened again
 * ([closeForRollback]).
 */
object BackupService {
    /** Slices waiting for the writer: 64 MB. */
    private const val QUEUE_SLICES = 1024
    private const val RETRY_MILLIS = 5 * 60_000L
    private const val GARBAGE_INTERVAL_MILLIS = 3_600_000L
    private const val STOP_TIMEOUT_SECONDS = 120L
    private const val GATE_TIMEOUT_MILLIS = 5_000L

    /** The game autosaves every 45 s unless configured otherwise. */
    private const val AUTOSAVE_WAIT_MILLIS = 60_000L

    /** The countdown of a rollback when nobody but the player who asked is online. */
    private const val SOLO_DELAY_SECONDS = 5

    /** Seconds left at which the countdown is said again. */
    private val REMIND_AT = setOf(60, 30, 10, 5, 4, 3, 2, 1)

    /** Ticks between the players being sent away and the server stopping, for them to be told why. */
    private const val STOP_DELAY_TICKS = 20

    /** For this long after a rollback, a player who joins is told how it ended. */
    private const val RESULT_SHOWN_MILLIS = 10 * 60_000L
    private const val MSG = "etobackup.msg"

    /** A single-player world closes for a rollback: [repository] holds the request, `saves/[folder]` is the world. */
    class Closing(val root: Path, val repository: Path, val folder: String, val worldName: String)

    /** Set by the client: closes the single-player world, carries the request out and opens the world again. */
    @Volatile
    var closeForRollback: ((Closing) -> Unit)? = null

    private class Slice(val hash: String, val data: ByteArray)

    /** The end of a capture; [result] is null when it failed and what was written is to be dropped. */
    private class End(val result: Capture.Result?, val summary: String)

    /** A snapshot somebody asked for; [then] gets it, or null when it could not be taken. */
    private class Order(
        val kind: String,
        val note: String,
        val requesters: List<ICommandSender>,
        val then: ((Manifest?) -> Unit)? = null,
    )

    private enum class Phase { COUNTING, SAVING, STOPPING, CLOSING }

    /** A rollback, from its announcement until the server stops (or the single-player world closes) for it. */
    private class Run(val target: String, val by: String, var ticksLeft: Int) {
        var phase = Phase.COUNTING
    }

    private var reader: ExecutorService? = null
    private var worker: ExecutorService? = null
    private var store: BackupStore? = null
    private var root: Path? = null
    private var folder = ""

    /** Counted down once [stop] has finished; a single-player rollback waits for it. */
    @Volatile
    private var stopped = CountDownLatch(0)

    /** The newest snapshot; the next one is the difference to it. */
    @Volatile
    private var baseline: Manifest? = null

    /** When the newest snapshot was taken, or null without one. */
    val lastSnapshot: Instant?
        get() = baseline?.let { Manifest.timeOf(it.name) }

    /** The number of snapshots and the bytes the repository takes on disk, or null while not measured. */
    @Volatile
    var usage: Usage? = null
        private set

    /** A capture, a writer or a command is at work. */
    private val busy = AtomicBoolean()
    private val requests = ConcurrentLinkedQueue<Order>()
    private val serverTasks = ConcurrentLinkedQueue<Runnable>()

    /** A player was online since the last capture. */
    @Volatile
    private var activity = false

    @Volatile
    private var notBefore = 0L
    private var ticks = 0

    /** Since when a snapshot is due and waiting for an autosave, or 0. */
    private var dueSince = 0L
    private var lastGarbageCollection = 0L

    @Volatile
    private var rollback: Run? = null

    @Volatile
    private var lastRollback: Rollback.Outcome? = null

    @Volatile
    private var repository = ""

    @Volatile
    private var singlePlayer = false

    private val hodgepodgeFlush: (() -> Unit)? by lazy {
        // Hodgepodge's threadedWorldDataSaving writes level.dat and the data/*.dat files from its own queue.
        try {
            val saver = Class.forName("com.mitchej123.hodgepodge.util.WorldDataSaver")
            val instance = saver.getField("INSTANCE").get(null)
            val flush = saver.getMethod("flush")
            val call: () -> Unit = {
                try {
                    flush.invoke(instance)
                } catch (e: InvocationTargetException) {
                    throw e.cause ?: e
                }
            }
            call
        } catch (e: ReflectiveOperationException) {
            null
        }
    }

    fun register() {
        FMLCommonHandler.instance().bus().register(this)
    }

    fun start(server: MinecraftServer) {
        val serverDir = server.getFile(".").toPath().toAbsolutePath().normalize()
        singlePlayer = !server.isDedicatedServer
        folder = server.folderName
        val opened = BackupStore(
            if (singlePlayer) PendingRollbacks.singlePlayerRepository(serverDir, BackupConfig.directory, folder)
            else PendingRollbacks.serverRepository(serverDir, BackupConfig.directory),
        )
        root = serverDir
        stopped = CountDownLatch(1)
        try {
            baseline = newestReadable(opened)
            repository = opened.id()
            lastRollback = Rollback.last(opened)
        } catch (e: IOException) {
            LOG.error("Could not open the repository {}; no backups are taken", opened.dir(), e)
            root = null
            stopped.countDown()
            return
        }
        store = opened
        reader = Executors.newSingleThreadExecutor { Thread(it, "ETOBackup Reader").apply { isDaemon = true } }
        val writing = Executors.newSingleThreadExecutor { Thread(it, "ETOBackup Writer").apply { isDaemon = true } }
        worker = writing
        writing.execute {
            // The world the last rollback replaced; this start loaded the new one.
            try {
                Rollback.deleteReplaced(opened)
            } catch (e: IOException) {
                LOG.warn("Could not delete the world the last rollback replaced: {}", e.toString())
            }
            measure(opened)
        }
        lastRollback?.let {
            if (it.ok()) LOG.info("The last rollback, by {}, put the world back to snapshot {}; {} is the world before it", it.by(), it.target(), it.pre())
            else LOG.warn("The last rollback, by {} to snapshot {}, failed and left the world as it was: {}", it.by(), it.target(), it.message())
        }
        LOG.info(
            "Repository {}, newest snapshot {}, timer {}",
            opened.dir(), baseline?.name ?: "none", if (BackupConfig.enabled) "every ${BackupConfig.intervalMinutes} min" else "off",
        )
    }

    /** Lets the reader and the writer finish the snapshot they are at. */
    fun stop() {
        val reading = reader
        val writing = worker
        if (reading != null && writing != null) {
            reading.shutdown()
            writing.shutdown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STOP_TIMEOUT_SECONDS)
            val finished = reading.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
                writing.awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
            if (!finished) LOG.warn("Did not finish within {} s; its snapshot is lost", STOP_TIMEOUT_SECONDS)
        }
        reader = null
        worker = null
        store = null
        root = null
        baseline = null
        usage = null
        rollback = null
        repository = ""
        requests.clear()
        serverTasks.clear()
        busy.set(false)
        stopped.countDown()
    }

    /** Waits until [stop] has finished. */
    fun awaitStopped(timeout: Long, unit: TimeUnit): Boolean = stopped.await(timeout, unit)

    /** Waits until what Hodgepodge's world data saver has queued is written. Any thread but the server's. */
    fun flushWorldData() {
        hodgepodgeFlush?.invoke()
    }

    /** Runs [task] in the next server tick. */
    fun onServerThread(task: () -> Unit) {
        serverTasks.add(Runnable(task))
    }

    /** What the server answers a client's hello. Server thread. */
    fun hello(): BackupNet.HelloReply = BackupNet.HelloReply(store != null, repository, singlePlayer)

    /** OP level 2, or the owner of a single-player world, cheats or not. */
    fun mayManage(sender: ICommandSender): Boolean = sender.canCommandSenderUseCommand(2, BackupCommand.commandName) || isOwner(sender)

    /** OP level 4, or the owner of a single-player world. */
    fun mayAdminister(sender: ICommandSender): Boolean = sender.canCommandSenderUseCommand(4, BackupCommand.commandName) || isOwner(sender)

    private fun isOwner(sender: ICommandSender): Boolean {
        val server = MinecraftServer.getServer() ?: return false
        return !server.isDedicatedServer && sender is EntityPlayerMP && sender.commandSenderName == server.serverOwner
    }

    fun requestNow(sender: ICommandSender) {
        if (store == null) return BackupChat.tell(sender, "$MSG.not_running")
        requests.add(Order(Manifest.MANUAL, sender.commandSenderName, listOf(sender)))
        BackupChat.tell(sender, "$MSG.taking")
    }

    fun list(sender: ICommandSender) = command(sender) { store ->
        val names = store.list()
        for (name in names.takeLast(10)) {
            val summary = store.summary(name)
            BackupChat.tell(
                sender, "$MSG.row.${kindKey(summary.kind())}", name, Manifest.megabytes(summary.storedBytes()),
                click = BackupChat.Click("", "/etobackup rollback $name"),
            )
        }
        BackupChat.tell(sender, "$MSG.total", names.size)
    }

    private fun kindKey(kind: String): String = if (kind == Manifest.MANUAL || kind == Manifest.PRE_ROLLBACK) kind else Manifest.AUTO

    /** The backup list for [player]'s screen. Server thread. */
    fun sendList(player: EntityPlayerMP) {
        val reply = BackupNet.ListReply()
        val store = store
        val worker = worker
        if (store == null || worker == null) return BackupNet.channel.sendTo(reply, player)
        reply.available = true
        reply.repository = repository
        reply.singlePlayer = singlePlayer
        reply.manage = mayManage(player)
        rollback?.let {
            reply.pendingTarget = it.target
            reply.pendingBy = it.by
            reply.pendingSeconds = if (it.phase == Phase.COUNTING) (it.ticksLeft + 19) / 20 else 0
        }
        reply.last = lastRollback?.let { BackupNet.Last(it.target(), it.pre(), it.by(), it.time(), it.ok(), it.message()) }
        worker.execute {
            try {
                reply.rows = store.list().takeLast(BackupNet.MAX_ROWS).mapNotNull { name ->
                    try {
                        val summary = store.summary(name)
                        BackupNet.Row(name, summary.kind(), summary.note(), summary.files(), summary.bytes(), summary.storedBytes())
                    } catch (e: IOException) {
                        null
                    }
                }
            } catch (e: IOException) {
                LOG.warn("Could not list the snapshots: {}", e.toString())
            }
            BackupNet.channel.sendTo(reply, player)
        }
    }

    /** A button of the backup screen. Server thread. */
    fun act(player: EntityPlayerMP, action: Int, name: String) {
        if (!mayManage(player)) return BackupChat.tell(player, "$MSG.no_permission")
        when (action) {
            BackupNet.ACTION_NOW -> requestNow(player)
            BackupNet.ACTION_ROLLBACK -> rollback(player, name)
            BackupNet.ACTION_UNDO -> undo(player)
            BackupNet.ACTION_CANCEL -> cancel(player)
        }
    }

    /**
     * Announces that the world goes back to the snapshot [name]. When the countdown ends the server takes a snapshot
     * of the world as it is and leaves a request in the repository ([Rollback]). A dedicated server then stops and
     * carries the request out when it is started again; a single-player world is closed, put back and opened again.
     */
    fun rollback(sender: ICommandSender, name: String) = command(sender) { store -> prepare(sender, store, name) }

    /** Goes back to the world as it was just before the last rollback. */
    fun undo(sender: ICommandSender) = command(sender) { store ->
        val last = Rollback.last(store)
        if (last == null || !last.ok()) return@command BackupChat.tell(sender, "$MSG.nothing_to_undo")
        prepare(sender, store, last.pre())
    }

    /** Server thread. */
    fun cancel(sender: ICommandSender) {
        if (rollback?.phase != Phase.COUNTING) return BackupChat.tell(sender, "$MSG.nothing_to_cancel")
        rollback = null
        BackupChat.broadcast("$MSG.cancelled", sender.commandSenderName)
    }

    /** The writer thread: nobody is told before the snapshot is known to restore whole. */
    private fun prepare(sender: ICommandSender, store: BackupStore, name: String) {
        if (rollback != null) return BackupChat.tell(sender, "$MSG.already")
        if (Manifest.timeOf(name) == null) return BackupChat.tell(sender, "$MSG.no_snapshot", name)
        val manifest = store.read(store.resolve(name))
        val world = worldName(root ?: return)
        if (manifest.files.keys.none { it.startsWith("$world/") }) return BackupChat.tell(sender, "$MSG.no_world", name)
        val damaged = store.verify(manifest).problems()
        if (damaged.isNotEmpty()) {
            for (problem in damaged.take(10)) LOG.warn("Snapshot {}: {}", name, problem)
            return BackupChat.tell(sender, "$MSG.damaged", name, damaged.size)
        }
        onServerThread { announce(sender, name) }
    }

    private fun announce(sender: ICommandSender, target: String) {
        if (rollback != null) return BackupChat.tell(sender, "$MSG.already")
        val server = MinecraftServer.getServer() ?: return
        val others = server.configurationManager.playerEntityList.any { it !== sender }
        val seconds = if (others) BackupConfig.rollbackDelaySeconds else SOLO_DELAY_SECONDS
        val run = Run(target, sender.commandSenderName, seconds * 20)
        rollback = run
        remind(run)
    }

    private fun remind(run: Run) = BackupChat.broadcast(
        if (singlePlayer) "$MSG.countdown_world" else "$MSG.countdown", run.by, run.target, (run.ticksLeft + 19) / 20,
        click = BackupChat.Click("$MSG.cancel", "/etobackup cancel"),
        clickFor = { mayManage(it) },
    )

    private fun countdown(server: MinecraftServer) {
        val run = rollback ?: return
        if (run.phase == Phase.SAVING || run.phase == Phase.CLOSING || --run.ticksLeft > 0) {
            if (run.phase == Phase.COUNTING && run.ticksLeft % 20 == 0 && run.ticksLeft / 20 in REMIND_AT) remind(run)
            return
        }
        if (run.phase == Phase.STOPPING) return server.initiateShutdown()
        run.phase = Phase.SAVING
        requests.add(
            Order(Manifest.PRE_ROLLBACK, "${run.target} ${run.by}", emptyList()) { made ->
                onServerThread { stopForRollback(run, made) }
            },
        )
    }

    /** The server thread, once the snapshot of the world as it is now is stored ([made]; null: it could not be). */
    private fun stopForRollback(run: Run, made: Manifest?) {
        fun abort(reason: String) {
            rollback = null
            BackupChat.broadcast("$MSG.aborted", reason)
        }
        val store = store
        val root = root
        val server = MinecraftServer.getServer()
        if (made == null || store == null || root == null || server == null) return abort("the snapshot of the world as it is now could not be taken")
        val close = closeForRollback
        if (singlePlayer && close == null) return abort("this world's server has no client to close it")
        try {
            Rollback.request(store, run.target, made.name, run.by, worldName(root))
        } catch (e: IOException) {
            return abort(e.message ?: e.javaClass.simpleName)
        }
        if (singlePlayer && close != null) {
            LOG.info("Closing the world; it is put back to snapshot {} (asked by {}); {} is the world as it is now", run.target, run.by, made.name)
            run.phase = Phase.CLOSING
            close(Closing(root, store.dir(), folder, server.worldName))
            return
        }
        LOG.info(
            "Stopping the server; it puts the world back to snapshot {} (asked by {}) when it is started again; {} is the world as it is now",
            run.target, run.by, made.name,
        )
        val age = Duration.between(Manifest.timeOf(run.target), Instant.now())
        val (unit, amount) = when {
            age.toHours() < 2 -> "minutes" to age.toMinutes()
            age.toDays() < 2 -> "hours" to age.toHours()
            else -> "days" to age.toDays()
        }
        val key = "etobackup.kick.$unit"
        for (player in server.configurationManager.playerEntityList.filterIsInstance<EntityPlayerMP>()) {
            val reason: IChatComponent =
                if (BackupNet.has(player)) ChatComponentTranslation(key, run.by, amount.toString())
                else ChatComponentText(Lang.english(key, run.by, amount.toString()))
            // What kickPlayerFromServer does, with a reason the client translates.
            val manager = player.playerNetServerHandler.netManager
            manager.scheduleOutboundPacket(
                S40PacketDisconnect(reason),
                GenericFutureListener<io.netty.util.concurrent.Future<Void>> { manager.closeChannel(reason) },
            )
            manager.disableAutoRead()
        }
        run.ticksLeft = STOP_DELAY_TICKS
        run.phase = Phase.STOPPING
    }

    @SubscribeEvent
    fun onLogin(event: PlayerEvent.PlayerLoggedInEvent) {
        val last = lastRollback ?: return
        if (System.currentTimeMillis() - last.time() > RESULT_SHOWN_MILLIS) return
        if (last.ok()) BackupChat.tell(event.player, "$MSG.done", last.by(), last.target())
        else BackupChat.tell(event.player, "$MSG.not_done", last.by(), last.target(), last.message())
    }

    fun verify(sender: ICommandSender, name: String) = command(sender) { store ->
        val resolved = store.resolve(name)
        val result = store.verify(store.read(resolved))
        for (problem in result.problems().take(10)) BackupChat.tell(sender, "$MSG.problem", problem)
        BackupChat.tell(sender, "$MSG.verified", resolved, result.objects(), Manifest.megabytes(result.bytes()), result.problems().size)
    }

    /** Never into the running world: the copy goes to `restored/<name>` in the repository. */
    fun restore(sender: ICommandSender, name: String) = command(sender) { store ->
        val resolved = store.resolve(name)
        val dest = store.dir().resolve("restored").resolve(resolved)
        val summary = store.restore(store.read(resolved), dest)
        BackupChat.tell(sender, "$MSG.restored", resolved, summary.files(), Manifest.megabytes(summary.bytes()), dest.toString())
    }

    private fun command(sender: ICommandSender, body: (BackupStore) -> Unit) {
        val store = store
        val worker = worker
        if (store == null || worker == null) return BackupChat.tell(sender, "$MSG.not_running")
        if (!busy.compareAndSet(false, true)) return BackupChat.tell(sender, "$MSG.busy")
        worker.execute {
            try {
                body(store)
            } catch (e: IOException) {
                BackupChat.tell(sender, "$MSG.failed", e.message ?: e.javaClass.simpleName)
            } finally {
                busy.set(false)
            }
        }
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase == TickEvent.Phase.START) return BackupGate.tickStarted()
        while (true) (serverTasks.poll() ?: break).run()
        val store = store ?: return
        val server = MinecraftServer.getServer() ?: return
        val second = ++ticks % 20 == 0
        if (second && server.currentPlayerCount > 0) activity = true
        val saved = BackupGate.savedThisTick(server.worldServers)
        countdown(server)
        if (requests.isEmpty()) {
            // While a snapshot is being written, whether the next one is due cannot be told yet.
            if (busy.get()) {
                dueSince = 0L
                return
            }
            val now = System.currentTimeMillis()
            if (dueSince == 0L) {
                if (!second || !due(now)) return
                dueSince = now
            }
            if (!saved && now - dueSince < AUTOSAVE_WAIT_MILLIS) return
        }
        if (!busy.compareAndSet(false, true)) return
        dueSince = 0L
        val orders = generateSequence { requests.poll() }.toList()
        val order = when (orders.size) {
            0 -> Order(Manifest.AUTO, "", emptyList())
            1 -> orders[0]
            // Asked for at the same moment: one snapshot serves them all, as the kind that is kept longest.
            else -> (orders.firstOrNull { it.kind == Manifest.PRE_ROLLBACK } ?: orders[0]).let { first ->
                Order(first.kind, first.note, orders.flatMap { it.requesters }) { made -> for (each in orders) each.then?.invoke(made) }
            }
        }
        capture(server, store, order, saved)
    }

    private fun due(now: Long): Boolean {
        if (!BackupConfig.enabled || now < notBefore || rollback != null) return false
        val last = baseline ?: return true
        val minutes = if (activity) BackupConfig.intervalMinutes else BackupConfig.idleIntervalMinutes
        return minutes > 0 && now - Manifest.timeOf(last.name).toEpochMilli() >= minutes * 60_000L
    }

    /**
     * The server thread's part: save unless the game just did ([saved]), start the hold, hand over to the reader.
     * A world with saving turned off ignores the save: the whole server after `save-off` or during another mod's
     * backup, where the files on disk are then one consistent state already, or a single dimension that never saves.
     */
    private fun capture(server: MinecraftServer, store: BackupStore, order: Order, saved: Boolean) {
        val started = System.nanoTime()
        val queue = ArrayBlockingQueue<Any>(QUEUE_SLICES)
        var writer: Future<*>? = null
        var hold = 0L
        // What players do from here on belongs to the next snapshot.
        activity = server.currentPlayerCount > 0
        try {
            val root = root!!
            val paths = paths(root)
            val excluded = excluded(root, store)
            val previous = baseline
            val name = nextName()
            val worlds = server.worldServers.filterNotNull()
            server.configurationManager.saveAllPlayerData()
            if (!saved) for (world in worlds) world.saveAllChunks(true, null)
            val saving = worlds.firstOrNull()?.levelSaving == false
            val held = BackupGate.hold()
            hold = held
            val paused = System.nanoTime() - started
            val running = worker!!.submit { write(store, queue, order) }
            // Their picture of this moment goes with the snapshot.
            for (player in server.configurationManager.playerEntityList.filterIsInstance<EntityPlayerMP>()) {
                if (BackupNet.has(player)) BackupNet.channel.sendTo(BackupNet.SnapshotStarted(repository, name), player)
            }
            writer = running
            reader!!.execute {
                try {
                    var closed = 0L
                    var flushed = 0L
                    val waited: Long
                    val result = try {
                        flush(worlds)
                        closed = BackupGate.close(GATE_TIMEOUT_MILLIS)
                        flushed = System.nanoTime()
                        Capture.run(root, paths, excluded, previous, name) { hash, data ->
                            hand(queue, running, Slice(hash, data))
                        }
                    } finally {
                        waited = BackupGate.release(held, closed)
                    }
                    val summary = "server paused ${(paused + waited) / 1_000_000} ms " +
                        (if (saved) "(the game had just saved)" else "(saving)") +
                        (if (waited > 0) ", ${waited / 1_000_000} ms of it a save waiting for the snapshot" else "") +
                        "; flushed in ${(flushed - started - paused) / 1_000_000} ms; " +
                        "read ${result.filesRead()} files, ${Manifest.megabytes(result.bytesRead())} " +
                        "in ${(System.nanoTime() - flushed) / 1_000_000} ms" +
                        if (saving) "" else "; saving is off, took what is on disk"
                    warn(result)
                    result.manifest().kind = order.kind
                    result.manifest().note = order.note
                    hand(queue, running, End(result, summary))
                } catch (e: Throwable) {
                    giveUp(queue, running, order, e)
                }
            }
            hold = 0L
        } catch (e: Exception) {
            if (hold != 0L) BackupGate.release(hold, 0)
            giveUp(queue, writer, order, e)
        }
    }

    /**
     * The reader thread waits for what the save queued: the chunks (the IO thread writes them; the second pass is for
     * a loader the IO thread dropped just as it got more work) and, through the same thread, Hodgepodge's world data.
     */
    private fun flush(worlds: List<WorldServer>) {
        ThreadedFileIOBase.threadedIOInstance.waitForFinish()
        for (world in worlds) world.saveChunkData()
        ThreadedFileIOBase.threadedIOInstance.waitForFinish()
        flushWorldData()
    }

    private fun warn(result: Capture.Result) {
        if (result.unstable().isNotEmpty()) {
            LOG.warn("{} files kept changing while read: {}", result.unstable().size, result.unstable().take(5))
        }
        if (result.drifted().isNotEmpty()) {
            LOG.warn(
                "{} files had changed without their size or time changing; earlier snapshots hold an older copy: {}",
                result.drifted().size, result.drifted().take(5),
            )
        }
        val held = BackupGate.heldWrites()
        if (held > 0) LOG.warn("{} chunk writes were queued during the snapshot and had to wait for it", held)
    }

    /** Has the writer drop what it got, and reports [e] unless the writer stopped and reported its own failure. */
    private fun giveUp(queue: BlockingQueue<Any>, writer: Future<*>?, order: Order, e: Throwable) {
        activity = true
        if (writer == null) {
            busy.set(false)
            order.then?.invoke(null)
        } else if (!writer.isDone) {
            queue.clear()
            queue.add(End(null, ""))
        }
        if (writer == null || !writer.isDone) fail(order.requesters, e)
    }

    private fun hand(queue: BlockingQueue<Any>, writer: Future<*>, item: Any) {
        do {
            if (writer.isDone) throw IOException("the backup writer stopped")
        } while (!queue.offer(item, 100, TimeUnit.MILLISECONDS))
    }

    /** The writer thread: stores slices as they come, then publishes the snapshot and tidies the repository. */
    private fun write(store: BackupStore, queue: BlockingQueue<Any>, order: Order) {
        var made: Manifest? = null
        try {
            val started = System.nanoTime()
            while (true) {
                val item = queue.take()
                if (item is Slice) {
                    store.put(item.hash, item.data)
                    continue
                }
                val end = item as End
                val result = end.result ?: return store.abandon()
                store.commit(result.manifest())
                baseline = result.manifest()
                made = result.manifest()
                val summary = result.manifest().summary()
                LOG.info(
                    "Snapshot {} ({}); {}; written in {} ms",
                    summary.describe(), summary.kind(), end.summary, (System.nanoTime() - started) / 1_000_000,
                )
                for (requester in order.requesters) {
                    if (requester !is EntityPlayerMP) continue
                    BackupChat.tell(
                        requester, "$MSG.made", summary.name(), summary.files(), Manifest.megabytes(summary.bytes()),
                        Manifest.megabytes(summary.storedBytes()),
                    )
                }
                tidy(store)
                measure(store)
                return
            }
        } catch (e: Exception) {
            store.abandon()
            activity = true
            fail(order.requesters, e)
        } finally {
            busy.set(false)
            order.then?.invoke(made)
        }
    }

    private fun tidy(store: BackupStore) {
        // A snapshot a rollback is about to restore must not be dropped under it.
        if (rollback != null) return
        try {
            val deleted = store.prune(BackupConfig.retention, ZoneId.systemDefault(), BackupConfig.keepPreRollbackDays)
            val now = System.currentTimeMillis()
            if (deleted.isEmpty() || now - lastGarbageCollection < GARBAGE_INTERVAL_MILLIS) return
            lastGarbageCollection = now
            val (files, bytes) = store.collectGarbage()
            LOG.info("Dropped {} old snapshots, freed {} objects, {}", deleted.size, files, Manifest.megabytes(bytes))
        } catch (e: IOException) {
            LOG.error("The snapshot is stored, but old ones could not be cleaned up", e)
        }
    }

    private fun measure(store: BackupStore) {
        usage = try {
            store.usage().let { Usage(it[0], it[1]) }
        } catch (e: IOException) {
            LOG.warn("Could not measure the repository: {}", e.toString())
            null
        }
    }

    private fun fail(requesters: List<ICommandSender>, e: Throwable) {
        notBefore = System.currentTimeMillis() + RETRY_MILLIS
        LOG.error("Backup failed", e)
        val reason = e.message ?: e.javaClass.simpleName
        serverTasks.add(
            Runnable {
                val manager = MinecraftServer.getServer()?.configurationManager ?: return@Runnable
                for (player in manager.playerEntityList.filterIsInstance<EntityPlayerMP>()) {
                    if (mayManage(player) || player in requesters) BackupChat.tell(player, "$MSG.backup_failed", reason)
                }
            },
        )
    }

    private fun newestReadable(store: BackupStore): Manifest? {
        for (name in store.list().asReversed()) {
            try {
                return store.read(name)
            } catch (e: IOException) {
                LOG.error("Snapshot {} cannot be read, falling back to an older one", name, e)
            }
        }
        return null
    }

    private fun nextName(): String {
        val now = Instant.now()
        val last = baseline?.let { Manifest.timeOf(it.name) } ?: return Manifest.nameOf(now)
        return Manifest.nameOf(if (now.epochSecond > last.epochSecond) now else last.plusSeconds(1))
    }

    /** The world directory relative to [root], with `/` separators as in a manifest. */
    private fun worldName(root: Path): String {
        val world = DimensionManager.getCurrentSaveRootDirectory().toPath().toAbsolutePath().normalize()
        if (!world.startsWith(root) || world == root) throw IOException("the world directory $world is not inside $root")
        return root.relativize(world).toString().replace('\\', '/')
    }

    private fun paths(root: Path): List<String> = listOf(worldName(root)) + BackupConfig.extraPaths.filter { inside(root, it) }

    private fun excluded(root: Path, store: BackupStore): List<String> {
        val own = if (store.dir().startsWith(root)) listOf(root.relativize(store.dir()).toString()) else emptyList()
        return own + BackupConfig.exclude.filter { inside(root, it) }
    }

    private fun inside(root: Path, path: String): Boolean {
        val resolved = try {
            root.resolve(path).normalize()
        } catch (e: InvalidPathException) {
            null
        }
        val ok = resolved != null && resolved != root && resolved.startsWith(root)
        if (!ok) LOG.warn("Ignoring the path {}, it is not inside {}", path, root)
        return ok
    }
}
