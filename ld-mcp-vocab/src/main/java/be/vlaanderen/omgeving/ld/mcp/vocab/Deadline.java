package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * A point in time by which a tool call must be done. Network requests, retries and pauses take
 * no longer than what is left, so that one call cannot keep the client waiting indefinitely.
 */
public final class Deadline {

    /** A deadline that never expires, for callers without a budget. */
    public static final Deadline NONE = new Deadline(Long.MAX_VALUE, System::nanoTime);

    /** Stands for "no limit" and still fits in every API that takes a duration. */
    private static final Duration UNLIMITED = Duration.ofDays(365L * 100);

    private final long end;
    private final LongSupplier clock;

    private Deadline(long end, LongSupplier clock) {
        this.end = end;
        this.clock = clock;
    }

    /**
     * Returns a deadline after the given budget, from now.
     *
     * @param budget the time allowed
     * @return the deadline
     */
    public static Deadline after(Duration budget) {
        return after(budget, System::nanoTime);
    }

    /**
     * Returns a deadline after the given budget, with a clock of one's own (for tests).
     *
     * @param budget the time allowed
     * @param clock  the clock, in nanoseconds
     * @return the deadline
     */
    static Deadline after(Duration budget, LongSupplier clock) {
        long end;
        try {
            end = Math.addExact(clock.getAsLong(), budget.toNanos());
        } catch (ArithmeticException e) {
            end = Long.MAX_VALUE;
        }
        return new Deadline(end, clock);
    }

    /**
     * Returns the time left.
     *
     * @return the time until the deadline; zero once it has passed
     */
    public Duration remaining() {
        if (end == Long.MAX_VALUE) {
            return UNLIMITED;
        }
        return Duration.ofNanos(Math.max(0, end - clock.getAsLong()));
    }

    /**
     * Tells whether the deadline has passed.
     *
     * @return whether no time is left
     */
    public boolean expired() {
        return remaining().isZero();
    }

    /**
     * Returns the smaller of a duration and the time left.
     *
     * @param d a duration, e.g. the time-out of one request
     * @return {@code d}, or the time left if that is shorter
     */
    public Duration cap(Duration d) {
        Duration left = remaining();
        return d.compareTo(left) <= 0 ? d : left;
    }
}
