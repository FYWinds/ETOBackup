package fyi.fyw.etobackup.client

import fyi.fyw.etobackup.api.BackupText
import fyi.fyw.etobackup.api.BackupView
import fyi.fyw.etobackup.api.Kind
import fyi.fyw.etobackup.api.Picture
import fyi.fyw.etobackup.api.Snapshot
import fyi.fyw.etobackup.api.State
import java.time.Instant
import java.time.LocalDate
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.I18n
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11

/**
 * ETOBackup's own screen, built on [BackupView] alone: the snapshots by day, newest first, each with this client's
 * picture of that moment; the chosen one large with its details and the button that puts the world back to it (a
 * second click confirms); Refresh, Snapshot Now, Undo and Cancel; above them what is going on. It does not pause the
 * game: a single-player world's server must keep running for snapshots and countdowns.
 */
class BackupScreen(private val parent: GuiScreen?) : GuiScreen() {
    private sealed interface Row {
        val height: Int
    }

    private class Day(val date: LocalDate, val count: Int) : Row {
        override val height = DAY_HEIGHT
    }

    private class Shot(val snapshot: Snapshot) : Row {
        override val height = SHOT_HEIGHT
    }

    /** A picture uploaded to the graphics card. */
    private class Texture(picture: Picture) {
        val width = picture.width
        val height = picture.height
        private val texture = DynamicTexture(width, height)

        init {
            val data = texture.textureData
            val rgba = picture.rgba
            for (i in 0 until width * height) {
                data[i] = (rgba[4 * i + 3].toInt() and 255 shl 24) or (rgba[4 * i].toInt() and 255 shl 16) or
                    (rgba[4 * i + 1].toInt() and 255 shl 8) or (rgba[4 * i + 2].toInt() and 255)
            }
            texture.updateDynamicTexture()
        }

        /** Fills the box, cut to its shape ([cover]) or whole inside it. */
        fun draw(x: Int, y: Int, w: Int, h: Int, cover: Boolean) {
            if (w <= 0 || h <= 0) return
            val scale = if (cover) maxOf(w.toDouble() / width, h.toDouble() / height) else minOf(w.toDouble() / width, h.toDouble() / height)
            val drawnW = width * scale
            val drawnH = height * scale
            var u0 = 0.0
            var v0 = 0.0
            var u1 = 1.0
            var v1 = 1.0
            var left = x.toDouble()
            var top = y.toDouble()
            var right = x + drawnW
            var bottom = y + drawnH
            if (cover) {
                val du = (1 - w / drawnW) / 2
                val dv = (1 - h / drawnH) / 2
                u0 = du
                u1 = 1 - du
                v0 = dv
                v1 = 1 - dv
                right = (x + w).toDouble()
                bottom = (y + h).toDouble()
            } else {
                left = x + (w - drawnW) / 2
                top = y + (h - drawnH) / 2
                right = left + drawnW
                bottom = top + drawnH
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.glTextureId)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glColor4f(1f, 1f, 1f, 1f)
            val tessellator = Tessellator.instance
            tessellator.startDrawingQuads()
            tessellator.addVertexWithUV(left, bottom, 0.0, u0, v1)
            tessellator.addVertexWithUV(right, bottom, 0.0, u1, v1)
            tessellator.addVertexWithUV(right, top, 0.0, u1, v0)
            tessellator.addVertexWithUV(left, top, 0.0, u0, v0)
            tessellator.draw()
        }

        fun delete() = texture.deleteGlTexture()
    }

    private var listener: AutoCloseable? = null
    private var closed = false

    /** The state [rows] were made from. */
    private var shown: State? = null
    private var rows: List<Row> = emptyList()
    private var selected: String? = null

    /** What waits for the second click: a snapshot's name, or [UNDO]. */
    private var confirming: String? = null
    private var confirmFrom = 0L
    private var scroll = 0

