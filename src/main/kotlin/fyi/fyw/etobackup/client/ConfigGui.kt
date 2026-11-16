package fyi.fyw.etobackup.client

import cpw.mods.fml.client.IModGuiFactory
import cpw.mods.fml.client.config.GuiConfig
import cpw.mods.fml.client.config.IConfigElement
import fyi.fyw.etobackup.BackupConfig
import fyi.fyw.etobackup.ETOBackup
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.resources.I18n
import net.minecraftforge.common.config.ConfigElement

/** The Config button of the mod list. */
class ConfigGuiFactory : IModGuiFactory {
    override fun initialize(minecraftInstance: Minecraft?) {}

    override fun mainConfigGuiClass(): Class<out GuiScreen> = ConfigScreen::class.java

    override fun runtimeGuiCategories(): Set<IModGuiFactory.RuntimeOptionCategoryElement>? = null

    override fun getHandlerFor(element: IModGuiFactory.RuntimeOptionCategoryElement?): IModGuiFactory.RuntimeOptionGuiHandler? = null
}

/** `config/etobackup.cfg` in Forge's config screen; [ClientSetup] takes over and saves what it changed. */
class ConfigScreen(parent: GuiScreen?) : GuiConfig(
    parent,
    BackupConfig.categories().map { ConfigElement<Any>(it) as IConfigElement<*> },
    ETOBackup.MOD_ID,
    false,
    false,
    I18n.format("etobackup.config.title"),
)
