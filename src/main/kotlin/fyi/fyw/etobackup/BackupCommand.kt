package fyi.fyw.etobackup

import net.minecraft.command.CommandBase
import net.minecraft.command.CommandException
import net.minecraft.command.ICommandSender
import net.minecraft.command.WrongUsageException

/**
 * `/etobackup now|list|rollback <name>|undo|cancel` for level 2, `verify [name]|restore <name>|reload` for level 4; the
 * owner of a single-player world may use all of them, cheats or not. `now` takes a snapshot at once, whatever the
 * timer says; `rollback` puts the world back to a snapshot after a countdown that `cancel` stops, and `undo` goes back
 * to the world before the last rollback; `reload` reads the config file again. The work runs off the server thread;
 * results go to the log and to the player who asked.
 */
object BackupCommand : CommandBase() {
    private val ACTIONS = arrayOf("now", "list", "rollback", "undo", "cancel", "verify", "restore", "reload")

    override fun getCommandName(): String = "etobackup"

    override fun getCommandUsage(sender: ICommandSender?): String =
        "/etobackup <now|list|rollback <name>|undo|cancel|verify [name|latest]|restore <name|latest>|reload>"

    override fun getRequiredPermissionLevel(): Int = 2

    override fun canCommandSenderUseCommand(sender: ICommandSender): Boolean = BackupService.mayManage(sender)

    override fun addTabCompletionOptions(sender: ICommandSender?, args: Array<String>): List<String>? =
        if (args.size == 1) getListOfStringsMatchingLastWord(args, *ACTIONS) else null

    override fun processCommand(sender: ICommandSender, args: Array<String>) {
        fun name(): String = args.getOrNull(1) ?: throw WrongUsageException(getCommandUsage(sender))
        when (args.firstOrNull()) {
            "now" -> BackupService.requestNow(sender)
            "list" -> BackupService.list(sender)
            "rollback" -> BackupService.rollback(sender, name())
            "undo" -> BackupService.undo(sender)
            "cancel" -> BackupService.cancel(sender)
            "verify" -> admin(sender) { BackupService.verify(sender, args.getOrNull(1) ?: "latest") }
            "restore" -> admin(sender) { BackupService.restore(sender, name()) }
            "reload" -> admin(sender) { reload(sender) }
            else -> throw WrongUsageException(getCommandUsage(sender))
        }
    }

    private fun reload(sender: ICommandSender) {
        val directory = BackupConfig.directory
        BackupConfig.reload()
        BackupChat.tell(sender, if (BackupConfig.directory == directory) "etobackup.msg.reloaded" else "etobackup.msg.reloaded_directory")
    }

    /** These read the whole repository, write a copy of the world next to it or change the settings. */
    private fun admin(sender: ICommandSender, action: () -> Unit) {
        if (!BackupService.mayAdminister(sender)) throw CommandException("commands.generic.permission")
        action()
    }
}
