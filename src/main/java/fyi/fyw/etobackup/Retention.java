package fyi.fyw.etobackup;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Which snapshots to keep. */
public final class Retention {

    /**
     * Every snapshot of the last {@code allHours} hours before the newest one, plus the newest snapshot of each of
     * the last {@code hourly} hours, {@code daily} days, {@code weekly} ISO weeks and {@code monthly} months that
     * have one. The rules add up. Of what they keep, snapshots more than {@code maxAgeDays} days older than the
     * newest one are dropped again (0: no limit). The newest snapshot is always kept.
     */
    public static final class Policy {

        private final int allHours;
        private final int hourly;
        private final int daily;
        private final int weekly;
        private final int monthly;
        private final int maxAgeDays;

        public Policy(int allHours, int hourly, int daily, int weekly, int monthly, int maxAgeDays) {
            this.allHours = allHours;
            this.hourly = hourly;
            this.daily = daily;
            this.weekly = weekly;
            this.monthly = monthly;
            this.maxAgeDays = maxAgeDays;
        }

        public int allHours() {
            return allHours;
        }

        public int hourly() {
            return hourly;
        }

        public int daily() {
            return daily;
        }

        public int weekly() {
            return weekly;
        }

        public int monthly() {
            return monthly;
        }

        public int maxAgeDays() {
            return maxAgeDays;
        }
    }

    private Retention() {}

    /** @return the names to keep; a name that is not a snapshot time is kept */
    public static Set<String> keep(List<String> names, Policy policy, ZoneId zone) {
        Set<String> keep = new HashSet<>();
        List<String> newestFirst = new ArrayList<>();
        for (String name : names) {
            if (Manifest.timeOf(name) == null) keep.add(name);
            else newestFirst.add(name);
        }
        if (newestFirst.isEmpty()) return keep;
        newestFirst.sort(Comparator.comparing(Manifest::timeOf)
            .reversed());
        Instant newest = Manifest.timeOf(newestFirst.get(0));
        keep.add(newestFirst.get(0));
        Instant allAfter = newest.minus(policy.allHours, ChronoUnit.HOURS);
        for (String name : newestFirst) {
            if (!Manifest.timeOf(name)
                .isBefore(allAfter)) keep.add(name);
        }
        newestOfEach(newestFirst, zone, policy.hourly, keep, t -> t.truncatedTo(ChronoUnit.HOURS));
        newestOfEach(newestFirst, zone, policy.daily, keep, ZonedDateTime::toLocalDate);
        newestOfEach(
            newestFirst,
            zone,
            policy.weekly,
            keep,
            t -> t.get(IsoFields.WEEK_BASED_YEAR) * 100 + t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        newestOfEach(newestFirst, zone, policy.monthly, keep, t -> t.getYear() * 100 + t.getMonthValue());
        if (policy.maxAgeDays > 0) {
            Instant oldest = newest.minus(policy.maxAgeDays, ChronoUnit.DAYS);
            for (String name : newestFirst) {
                if (Manifest.timeOf(name)
                    .isBefore(oldest)) keep.remove(name);
            }
        }
        return keep;
    }

    private static void newestOfEach(List<String> newestFirst, ZoneId zone, int periods, Set<String> keep,
        Function<ZonedDateTime, Object> period) {
        Set<Object> seen = new HashSet<>();
        for (String name : newestFirst) {
            if (seen.size() >= periods) return;
            if (seen.add(
                period.apply(
                    Manifest.timeOf(name)
                        .atZone(zone)))) keep.add(name);
        }
    }
}
