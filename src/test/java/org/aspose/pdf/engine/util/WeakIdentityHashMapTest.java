package org.aspose.pdf.engine.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WeakIdentityHashMap}: identity (not equals) keying, plus
 * weak retention so entries evaporate once the key is unreachable — the two
 * properties that let the CFF font caches dedup within a document without
 * pinning every document's fonts for the JVM's lifetime.
 */
class WeakIdentityHashMapTest {

    @Test
    void keysByIdentityNotEquals() {
        WeakIdentityHashMap<String, Integer> map = new WeakIdentityHashMap<>();
        // Two distinct String INSTANCES that are equals()-equal.
        String a = new String("watermark");
        String b = new String("watermark");
        assertTrue(a.equals(b));
        assertFalse(a == b);

        map.put(a, 1);
        map.put(b, 2);
        // Content-keyed maps would collapse these into one entry (the bug that
        // corrupted glyphs across structurally-identical font dicts); identity
        // keeps them separate.
        assertEquals(2, map.size());
        assertEquals(1, map.get(a));
        assertEquals(2, map.get(b));
        assertTrue(map.containsKey(a));
        assertFalse(map.containsKey(new String("watermark")));
    }

    @Test
    void putGetRemoveNullValues() {
        WeakIdentityHashMap<Object, Object> map = new WeakIdentityHashMap<>();
        Object k = new Object();
        // Negative caching: a failed font load stores null but must still
        // register as present so callers don't retry.
        map.put(k, null);
        assertTrue(map.containsKey(k));
        assertNull(map.get(k));
        assertEquals(1, map.size());
        map.remove(k);
        assertFalse(map.containsKey(k));
        assertEquals(0, map.size());
    }

    @Test
    void entriesEvictWhenKeysAreUnreachable() throws InterruptedException {
        WeakIdentityHashMap<Object, byte[]> map = new WeakIdentityHashMap<>();
        List<Object> live = new ArrayList<>();
        // A live key must survive GC.
        Object pinned = new Object();
        map.put(pinned, new byte[16]);

        // Many keys we drop immediately — these simulate font dicts from closed
        // documents in a mass loop.
        for (int i = 0; i < 5000; i++) {
            Object doomed = new Object();
            map.put(doomed, new byte[1024]);
            // do NOT retain `doomed`
        }
        live.add(pinned);

        // Force collection of the unreachable keys.
        for (int i = 0; i < 5 && map.size() > 1; i++) {
            System.gc();
            Thread.sleep(50);
            map.size(); // triggers purge()
        }

        assertTrue(map.containsKey(pinned), "strongly-reachable key survives");
        assertTrue(map.size() < 5001,
                "unreachable keys must be reclaimed, size=" + map.size());
        // Reference `live` so the pinned key can't be optimised away early.
        assertEquals(1, live.size());
    }
}
