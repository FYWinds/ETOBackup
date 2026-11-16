package fyi.fyw.etobackup.client

import cpw.mods.fml.client.FMLClientHandler
import fyi.fyw.etobackup.BackupService
import fyi.fyw.etobackup.BackupStore
import fyi.fyw.etobackup.LOG
import fyi.fyw.etobackup.PendingRollbacks
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiMainMenu
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.GuiSelectWorld
import net.minecraft.client.multiplayer.WorldClient
import net.minecraft.client.resources.I18n

/**
 * A single-player rollback once the world's server has taken the snapshot of the world as it is and written the
 * request: the world is closed as the pause menu's Save and Quit does, the request is carried out off the client
 * thread once the world's server has stopped and written everything ([RollbackScreen] meanwhile), and the world is
 * opened again. A rollback that failed before the swap left the world as it was, which is opened again as well; the
 * chat then says why.
 */
internal object SinglePlayerRollback {
    /** How long the world's server may take to stop, its last snapshot included. */
    private const val STOP_WAIT_SECONDS = 180L

    @Volatile
    private var requested: BackupService.Closing? = null

    /** The world's server thread asks for its world to be closed. */
    fun request(closing: BackupService.Closing) {
        requested = closing
    }

    /** The start of a client tick: closing the world here is what the pause menu does too. */
    fun tick() {
        val closing = requested ?: return
        requested = null
        val mc = Minecraft.getMinecraft()
        if (mc.theWorld != null) {
            mc.theWorld.sendQuittingDisconnectingPacket()
            // Waits until the world's server has stopped.
            mc.loadWorld(null as WorldClient?)
        }
        val screen = RollbackScreen(closing.worldName)
        mc.displayGuiScreen(screen)
        thread(name = "ETOBackup Rollback", isDaemon = true) {
            val failure = try {
                if (!BackupService.awaitStopped(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) throw IOException("the world's server did not stop")
                BackupService.flushWorldData()
                PendingRollbacks.finish(closing.root, BackupStore(closing.repository))
                null
            } catch (e: Exception) {
                LOG.error("The single-player rollback of {} did not finish", closing.folder, e)
                e
            }
            mc.func_152344_a { screen.finished(closing, failure) }
        }
    }
}

/**
 * What the player sees while the world is put back. It opens the world again when that is done, or says what went
 * wrong and goes back to the title screen.
 */
internal class RollbackScreen(private val worldName: String) : GuiScreen() {
    private var closing: BackupService.Closing? = null
    private var failure: Exception? = null
    private var done = false
    private var reopened = false
    private var frames = 0

    fun finished(closing: BackupService.Closing, failure: Exception?) {
        this.closing = closing
        this.failure = failure
        done = true
        initGui()
    }

    override fun initGui() {
        buttonList.clear()
        if (done && failure != null) buttonList.add(GuiButton(0, width / 2 - 100, height / 2 + 40, I18n.format("gui.toTitle")))
    }

    override fun updateScreen() {
        frames++
        val closing = closing ?: return
        if (!done || failure != null || reopened) return
        reopened = true
        // Outside the scheduled-task queue: loading a world waits for its server.
        FMLClientHandler.instance().tryLoadExistingWorld(GuiSelectWorld(GuiMainMenu()), closing.folder, closing.worldName)
    }

    override fun actionPerformed(button: GuiButton) {
        if (button.id == 0) mc.displayGuiScreen(GuiMainMenu())
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        // Escape must not leave the screen while the world is being replaced.
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        drawDefaultBackground()
        val failure = failure
        val lines = when {
            failure is PendingRollbacks.HalfReplaced ->
                listOf(I18n.format("etobackup.rollback.halfway"), failure.cause?.message ?: failure.message.orEmpty())
            failure != null -> listOf(I18n.format("etobackup.rollback.failed"), failure.message ?: failure.javaClass.simpleName)
            done -> listOf(I18n.format("etobackup.rollback.opening", worldName))
            else -> listOf(I18n.format("etobackup.rollback.working", worldName) + ".".repeat(frames / 10 % 4))
        }
        var y = height / 2 - 30
        for (line in lines) {
            for (wrapped in fontRendererObj.listFormattedStringToWidth(line, width - 40)) {
                drawCenteredString(fontRendererObj, wrapped as String, width / 2, y, 0xFFFFFF)
                y += fontRendererObj.FONT_HEIGHT + 2
            }
            y += 4
        }
        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    override fun doesGuiPauseGame(): Boolean = false
}
