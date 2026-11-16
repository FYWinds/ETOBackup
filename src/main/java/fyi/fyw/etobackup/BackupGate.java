package fyi.fyw.etobackup;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

/**
 * Keeps the world files at the state of one save while a snapshot of them is taken off the server thread, without
 * turning saving off.
 * <p>
 * The server thread starts a {@link #hold} right after a save. While it lasts, the game queues no further chunk: it
 * unloads none ({@link #holding}), and a world save waits ({@link #pass}). The reader thread lets the chunks already
 * queued be written, then {@link #close closes} the gate for chunk writes as well, so nothing can change under it,
 * reads, and {@link #release releases} both.
 */
public final class BackupGate {

    private static final StampedLock HOLD = new StampedLock();
    private static final StampedLock WRITES = new StampedLock();
    /** When a world save started waiting for the hold to end, or 0. */
    private static final AtomicLong WAITING_SINCE = new AtomicLong();
    /** Chunk writes that found the gate closed during this hold: something queued a chunk anyway. */
    private static final AtomicInteger HELD_WRITES = new AtomicInteger();
    /** The worlds that were saved in full during the current tick. Server thread only. */
    private static final Set<Object> SAVED = Collections.newSetFromMap(new IdentityHashMap<>());

    private BackupGate() {}

    /** Before a chunk is written to its region file; waits while the gate is closed. */
    public static long enter() {
        long stamp = WRITES.tryReadLock();
        if (stamp == 0) {
            HELD_WRITES.incrementAndGet();
            stamp = WRITES.readLock();
        }
        return stamp;
    }

    public static void leave(long stamp) {
        WRITES.unlockRead(stamp);
    }

    /** Before a world save; waits while a hold lasts. */
    public static void pass() {
        long stamp = HOLD.tryReadLock();
        if (stamp == 0) {
            WAITING_SINCE.compareAndSet(0, System.nanoTime());
            stamp = HOLD.readLock();
        }
        HOLD.unlockRead(stamp);
    }

    /** Whether chunks are to stay loaded for now: unloading one saves it. */
    public static boolean holding() {
        return HOLD.isWriteLocked();
    }

    /** A full save of {@code world} begins. */
    public static void saved(Object world) {
        SAVED.add(world);
    }

    public static void tickStarted() {
        SAVED.clear();
    }

    /** Whether every one of {@code worlds} was saved in full during this tick, as the game's autosave does. */
    public static boolean savedThisTick(Object[] worlds) {
        boolean any = false;
        for (Object world : worlds) {
            if (world == null) continue;
            if (!SAVED.contains(world)) return false;
            any = true;
        }
        return any;
    }

    /** @return what {@link #release} needs */
    public static long hold() {
        long stamp = HOLD.writeLock();
        WAITING_SINCE.set(0);
        HELD_WRITES.set(0);
        return stamp;
    }

    /**
     * Closes the gate for chunk writes once none is under way.
     *
     * @return what {@link #release} needs
     */
    public static long close(long timeoutMillis) throws IOException, InterruptedException {
        long stamp = WRITES.tryWriteLock(timeoutMillis, TimeUnit.MILLISECONDS);
        if (stamp == 0) throw new IOException("chunk writes did not finish within " + timeoutMillis + " ms");
        return stamp;
    }

    /**
     * @param closed what {@link #close} returned, or 0 if it was not reached
     * @return for how many nanoseconds a world save waited
     */
    public static long release(long hold, long closed) {
        long since = WAITING_SINCE.getAndSet(0);
        if (closed != 0) WRITES.unlockWrite(closed);
        HOLD.unlockWrite(hold);
        return since == 0 ? 0 : System.nanoTime() - since;
    }

    /** How many chunk writes found the gate closed during the last hold. */
    public static int heldWrites() {
        return HELD_WRITES.get();
    }
}
