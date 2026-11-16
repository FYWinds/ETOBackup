package fyi.fyw.etobackup

import cpw.mods.fml.common.network.ByteBufUtils
import cpw.mods.fml.common.network.NetworkRegistry
import cpw.mods.fml.common.network.simpleimpl.IMessage
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler
import cpw.mods.fml.common.network.simpleimpl.MessageContext
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper
import cpw.mods.fml.relauncher.Side
import fyi.fyw.etobackup.client.BackupClient
import io.netty.buffer.ByteBuf
import java.util.Collections
import java.util.WeakHashMap
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.network.NetworkManager

/**
 * The `etobackup` channel. A client with ETOBackup says [Hello] once it has joined; from then on the server sends it
 * chat lines as lang keys it shows in its own language and time zone ([Notice]), tells it when a snapshot starts so
 * that it keeps a picture of its screen ([SnapshotStarted]), and answers its backup screen ([ListRequest], [Action]).
 * A client without the mod never hears of the channel and gets plain English chat lines.
 */
object BackupNet {
    const val ACTION_NOW = 0
    const val ACTION_ROLLBACK = 1
    const val ACTION_UNDO = 2
    const val ACTION_CANCEL = 3

    /** The newest snapshots a list carries; a repository rarely holds more. */
    const val MAX_ROWS = 4000

    lateinit var channel: SimpleNetworkWrapper
        private set

