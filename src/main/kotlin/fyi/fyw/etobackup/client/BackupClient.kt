package fyi.fyw.etobackup.client

import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.eventhandler.EventPriority
import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.gameevent.TickEvent
import cpw.mods.fml.common.network.FMLNetworkEvent
import fyi.fyw.etobackup.BackupConfig
import fyi.fyw.etobackup.BackupNet
import fyi.fyw.etobackup.LOG
import fyi.fyw.etobackup.Manifest
import fyi.fyw.etobackup.api.BackupText
import fyi.fyw.etobackup.api.Kind
import fyi.fyw.etobackup.api.LastRollback
import fyi.fyw.etobackup.api.Pending
import fyi.fyw.etobackup.api.Picture
import fyi.fyw.etobackup.api.Snapshot
import fyi.fyw.etobackup.api.State
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.shader.Framebuffer
import net.minecraft.event.ClickEvent
import net.minecraft.util.ChatComponentTranslation
import net.minecraft.util.EnumChatFormatting
import net.minecraft.util.IChatComponent
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.common.MinecraftForge
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GLContext

/**
 * The client's side of ETOBackup, behind [fyi.fyw.etobackup.api.BackupView]: it says hello to the server, keeps what
 * the server last said (its backup list and chat lines, these in this client's language and time zone), sends the
 * buttons' requests, and keeps a picture of the screen for every snapshot the server starts
 * (`etobackup/shots/<repository id>/<snapshot>.jpg` in the game directory, never sent anywhere).
 */
object BackupClient {
    const val ACTION_NOW = BackupNet.ACTION_NOW
    const val ACTION_ROLLBACK = BackupNet.ACTION_ROLLBACK
    const val ACTION_UNDO = BackupNet.ACTION_UNDO
    const val ACTION_CANCEL = BackupNet.ACTION_CANCEL

    private val REPOSITORY = Regex("[0-9a-f-]{36}")
    private const val JPEG_QUALITY = 0.85f

    /** A picture whose snapshot the server does not list is deleted once it is this old; the snapshot may still be written. */
    private const val UNLISTED_MILLIS = 3_600_000L

    /** Pictures of servers not visited for this long are deleted. */
    private const val OTHER_SERVER_DAYS = 35L

