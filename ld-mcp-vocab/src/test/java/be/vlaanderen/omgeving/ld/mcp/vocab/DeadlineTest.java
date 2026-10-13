package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class DeadlineTest {

    @Test
    void countsDownAndCaps() {
        AtomicLong now = new AtomicLong(Long.MIN_VALUE + 5); // nanoTime may be negative
        Deadline d = Deadline.after(Duration.ofSeconds(10), now::get);
        assertEquals(Duration.ofSeconds(10), d.remaining());
        assertEquals(Duration.ofSeconds(3), d.cap(Duration.ofSeconds(3)));
        now.addAndGet(Duration.ofSeconds(8).toNanos());
        assertEquals(Duration.ofSeconds(2), d.cap(Duration.ofSeconds(3)));
        assertFalse(d.expired());
        now.addAndGet(Duration.ofSeconds(5).toNanos());
        assertEquals(Duration.ZERO, d.remaining());
        assertTrue(d.expired());
    }

    @Test
    void noneAndHugeBudgetsDoNotOverflow() {
        assertFalse(Deadline.NONE.expired());
        assertEquals(Duration.ofSeconds(5), Deadline.NONE.cap(Duration.ofSeconds(5)));
        Deadline huge = Deadline.after(Duration.ofSeconds(Long.MAX_VALUE / 2), () -> Long.MAX_VALUE - 10);
        assertFalse(huge.expired());
    }
}
