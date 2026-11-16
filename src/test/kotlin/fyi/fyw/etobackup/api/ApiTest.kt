package fyi.fyw.etobackup.api

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApiTest {
    private fun snapshot(kind: Kind, note: String) =
        Snapshot("20260101T000000Z", Instant.parse("2026-01-01T00:00:00Z"), kind, note, 1, 2, 3)

    @Test
    fun `a snapshot tells who asked for it and which rollback it was taken before`() {
        assertThat(snapshot(Kind.AUTO, "").by).isEmpty()
        assertThat(snapshot(Kind.AUTO, "").rollbackTarget).isNull()
        assertThat(snapshot(Kind.MANUAL, "Alex").by).isEqualTo("Alex")
        val pre = snapshot(Kind.PRE_ROLLBACK, "20251231T120000Z Steve")
        assertThat(pre.by).isEqualTo("Steve")
        assertThat(pre.rollbackTarget).isEqualTo("20251231T120000Z")
    }

    @Test
    fun `a countdown counts down from when the state arrived`() {
        val now = System.currentTimeMillis()

        assertThat(Pending("20260101T000000Z", "Alex", 30, now + 29_500).secondsLeft()).isEqualTo(30)
        assertThat(Pending("20260101T000000Z", "Alex", 30, now - 1).secondsLeft()).isZero()
    }
}