    private val thumbs = HashMap<String, Texture?>()
    private var picture: Texture? = null
    private var pictureOf: String? = null

    private lateinit var refreshButton: GuiButton
    private lateinit var nowButton: GuiButton
    private lateinit var undoButton: GuiButton
    private lateinit var cancelButton: GuiButton
    private lateinit var doneButton: GuiButton
    private lateinit var rollbackButton: GuiButton
    private lateinit var confirmButton: GuiButton
    private lateinit var keepButton: GuiButton

    // The list's box, worked out in drawScreen.
    private var listLeft = 0
    private var listTop = 0
    private var listRight = 0
    private var listBottom = 0

    override fun initGui() {
        if (listener == null && !closed) {
            listener = BackupView.listen { sync() }
            BackupView.refresh()
        }
        buttonList.clear()
        refreshButton = button(ID_REFRESH, "etobackup.screen.refresh")
        nowButton = button(ID_NOW, "etobackup.screen.now")
        undoButton = button(ID_UNDO, "etobackup.screen.undo")
        cancelButton = button(ID_CANCEL, "etobackup.screen.cancel")
        doneButton = button(ID_DONE, "gui.done")
        rollbackButton = button(ID_ROLLBACK, "etobackup.screen.rollback")
        confirmButton = button(ID_CONFIRM, "etobackup.screen.confirm")
        keepButton = button(ID_KEEP, "etobackup.screen.keep")
        // Placed and shown by drawScreen.
        for (button in listOf(rollbackButton, confirmButton, keepButton)) button.visible = false
        sync()
    }

    private fun button(id: Int, key: String): GuiButton {
        val label = I18n.format(key)
        return GuiButton(id, 0, 0, fontRendererObj.getStringWidth(label) + 12, 20, label).also { buttonList.add(it) }
    }

    override fun onGuiClosed() {
        closed = true
        listener?.close()
        listener = null
        for (texture in thumbs.values) texture?.delete()
        thumbs.clear()
        picture?.delete()
        picture = null
        pictureOf = null
    }