    private val io = Executors.newSingleThreadExecutor {
        Thread(it, "ETOBackup Pictures").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /** The snapshot the next frame is kept for. */
    @Volatile
    private var wanted: BackupNet.SnapshotStarted? = null
    private var scratch: Framebuffer? = null
    private var pixels: ByteBuffer? = null
    private var ticks = 0

    /** Hello was sent on this connection. */
    @Volatile
    private var greeted = false

    /** The server answered the hello and takes backups. Client thread. */
    var available = false
        private set

    /** The repository the server named, empty before it did. Client thread. */
    private var repository = ""

    /** The list the server sent last and when, or null before the first one. Client thread. */
    var state: State? = null
        private set
    var stateTime = 0L
        private set

    /** The server's last chat line, as plain text. Client thread. */
    var notice = ""
        private set

    /** Until when the countdown the server announced last runs, in milliseconds; 0 without one. */
    private var announcedUntil = 0L

    private val listeners = ArrayList<Runnable>()

    fun register() {
        FMLCommonHandler.instance().bus().register(this)
        MinecraftForge.EVENT_BUS.register(this)
    }

    fun rollbackAnnounced(): Boolean = System.currentTimeMillis() < announcedUntil

    fun listen(listener: Runnable): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun changed() {
        for (listener in listeners.toList()) {
            try {
                listener.run()
            } catch (e: RuntimeException) {
                LOG.error("A backup view failed to update", e)
            }
        }
    }

    fun requestList() {
        if (available) BackupNet.channel.sendToServer(BackupNet.ListRequest())
    }

    fun act(action: Int, name: String = "") {
        if (available) BackupNet.channel.sendToServer(BackupNet.Action(action, name))
    }

    fun snapshotTime(name: String): Instant? = Manifest.timeOf(name)

    private fun onClientThread(task: () -> Unit) {
        Minecraft.getMinecraft().func_152344_a {
            try {
                task()
            } catch (e: RuntimeException) {
                LOG.error("Could not handle a message of the server", e)
            }
        }
    }

    fun onHello(message: BackupNet.HelloReply) = onClientThread {
        available = message.running
        if (REPOSITORY.matches(message.repository)) repository = message.repository
        changed()
        if (listeners.isNotEmpty()) requestList()
    }

    fun onNotice(message: BackupNet.Notice) = onClientThread {
        val args = Array<Any>(message.args.size) { BackupText.snapshot(message.args[it]) ?: message.args[it] }
        val line: IChatComponent = ChatComponentTranslation(message.key, *args)
        notice = line.unformattedText
        val kind = message.key.substringAfterLast('.')
        announcedUntil = when {
            kind.startsWith("countdown") -> System.currentTimeMillis() + (message.args.getOrNull(2)?.toLongOrNull() ?: 0L) * 1000
            kind == "cancelled" || kind == "aborted" -> 0L
            else -> announcedUntil
        }
        if (message.command.isNotEmpty()) {
            if (message.clickKey.isEmpty()) {
                line.chatStyle.setChatClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, message.command))
            } else {
                val link = ChatComponentTranslation(message.clickKey)
                link.chatStyle.setColor(EnumChatFormatting.AQUA)
                    .setChatClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, message.command))
                line.appendText(" ").appendSibling(link)
            }
        }
        Minecraft.getMinecraft().thePlayer?.addChatMessage(line)
        // What the line reports changed the list.
        if (listeners.isNotEmpty()) requestList()
        changed()
    }

    fun onList(message: BackupNet.ListReply) = onClientThread {
        val now = System.currentTimeMillis()
        available = message.available
        if (!message.available) {
            state = null
        } else {
            if (REPOSITORY.matches(message.repository)) repository = message.repository
            state = State(
                message.repository,
                message.singlePlayer,
                message.manage,
                message.pendingTarget.takeIf { it.isNotEmpty() }?.let {
                    Pending(it, message.pendingBy, message.pendingSeconds, now + message.pendingSeconds * 1000L)
                },
                message.last?.let { LastRollback(it.target, it.pre, it.by, Instant.ofEpochMilli(it.time), it.ok, it.message) },
                message.rows.mapNotNull { row ->
                    val time = Manifest.timeOf(row.name) ?: return@mapNotNull null
                    Snapshot(row.name, time, kindOf(row.kind), row.note, row.files, row.bytes, row.storedBytes)
                },
            )
            if (REPOSITORY.matches(message.repository)) {
                val names = message.rows.mapTo(HashSet()) { it.name }
                io.execute { prune(message.repository, names) }
            }
        }
        stateTime = now
        changed()
    }

    private fun kindOf(kind: String): Kind = when (kind) {
        Manifest.MANUAL -> Kind.MANUAL
        Manifest.PRE_ROLLBACK -> Kind.PRE_ROLLBACK
        else -> Kind.AUTO
    }

    fun onSnapshotStarted(message: BackupNet.SnapshotStarted) {
        if (!BackupConfig.screenshots) return
        if (!REPOSITORY.matches(message.repository) || Manifest.timeOf(message.name) == null) return
        wanted = message
    }

    @SubscribeEvent
    fun onDisconnected(event: FMLNetworkEvent.ClientDisconnectionFromServerEvent) {
        wanted = null
        greeted = false
        onClientThread {
            available = false
            repository = ""
            state = null
            stateTime = 0L
            announcedUntil = 0L
            notice = ""
            changed()
        }
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getMinecraft()
        if (!greeted && mc.thePlayer != null && mc.netHandler != null) {
            greeted = true
            BackupNet.channel.sendToServer(BackupNet.Hello())
        }
        if (++ticks % 20 == 0) changed()
    }

    /** The world and the HUD are drawn and an open screen is not yet: that is the picture. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onOverlayDrawn(event: RenderGameOverlayEvent.Post) {
        if (event.type == RenderGameOverlayEvent.ElementType.ALL) shoot()
    }

    /** With the HUD hidden (F1) no overlay is drawn; the finished frame is taken instead. */
    @SubscribeEvent
    fun onFrameDrawn(event: TickEvent.RenderTickEvent) {
        if (event.phase == TickEvent.Phase.END) shoot()
    }

    private fun shoot() {
        val snapshot = wanted ?: return
        wanted = null
        val mc = Minecraft.getMinecraft()
        if (mc.theWorld == null || mc.displayWidth <= 0 || mc.displayHeight <= 0) return
        try {
            val width = minOf(BackupConfig.screenshotWidth, mc.displayWidth)
            val height = maxOf(1, mc.displayHeight * width / mc.displayWidth)
            val file = shotFile(snapshot.repository, snapshot.name)
            if (width < mc.displayWidth && OpenGlHelper.isFramebufferEnabled() && GLContext.getCapabilities().OpenGL30) {
                // Shrunk on the graphics card, so that only the small picture is read back.
                val main = mc.framebuffer
                val small = scratch?.takeIf { it.framebufferWidth == width && it.framebufferHeight == height }
                    ?: Framebuffer(width, height, false).also {
                        scratch?.deleteFramebuffer()
                        scratch = it
                    }
                OpenGlHelper.func_153171_g(GL30.GL_READ_FRAMEBUFFER, main.framebufferObject)
                OpenGlHelper.func_153171_g(GL30.GL_DRAW_FRAMEBUFFER, small.framebufferObject)
                GL30.glBlitFramebuffer(
                    0, 0, main.framebufferWidth, main.framebufferHeight, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR,
                )
                OpenGlHelper.func_153171_g(GL30.GL_READ_FRAMEBUFFER, small.framebufferObject)
                val rgb = read(width, height)
                main.bindFramebuffer(true)
                io.execute { save(file, rgb, width, height, width) }
            } else {
                val rgb = read(mc.displayWidth, mc.displayHeight)
                val fullWidth = mc.displayWidth
                val fullHeight = mc.displayHeight
                io.execute { save(file, rgb, fullWidth, fullHeight, width) }
            }
        } catch (e: RuntimeException) {
            LOG.warn("Could not take the picture for snapshot {}: {}", snapshot.name, e.toString())
        }
    }

    /** The bound read framebuffer as RGB bytes, bottom row first. */
    private fun read(width: Int, height: Int): ByteArray {
        val size = width * height * 3
        val buffer = pixels?.takeIf { it.capacity() >= size } ?: BufferUtils.createByteBuffer(size).also { pixels = it }
        buffer.clear()
        buffer.limit(size)
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buffer)
        return ByteArray(size).also { buffer.get(it) }
    }

    private fun save(file: File, rgb: ByteArray, width: Int, height: Int, targetWidth: Int) {
        try {
            var image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val row = IntArray(width)
            for (y in 0 until height) {
                var at = (height - 1 - y) * width * 3
                for (x in 0 until width) {
                    row[x] = (rgb[at].toInt() and 255 shl 16) or (rgb[at + 1].toInt() and 255 shl 8) or (rgb[at + 2].toInt() and 255)
                    at += 3
                }
                image.setRGB(0, y, width, 1, row, 0, width)
            }
            if (targetWidth < width) {
                val scaled = BufferedImage(targetWidth, maxOf(1, height * targetWidth / width), BufferedImage.TYPE_INT_RGB)
                val graphics = scaled.createGraphics()
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.drawImage(image, 0, 0, scaled.width, scaled.height, null)
                graphics.dispose()
                image = scaled
            }
            file.parentFile.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            val writer = ImageIO.getImageWritersByFormatName("jpg").next()
            try {
                ImageIO.createImageOutputStream(temporary).use { out ->
                    writer.output = out
                    val param = writer.defaultWriteParam
                    param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    param.compressionQuality = JPEG_QUALITY
                    writer.write(null, IIOImage(image, null, null), param)
                }
            } finally {
                writer.dispose()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            LOG.warn("Could not save {}: {}", file, e.toString())
        }
    }

    private fun shotsDir(): File = File(Minecraft.getMinecraft().mcDataDir, "etobackup/shots")

    private fun shotFile(repository: String, name: String): File = File(File(shotsDir(), repository), "$name.jpg")

    /**
     * Decodes this client's picture of the snapshot [name] of the current repository off the client thread, shrunk to
     * about [maxWidth] pixels wide; [then] gets it on the client thread, or null when there is none.
     */
    fun loadPicture(name: String, maxWidth: Int, then: (Picture?) -> Unit) {
        val repository = repository
        if (!REPOSITORY.matches(repository) || Manifest.timeOf(name) == null) return then(null)
        val file = shotFile(repository, name)
        io.execute {
            val picture = try {
                if (file.isFile) decode(file, maxOf(1, maxWidth)) else null
            } catch (e: IOException) {
                LOG.warn("Could not read {}: {}", file, e.toString())
                null
            }
            onClientThread { then(picture) }
        }
    }

    private fun decode(file: File, maxWidth: Int): Picture? {
        ImageIO.createImageInputStream(file).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val param = reader.defaultReadParam
                val step = maxOf(1, reader.getWidth(0) / maxWidth)
                param.setSourceSubsampling(step, step, 0, 0)
                val image = reader.read(0, param)
                val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                val rgba = ByteArray(argb.size * 4)
                for (i in argb.indices) {
                    rgba[4 * i] = (argb[i] shr 16).toByte()
                    rgba[4 * i + 1] = (argb[i] shr 8).toByte()
                    rgba[4 * i + 2] = argb[i].toByte()
                    rgba[4 * i + 3] = -1
                }
                return Picture(image.width, image.height, rgba)
            } finally {
                reader.dispose()
            }
        }
    }

    /** Drops the pictures of snapshots the server no longer has, and those of servers not visited for a long time. */
    private fun prune(repository: String, listed: Set<String>) {
        val now = System.currentTimeMillis()
        for (dir in shotsDir().listFiles().orEmpty()) {
            if (!dir.isDirectory) continue
            for (file in dir.listFiles().orEmpty()) {
                val age = now - file.lastModified()
                val stale = if (dir.name == repository) age > UNLISTED_MILLIS && file.name.removeSuffix(".jpg") !in listed
                else age > Duration.ofDays(OTHER_SERVER_DAYS).toMillis()
                if (stale) file.delete()
            }
            if (dir.name != repository) dir.delete()
        }
    }
}
