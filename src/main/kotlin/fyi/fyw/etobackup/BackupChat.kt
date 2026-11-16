package fyi.fyw.etobackup

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import net.minecraft.command.ICommandSender
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.event.ClickEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.util.ChatComponentText
import net.minecraft.util.EnumChatFormatting

/**
 * What ETOBackup tells players. A client with ETOBackup gets the lang key and shows the line in its own language, with
 * snapshot times in its own time zone; any other client, the console and the log get English with the times in UTC.
 */
internal object BackupChat {
    /** See [BackupNet.Notice]. */
    class Click(val key: String, val command: String)

    private val UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    /** English. A snapshot's name stays in after its time: it is what the commands take. */
    fun text(key: String, vararg args: Any): String = Lang.english(
        key, *Array(args.size) { i -> Manifest.timeOf(args[i].toString())?.let { "${UTC.format(it)} (${args[i]})" } ?: args[i] },
    )

    /** Safe off the server thread. A sender that is not a player (the console) reads the log. */
    fun tell(sender: ICommandSender, key: String, vararg args: Any, click: Click? = null) {
        val player = sender as? EntityPlayerMP ?: return LOG.info("{}", text(key, *args))
        if (BackupNet.has(player)) {
            BackupNet.channel.sendTo(
                BackupNet.Notice(key, args.map { it.toString() }, click?.key.orEmpty(), click?.command.orEmpty()), player,
            )
            return
        }
        val line = ChatComponentText(text(key, *args))
        if (click != null) {
            if (click.key.isEmpty()) {
                line.chatStyle.setChatClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, click.command))
            } else {
                val link = ChatComponentText(Lang.english(click.key))
                link.chatStyle.setColor(EnumChatFormatting.AQUA)
                    .setChatClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, click.command))
                line.appendText(" ").appendSibling(link)
            }
        }
        player.addChatMessage(line)
    }

    /** To the log and every player; [click] only goes to the players [clickFor] accepts. Server thread only. */
    fun broadcast(key: String, vararg args: Any, click: Click? = null, clickFor: (EntityPlayerMP) -> Boolean = { true }) {
        LOG.info("{}", text(key, *args))
        val players = MinecraftServer.getServer()?.configurationManager?.playerEntityList ?: return
        for (player in players.filterIsInstance<EntityPlayerMP>()) tell(player, key, *args, click = click.takeIf { clickFor(player) })
    }
}