    /** The connections whose client said hello. Netty and server threads. */
    private val greeted: MutableSet<NetworkManager> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    fun register() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(ETOBackup.MOD_ID)
        channel.registerMessage(HelloHandler::class.java, Hello::class.java, 0, Side.SERVER)
        channel.registerMessage(HelloReplyHandler::class.java, HelloReply::class.java, 1, Side.CLIENT)
        channel.registerMessage(NoticeHandler::class.java, Notice::class.java, 2, Side.CLIENT)
        channel.registerMessage(SnapshotStartedHandler::class.java, SnapshotStarted::class.java, 3, Side.CLIENT)
        channel.registerMessage(ListRequestHandler::class.java, ListRequest::class.java, 4, Side.SERVER)
        channel.registerMessage(ListReplyHandler::class.java, ListReply::class.java, 5, Side.CLIENT)
        channel.registerMessage(ActionHandler::class.java, Action::class.java, 6, Side.SERVER)
    }

    /** Whether [player]'s client has ETOBackup. */
    fun has(player: EntityPlayerMP): Boolean = player.playerNetServerHandler?.netManager?.let { it in greeted } == true

    private fun writeString(buf: ByteBuf, value: String) = ByteBufUtils.writeUTF8String(buf, value)

    private fun readString(buf: ByteBuf): String = ByteBufUtils.readUTF8String(buf)

    /** The client has ETOBackup. */
    class Hello : IMessage {
        override fun toBytes(buf: ByteBuf) {}

        override fun fromBytes(buf: ByteBuf) {}
    }

    /**
     * The server has ETOBackup. [running]: it takes backups (false when the repository could not be opened);
     * [repository] is its id, [singlePlayer] whether it is a single-player world's server.
     */
    class HelloReply(var running: Boolean = false, var repository: String = "", var singlePlayer: Boolean = false) : IMessage {
        override fun toBytes(buf: ByteBuf) {
            buf.writeBoolean(running)
            writeString(buf, repository)
            buf.writeBoolean(singlePlayer)
        }

        override fun fromBytes(buf: ByteBuf) {
            running = buf.readBoolean()
            repository = readString(buf)
            singlePlayer = buf.readBoolean()
        }
    }

    /**
     * A chat line as the lang key [key] with [args]. An argument that is a snapshot name is shown as that snapshot's
     * time, in the reader's time zone. With a [command]: [clickKey] names a link after the line that runs it, and
     * without a [clickKey] a click on the line puts it into the chat box.
     */
    class Notice(
        var key: String = "",
        var args: List<String> = emptyList(),
        var clickKey: String = "",
        var command: String = "",
    ) : IMessage {
        override fun toBytes(buf: ByteBuf) {
            writeString(buf, key)
            buf.writeByte(args.size)
            for (arg in args) writeString(buf, arg)
            writeString(buf, clickKey)
            writeString(buf, command)
        }

        override fun fromBytes(buf: ByteBuf) {
            key = readString(buf)
            args = List(buf.readUnsignedByte().toInt()) { readString(buf) }
            clickKey = readString(buf)
            command = readString(buf)
        }
    }

    /** The server starts the snapshot [name] of the repository [repository] right now. */
    class SnapshotStarted(var repository: String = "", var name: String = "") : IMessage {
        override fun toBytes(buf: ByteBuf) {
            writeString(buf, repository)
            writeString(buf, name)
        }

        override fun fromBytes(buf: ByteBuf) {
            repository = readString(buf)
            name = readString(buf)
        }
    }

    class ListRequest : IMessage {
        override fun toBytes(buf: ByteBuf) {}

        override fun fromBytes(buf: ByteBuf) {}
    }

    class Row(val name: String, val kind: String, val note: String, val files: Long, val bytes: Long, val storedBytes: Long)

    /** How the last rollback ended; see [Rollback.Outcome]. */
    class Last(val target: String, val pre: String, val by: String, val time: Long, val ok: Boolean, val message: String)

    class ListReply : IMessage {
        /** False: the server takes no backups (its repository could not be opened). */
        var available = false
        var repository = ""
        var singlePlayer = false

        /** The player may take snapshots and roll back. */
        var manage = false

        /** A rollback is announced: its target, or empty. */
        var pendingTarget = ""
        var pendingBy = ""
        var pendingSeconds = 0
        var last: Last? = null

        /** Oldest first. */
        var rows: List<Row> = emptyList()

        override fun toBytes(buf: ByteBuf) {
            buf.writeBoolean(available)
            writeString(buf, repository)
            buf.writeBoolean(singlePlayer)
            buf.writeBoolean(manage)
            writeString(buf, pendingTarget)
            writeString(buf, pendingBy)
            buf.writeInt(pendingSeconds)
            val last = last
            buf.writeBoolean(last != null)
            if (last != null) {
                writeString(buf, last.target)
                writeString(buf, last.pre)
                writeString(buf, last.by)
                buf.writeLong(last.time)
                buf.writeBoolean(last.ok)
                writeString(buf, last.message)
            }
            buf.writeInt(rows.size)
            for (row in rows) {
                writeString(buf, row.name)
                writeString(buf, row.kind)
                writeString(buf, row.note)
                buf.writeLong(row.files)
                buf.writeLong(row.bytes)
                buf.writeLong(row.storedBytes)
            }
        }

        override fun fromBytes(buf: ByteBuf) {
            available = buf.readBoolean()
            repository = readString(buf)
            singlePlayer = buf.readBoolean()
            manage = buf.readBoolean()
            pendingTarget = readString(buf)
            pendingBy = readString(buf)
            pendingSeconds = buf.readInt()
            last = if (!buf.readBoolean()) null else Last(
                readString(buf), readString(buf), readString(buf), buf.readLong(), buf.readBoolean(), readString(buf),
            )
            val count = buf.readInt()
            require(count in 0..MAX_ROWS) { "bad row count $count" }
            rows = List(count) {
                Row(readString(buf), readString(buf), readString(buf), buf.readLong(), buf.readLong(), buf.readLong())
            }
        }
    }

    /** What a button of the backup screen asks for: one of the `ACTION_` values, [name] being the snapshot to go back to. */
    class Action(var action: Int = 0, var name: String = "") : IMessage {
        override fun toBytes(buf: ByteBuf) {
            buf.writeByte(action)
            writeString(buf, name)
        }

        override fun fromBytes(buf: ByteBuf) {
            action = buf.readByte().toInt()
            name = readString(buf)
        }
    }

    class HelloHandler : IMessageHandler<Hello, IMessage> {
        override fun onMessage(message: Hello, ctx: MessageContext): IMessage? {
            val handler = ctx.serverHandler
            greeted += handler.netManager
            val player = handler.playerEntity
            BackupService.onServerThread { channel.sendTo(BackupService.hello(), player) }
            return null
        }
    }

    class HelloReplyHandler : IMessageHandler<HelloReply, IMessage> {
        override fun onMessage(message: HelloReply, ctx: MessageContext): IMessage? {
            BackupClient.onHello(message)
            return null
        }
    }

    class NoticeHandler : IMessageHandler<Notice, IMessage> {
        override fun onMessage(message: Notice, ctx: MessageContext): IMessage? {
            BackupClient.onNotice(message)
            return null
        }
    }

    class SnapshotStartedHandler : IMessageHandler<SnapshotStarted, IMessage> {
        override fun onMessage(message: SnapshotStarted, ctx: MessageContext): IMessage? {
            BackupClient.onSnapshotStarted(message)
            return null
        }
    }

    class ListReplyHandler : IMessageHandler<ListReply, IMessage> {
        override fun onMessage(message: ListReply, ctx: MessageContext): IMessage? {
            BackupClient.onList(message)
            return null
        }
    }

    class ListRequestHandler : IMessageHandler<ListRequest, IMessage> {
        override fun onMessage(message: ListRequest, ctx: MessageContext): IMessage? {
            val player = ctx.serverHandler.playerEntity
            BackupService.onServerThread { BackupService.sendList(player) }
            return null
        }
    }

    class ActionHandler : IMessageHandler<Action, IMessage> {
        override fun onMessage(message: Action, ctx: MessageContext): IMessage? {
            val player = ctx.serverHandler.playerEntity
            BackupService.onServerThread { BackupService.act(player, message.action, message.name) }
            return null
        }
    }
}
