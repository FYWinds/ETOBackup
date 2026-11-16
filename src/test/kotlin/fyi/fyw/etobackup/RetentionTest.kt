package fyi.fyw.etobackup

import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RetentionTest {
    @Test
    fun `recent snapshots are all kept, older ones one per hour and day`() {
        val names = listOf(
            "20260101T100000Z", "20260101T234500Z",
            "20260102T080000Z", "20260102T233000Z",
            "20260103T090000Z", "20260103T091500Z", "20260103T114500Z", "20260103T120000Z",
        )

        val keep = Retention.keep(names, Retention.Policy(1, 2, 3, 0, 0, 0), ZoneOffset.UTC)

        assertThat(keep).containsExactlyInAnyOrder(
            "20260103T120000Z", "20260103T114500Z", "20260102T233000Z", "20260101T234500Z",
        )
    }

    @Test
    fun `weeks follow the ISO calendar and months the calendar month`() {
        // 31 January and 1 February 2026 are the Saturday and Sunday of one week.
        val names = listOf("20260131T000000Z", "20260201T000000Z", "20260202T000000Z", "20260228T000000Z")

        val keep = Retention.keep(names, Retention.Policy(0, 0, 0, 2, 2, 0), ZoneOffset.UTC)

        assertThat(keep).containsExactlyInAnyOrder("20260228T000000Z", "20260202T000000Z", "20260131T000000Z")
    }

    @Test
    fun `days are the days of the given time zone`() {
        val names = listOf("20260101T120000Z", "20260101T233000Z", "20260102T003000Z")
        val policy = Retention.Policy(0, 0, 2, 0, 0, 0)

        assertThat(Retention.keep(names, policy, ZoneOffset.UTC))
            .containsExactlyInAnyOrder("20260102T003000Z", "20260101T233000Z")
        assertThat(Retention.keep(names, policy, ZoneId.of("Asia/Shanghai")))
            .containsExactlyInAnyOrder("20260102T003000Z", "20260101T120000Z")
    }

    @Test
    fun `with every rule at zero the newest snapshot is still kept`() {
        val keep = Retention.keep(listOf("20260101T000000Z", "20260102T000000Z"), Retention.Policy(0, 0, 0, 0, 0, 0), ZoneOffset.UTC)

        assertThat(keep).containsExactly("20260102T000000Z")
    }

    @Test
    fun `the age limit drops what the keep rules would hold on to, never the newest`() {
        val names = listOf("20260101T000000Z", "20260120T000000Z", "20260201T000000Z", "20260210T000000Z")

        val keep = Retention.keep(names, Retention.Policy(0, 0, 100, 0, 12, 30), ZoneOffset.UTC)

        assertThat(keep).containsExactlyInAnyOrder("20260210T000000Z", "20260201T000000Z", "20260120T000000Z")
        assertThat(Retention.keep(listOf("20260101T000000Z"), Retention.Policy(0, 0, 0, 0, 0, 1), ZoneOffset.UTC))
            .containsExactly("20260101T000000Z")
    }
}
