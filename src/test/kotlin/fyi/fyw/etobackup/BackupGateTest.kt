package fyi.fyw.etobackup

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BackupGateTest {
    @Test
    fun `a chunk write waits while the gate is closed and is counted, whichever thread releases`() {
        val hold = BackupGate.hold()
        val closed = BackupGate.close(1000)
        val arrived = CountDownLatch(1)
        val written = AtomicBoolean()
        val io = thread {
            arrived.countDown()
            val stamp = BackupGate.enter()
            written.set(true)
            BackupGate.leave(stamp)
        }
        arrived.await()
        io.join(200)
        assertThat(written.get()).isFalse()

        thread { BackupGate.release(hold, closed) }.join()
        io.join(5000)

        assertThat(written.get()).isTrue()
        assertThat(BackupGate.heldWrites()).isEqualTo(1)
    }

    @Test
    fun `chunk writes pass during a hold until the gate is closed`() {
        val hold = BackupGate.hold()
        try {
            BackupGate.leave(BackupGate.enter())

            assertThat(BackupGate.heldWrites()).isZero()
        } finally {
            BackupGate.release(hold, 0)
        }
    }

    @Test
    fun `closing waits for a chunk write under way and gives up after the timeout`() {
        val stamp = BackupGate.enter()
        try {
            val failure = runCatching { BackupGate.close(100) }.exceptionOrNull()

            assertThat(failure).hasMessageContaining("did not finish within 100 ms")
        } finally {
            BackupGate.leave(stamp)
        }
    }

    @Test
    fun `a world save waits for the hold to end, and the release reports for how long`() {
        assertThat(BackupGate.release(BackupGate.hold(), 0)).isZero()
        assertThat(BackupGate.holding()).isFalse()

        val hold = BackupGate.hold()
        assertThat(BackupGate.holding()).isTrue()
        val save = thread { BackupGate.pass() }
        while (save.state != Thread.State.WAITING) Thread.sleep(1)
        Thread.sleep(50)
        val waited = BackupGate.release(hold, 0)
        save.join(5000)

        assertThat(save.isAlive).isFalse()
        assertThat(TimeUnit.NANOSECONDS.toMillis(waited)).isBetween(50L, 5000L)
    }

    @Test
    fun `a tick counts as saved only when every world was saved in it`() {
        val overworld = Any()
        val nether = Any()
        BackupGate.tickStarted()
        BackupGate.saved(overworld)
        assertThat(BackupGate.savedThisTick(arrayOf(overworld, null, nether))).isFalse()

        BackupGate.saved(nether)
        assertThat(BackupGate.savedThisTick(arrayOf(overworld, null, nether))).isTrue()

        BackupGate.tickStarted()
        assertThat(BackupGate.savedThisTick(arrayOf(overworld, null, nether))).isFalse()
    }
}
