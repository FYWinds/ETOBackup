package fyi.fyw.etobackup.client

import cpw.mods.fml.client.event.ConfigChangedEvent
import cpw.mods.fml.client.registry.ClientRegistry
import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.gameevent.InputEvent
import cpw.mods.fml.common.gameevent.TickEvent
import fyi.fyw.etobackup.BackupConfig
import fyi.fyw.etobackup.BackupService
import fyi.fyw.etobackup.ETOBackup
import fyi.fyw.etobackup.api.BackupView
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiIngameMenu
import net.minecraft.client.resources.I18n
import net.minecraft.client.settings.KeyBinding
import net.minecraftforge.client.event.GuiScreenEvent
import net.minecraftforge.common.MinecraftForge
import org.lwjgl.input.Keyboard

/** The client's entry points: the key binding, the pause menu's button, the config screen's changes and single-player rollbacks. */
object ClientSetup {
    /** Unbound at first; the player picks a key in the controls. */
    private val openKey = KeyBinding("key.etobackup.open", Keyboard.KEY_NONE, "key.categories.etobackup")

    /** An id the pause menu's own buttons do not use. */
    private const val BUTTON_ID = 0x45_54_4F

    fun register() {
        BackupClient.register()
        ClientRegistry.registerKeyBinding(openKey)
        FMLCommonHandler.instance().bus().register(this)
        MinecraftForge.EVENT_BUS.register(this)
        BackupService.closeForRollback = SinglePlayerRollback::request
    }

    @SubscribeEvent
    fun onKey(event: InputEvent.KeyInputEvent) {
        val mc = Minecraft.getMinecraft()
        if (openKey.isPressed && mc.currentScreen == null && mc.theWorld != null) BackupView.open(null)
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.START) SinglePlayerRollback.tick()
    }

    @SubscribeEvent
    fun onConfigChanged(event: ConfigChangedEvent.OnConfigChangedEvent) {
        if (event.modID == ETOBackup.MOD_ID) BackupConfig.saveChanges()
    }

    /** Below the pause menu's lowest button when there is room, else in the top left corner. */
    @SubscribeEvent
    fun onGuiInit(event: GuiScreenEvent.InitGuiEvent.Post) {
        val gui = event.gui as? GuiIngameMenu ?: return
        if (!BackupConfig.pauseMenuButton || !BackupView.available) return
        val buttons = event.buttonList.filterIsInstance<GuiButton>()
        val lowest = buttons.maxOfOrNull { it.yPosition } ?: (gui.height / 4)
        val label = I18n.format("etobackup.screen.button")
        val button = if (lowest + 24 + 20 <= gui.height - 4) {
            GuiButton(BUTTON_ID, gui.width / 2 - 100, lowest + 24, 200, 20, label)
        } else {
            GuiButton(BUTTON_ID, 4, 4, 90, 20, label)
        }
        event.buttonList.add(button)
    }

    @SubscribeEvent
    fun onGuiAction(event: GuiScreenEvent.ActionPerformedEvent.Pre) {
        if (event.gui is GuiIngameMenu && event.button.id == BUTTON_ID) BackupView.open(event.gui)
    }
}