    override fun doesGuiPauseGame(): Boolean = false

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (keyCode == 1) return close()
    }

    private fun close() {
        mc.displayGuiScreen(parent)
        if (parent == null) mc.setIngameFocus()
    }

    // ---- what is shown ----

    private fun state(): State? = BackupView.state

    private fun chosen(): Snapshot? = selected?.let { name -> state()?.snapshots?.firstOrNull { it.name == name } }

    /** Called on a change and once a second. */
    private fun sync() {
        if (closed) return
        val state = state()
        if (state !== shown) {
            if (state?.repository != shown?.repository) {
                for (texture in thumbs.values) texture?.delete()
                thumbs.clear()
                picture?.delete()
                picture = null
                pictureOf = null
                selected = null
            }
            shown = state
            rows = rows(state)
            val names = state?.snapshots.orEmpty()
            if (names.none { it.name == selected }) select(names.lastOrNull()?.name)
            if (state?.pending != null) confirming = null
        }
    }

    /** Newest first, each day under its own heading. */
    private fun rows(state: State?): List<Row> {
        val out = ArrayList<Row>()
        val newestFirst = state?.snapshots.orEmpty().asReversed()
        for ((date, ofDay) in newestFirst.groupBy { BackupText.zoned(it.time).toLocalDate() }) {
            out += Day(date, ofDay.size)
            for (snapshot in ofDay) out += Shot(snapshot)
        }
        return out
    }

    private fun select(name: String?) {
        if (name == selected) return
        selected = name
        confirming = null
        picture?.delete()
        picture = null
        pictureOf = null
        if (name == null) return
        val repository = state()?.repository
        BackupView.loadPicture(name, minOf(FULL_PIXELS, mc.displayWidth)) { loaded ->
            if (closed || selected != name || state()?.repository != repository) return@loadPicture
            picture?.delete()
            picture = loaded?.let { Texture(it) }
            pictureOf = name
        }
    }

    private fun thumb(name: String): Texture? {
        if (name in thumbs) return thumbs[name]
        thumbs[name] = null
        val repository = state()?.repository
        BackupView.loadPicture(name, THUMB_PIXELS) { loaded ->
            if (closed || state()?.repository != repository || loaded == null) return@loadPicture
            thumbs.put(name, Texture(loaded))?.delete()
        }
        return null
    }

    /** Seconds until the confirm button works, 0 once it does. */
    private fun confirmWait(): Int = maxOf(0L, (confirmFrom + CONFIRM_DELAY_MILLIS - System.currentTimeMillis() + 999) / 1000).toInt()

    private fun confirm(what: String) {
        confirming = what
        confirmFrom = System.currentTimeMillis()
    }

    override fun actionPerformed(button: GuiButton) {
        val state = state()
        when (button.id) {
            ID_REFRESH -> BackupView.refresh()
            ID_NOW -> BackupView.snapshotNow()
            ID_UNDO -> state?.last?.let {
                if (state.snapshots.any { snapshot -> snapshot.name == it.pre }) select(it.pre)
                confirm(UNDO)
            }
            ID_CANCEL -> BackupView.cancel()
            ID_DONE -> close()
            ID_ROLLBACK -> selected?.let { confirm(it) }
            ID_CONFIRM -> {
                val what = confirming ?: return
                if (confirmWait() > 0) return
                confirming = null
                if (what == UNDO) BackupView.undo() else BackupView.rollback(what)
            }
            ID_KEEP -> confirming = null
        }
    }

    override fun handleMouseInput() {
        super.handleMouseInput()
        val wheel = Mouse.getEventDWheel()
        if (wheel == 0) return
        val x = Mouse.getEventX() * width / mc.displayWidth
        val y = height - Mouse.getEventY() * height / mc.displayHeight - 1
        if (x in listLeft until listRight && y in listTop until listBottom) scroll -= Integer.signum(wheel) * SHOT_HEIGHT
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        super.mouseClicked(mouseX, mouseY, mouseButton)
        if (mouseButton != 0 || mouseX !in listLeft until listRight - SCROLLBAR || mouseY !in listTop until listBottom) return
        val row = rowAt(mouseY) as? Shot ?: return
        select(row.snapshot.name)
    }

    private fun rowAt(y: Int): Row? {
        var top = listTop - scroll
        for (row in rows) {
            if (y in top until top + row.height) return row
            top += row.height
        }
        return null
    }

    // ---- drawing ----

    private class Line(val text: String, val color: Int)

    private fun statusLines(state: State?): List<Line> {
        val lines = ArrayList<Line>()
        if (!BackupView.available) {
            lines += Line(I18n.format("etobackup.screen.unavailable"), GRAY)
            return lines
        }
        if (state == null) {
            lines += Line(I18n.format("etobackup.screen.loading"), GRAY)
            return lines
        }
        state.pending?.let {
            val seconds = it.secondsLeft()
            val world = if (state.singlePlayer) "_world" else ""
            val key = "etobackup.screen.pending$world" + when (seconds) {
                0 -> "_now"
                1 -> "_one"
                else -> ""
            }
            lines += Line(I18n.format(key, it.by, label(it.target), seconds), YELLOW)
        }
        state.last?.let {
            val time = BackupText.time(it.time)
            lines += if (it.ok) Line(I18n.format("etobackup.screen.last_ok", time, it.by, label(it.target)), GRAY)
            else Line(I18n.format("etobackup.screen.last_failed", time, it.by, label(it.target), it.message), RED)
        }
        // During a countdown the server's last line is the countdown the line above already shows.
        if (state.pending == null && BackupView.notice.isNotEmpty()) lines += Line(BackupView.notice, LIGHT)
        if (!state.canManage) lines += Line(I18n.format("etobackup.screen.no_manage"), GRAY)
        return lines
    }

    private fun label(name: String): String = BackupText.snapshot(name) ?: name

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        // Opaque: the game and its HUD must not show through.
        drawRect(0, 0, width, height, BACKDROP)
        val state = state()
        drawString(fontRendererObj, I18n.format("etobackup.screen.title"), MARGIN, MARGIN, WHITE)

        var y = MARGIN + fontRendererObj.FONT_HEIGHT + 4
        for (line in statusLines(state)) {
            for (wrapped in fontRendererObj.listFormattedStringToWidth(line.text, width - 2 * MARGIN)) {
                drawString(fontRendererObj, wrapped, MARGIN, y, line.color)
                y += fontRendererObj.FONT_HEIGHT + 1
            }
        }
        val bottomBar = height - MARGIN - 20
        layoutBottomBar(state, bottomBar)

        val top = y + 4
        val bottom = bottomBar - GAP
        listLeft = MARGIN
        listRight = MARGIN + (width * 2 / 5).coerceIn(150, 260).coerceAtMost(width - 2 * MARGIN)
        listTop = top
        listBottom = top
        rollbackButton.visible = false
        confirmButton.visible = false
        keepButton.visible = false
        if (state != null && bottom - top >= SHOT_HEIGHT) {
            listBottom = bottom
            drawList(state, mouseX, mouseY)
            drawDetails(state, listRight + GAP, top, width - MARGIN, bottom)
        }
        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    private fun layoutBottomBar(state: State?, y: Int) {
        val manage = state?.canManage == true
        val pending = state?.pending
        refreshButton.visible = BackupView.available
        nowButton.visible = manage
        nowButton.enabled = pending == null
        undoButton.visible = manage && state?.last?.ok == true && pending == null && confirming != UNDO
        cancelButton.visible = manage && pending != null && pending.secondsLeft() > 0
        var x = MARGIN
        for (button in listOf(refreshButton, nowButton, undoButton, cancelButton)) {
            if (!button.visible) continue
            button.xPosition = x
            button.yPosition = y
            x += button.width + 4
        }
        doneButton.xPosition = width - MARGIN - doneButton.width
        doneButton.yPosition = y
    }

    private fun drawList(state: State, mouseX: Int, mouseY: Int) {
        drawRect(listLeft, listTop, listRight, listBottom, PANEL)
        val total = rows.sumOf { it.height }
        val visible = listBottom - listTop
        scroll = scroll.coerceIn(0, maxOf(0, total - visible))
        if (rows.isEmpty()) {
            drawString(fontRendererObj, I18n.format("etobackup.screen.empty"), listLeft + 4, listTop + 4, GRAY)
            return
        }
        clip(listLeft, listTop, listRight, listBottom)
        try {
            var top = listTop - scroll
            val textLeft = listLeft + 4 + THUMB_W + 6
            for (row in rows) {
                if (top + row.height > listTop && top < listBottom) {
                    when (row) {
                        is Day -> drawString(fontRendererObj, dayTitle(row), listLeft + 4, top + 3, YELLOW)
                        is Shot -> {
                            val snapshot = row.snapshot
                            val hovered = mouseX in listLeft until listRight - SCROLLBAR && mouseY in maxOf(top, listTop) until minOf(top + row.height, listBottom)
                            if (snapshot.name == selected) drawRect(listLeft, top, listRight - SCROLLBAR, top + row.height, SELECTED)
                            else if (hovered) drawRect(listLeft, top, listRight - SCROLLBAR, top + row.height, HOVER)
                            val thumbTop = top + (row.height - THUMB_H) / 2
                            drawRect(listLeft + 4, thumbTop, listLeft + 4 + THUMB_W, thumbTop + THUMB_H, THUMB_BACK)
                            thumb(snapshot.name)?.draw(listLeft + 4, thumbTop, THUMB_W, THUMB_H, true)
                            val textWidth = listRight - SCROLLBAR - textLeft - 2
                            val time = "${BackupText.CLOCK.format(BackupText.zoned(snapshot.time))}  ${BackupText.ago(snapshot.time)}"
                            drawString(fontRendererObj, fontRendererObj.trimStringToWidth(time, textWidth), textLeft, top + 5, WHITE)
                            val kind = BackupText.kind(snapshot.kind)
                            drawString(fontRendererObj, kind, textLeft, top + 17, kindColor(snapshot.kind))
                            val added = "+" + BackupText.size(snapshot.storedBytes)
                            val kindWidth = fontRendererObj.getStringWidth("$kind  ")
                            drawString(fontRendererObj, fontRendererObj.trimStringToWidth(added, maxOf(0, textWidth - kindWidth)), textLeft + kindWidth, top + 17, GRAY)
                        }
                    }
                }
                top += row.height
            }
        } finally {
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
        }
        if (total > visible) {
            val barHeight = maxOf(16, visible * visible / total)
            val barTop = listTop + (visible - barHeight) * scroll / (total - visible)
            drawRect(listRight - SCROLLBAR, listTop, listRight, listBottom, SCROLL_TRACK)
            drawRect(listRight - SCROLLBAR, barTop, listRight, barTop + barHeight, SCROLL_BAR)
        }
    }

    private fun dayTitle(day: Day): String {
        val today = BackupText.zoned(Instant.now()).toLocalDate()
        val name = when (day.date) {
            today -> I18n.format("etobackup.screen.today")
            today.minusDays(1) -> I18n.format("etobackup.screen.yesterday")
            else -> BackupText.DAY.format(day.date)
        }
        return I18n.format("etobackup.screen.day", name, day.count)
    }

    private fun kindColor(kind: Kind): Int = when (kind) {
        Kind.AUTO -> GRAY
        Kind.MANUAL -> GREEN
        Kind.PRE_ROLLBACK -> YELLOW
    }

    private fun drawDetails(state: State, left: Int, top: Int, right: Int, bottom: Int) {
        if (right - left < 60) return
        drawRect(left, top, right, bottom, PANEL)
        val snapshot = chosen()
        val inner = right - left - 8
        val lineHeight = fontRendererObj.FONT_HEIGHT + 1
        val text = ArrayList<Line>()
        val undo = confirming == UNDO
        if (snapshot != null) {
            text += Line("${BackupText.FULL.format(BackupText.zoned(snapshot.time))}  (${BackupText.ago(snapshot.time)})", WHITE)
            text += Line(kindDetail(snapshot), LIGHT)
            text += Line(
                I18n.format("etobackup.screen.size", snapshot.files, BackupText.size(snapshot.bytes), BackupText.size(snapshot.storedBytes), snapshot.name),
                GRAY,
            )
        }
        val confirmingHere = confirming != null && (undo || confirming == snapshot?.name)
        if (confirmingHere) {
            val world = if (state.singlePlayer) "_world" else ""
            text += Line(
                if (undo) I18n.format("etobackup.screen.undo_warning$world", label(state.last?.pre.orEmpty()))
                else I18n.format("etobackup.screen.warning$world"),
                YELLOW,
            )
        }
        val wrapped = text.flatMap { line -> fontRendererObj.listFormattedStringToWidth(line.text, inner).map { Line(it, line.color) } }
        val showButtons = state.canManage && (snapshot != null || undo)
        val buttonsHeight = if (showButtons) 24 else 0
        val textHeight = wrapped.size * lineHeight
        val pictureBottom = bottom - 4 - buttonsHeight - textHeight - 4
        if (snapshot != null && pictureBottom - (top + 4) >= MIN_PICTURE) {
            val shown = picture?.takeIf { pictureOf == snapshot.name }
            if (shown != null) {
                shown.draw(left + 4, top + 4, inner, pictureBottom - top - 4, false)
            } else {
                var y = (top + pictureBottom) / 2 - lineHeight
                for (line in fontRendererObj.listFormattedStringToWidth(I18n.format("etobackup.screen.no_picture"), inner)) {
                    drawCenteredString(fontRendererObj, line, (left + right) / 2, y, GRAY)
                    y += lineHeight
                }
            }
        }
        var y = bottom - 4 - buttonsHeight - textHeight
        for (line in wrapped) {
            drawString(fontRendererObj, line.text, left + 4, y, line.color)
            y += lineHeight
        }
        if (!showButtons) return
        val buttonsTop = bottom - 4 - 20
        if (confirmingHere) {
            val wait = confirmWait()
            confirmButton.displayString = if (wait > 0) I18n.format("etobackup.screen.confirm_wait", wait) else I18n.format("etobackup.screen.confirm")
            confirmButton.width = fontRendererObj.getStringWidth(I18n.format("etobackup.screen.confirm_wait", 3)) + 12
            confirmButton.enabled = wait == 0 && state.pending == null
            confirmButton.visible = true
            confirmButton.xPosition = left + 4
            confirmButton.yPosition = buttonsTop
            keepButton.visible = true
            keepButton.xPosition = left + 4 + confirmButton.width + 4
            keepButton.yPosition = buttonsTop
        } else if (snapshot != null) {
            rollbackButton.visible = true
            rollbackButton.enabled = state.pending == null
            rollbackButton.xPosition = left + 4
            rollbackButton.yPosition = buttonsTop
        }
    }

    private fun kindDetail(snapshot: Snapshot): String = when (snapshot.kind) {
        Kind.MANUAL -> I18n.format("etobackup.screen.detail.manual", snapshot.by)
        Kind.PRE_ROLLBACK -> I18n.format("etobackup.screen.detail.prerollback", snapshot.by, label(snapshot.rollbackTarget.orEmpty()))
        Kind.AUTO -> I18n.format("etobackup.screen.detail.auto")
    }

    /** Limits drawing to the box, in this screen's coordinates. */
    private fun clip(left: Int, top: Int, right: Int, bottom: Int) {
        val scale = ScaledResolution(mc, mc.displayWidth, mc.displayHeight).scaleFactor
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor(left * scale, mc.displayHeight - bottom * scale, (right - left) * scale, (bottom - top) * scale)
    }

    private companion object {
        /** Not a snapshot name. */
        const val UNDO = "undo"
        const val CONFIRM_DELAY_MILLIS = 3000L

        const val MARGIN = 6
        const val GAP = 6
        const val DAY_HEIGHT = 14
        const val SHOT_HEIGHT = 32
        const val THUMB_W = 48
        const val THUMB_H = 27
        const val SCROLLBAR = 4
        const val MIN_PICTURE = 40
        const val THUMB_PIXELS = 160
        const val FULL_PIXELS = 1920

        const val ID_REFRESH = 1
        const val ID_NOW = 2
        const val ID_UNDO = 3
        const val ID_CANCEL = 4
        const val ID_DONE = 5
        const val ID_ROLLBACK = 6
        const val ID_CONFIRM = 7
        const val ID_KEEP = 8

        const val WHITE = 0xFFFFFF
        const val LIGHT = 0xDDDDDD
        const val GRAY = 0xA0A0A0
        const val YELLOW = 0xFFFF55
        const val RED = 0xFF5555
        const val GREEN = 0x55FF55
        const val BACKDROP = 0xFF101317.toInt()
        const val PANEL = 0xFF1D2127.toInt()
        const val SELECTED = 0xFF3A4150.toInt()
        const val HOVER = 0xFF292E37.toInt()
        const val THUMB_BACK = 0xFF0A0B0D.toInt()
        const val SCROLL_TRACK = 0x40000000
        const val SCROLL_BAR = 0xA0C0C0C0.toInt()
    }
}
