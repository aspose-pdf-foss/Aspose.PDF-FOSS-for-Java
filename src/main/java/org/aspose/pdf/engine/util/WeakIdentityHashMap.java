package org.aspose.pdf.engine.util;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * A map that keys on the <em>identity</em> of its keys (like
 * {@link java.util.IdentityHashMap}) but holds those keys through
 * {@link WeakReference}s (like {@link java.util.WeakHashMap}), so an entry is
 * evicted automatically once its key is no longer strongly reachable anywhere
 * else.
 *
 * <p>Neither of the JDK maps offers both properties at once:
 * {@code IdentityHashMap} pins its keys forever (a process-wide static cache
 * keyed on per-document objects then leaks the whole document graph across a
 * mass-conversion loop), while {@code WeakHashMap} keys on {@code equals}
 * (deep, and — for {@code PdfDictionary} — content-based, which collides
 * structurally-identical dictionaries from different documents). This class is
 * the intersection: identity comparison for keys, weak retention for their
 * lifetime.</p>
 *
 * <p><strong>Value/key coupling caveat:</strong> like {@code WeakHashMap}, the
 * value is held strongly. If a value transitively references its own key the
 * entry can never be collected — callers must ensure values are independent of
 * their keys (the CFF caches store AWT {@code Font}s and {@code int[]} glyph
 * maps, none of which point back at the font dictionary).</p>
 *
 * <p>All public methods are {@code synchronized}, matching the
 * {@code Collections.synchronizedMap(...)} wrappers this class replaces.</p>
 *
 * @param <K> key type (compared by identity, retained weakly)
 * @param <V> value type (retained strongly)
 */
public final class WeakIdentityHashMap<K, V> {

    private final Map<IdentityWeakKey, V> map = new HashMap<>();
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();

    /**
     * Returns the value mapped to {@code key} (by identity), or {@code null}.
     *
     * @param key the lookup key
     * @return the mapped value, or {@code null} if absent
     */
    public synchronized V get(Object key) {
        purge();
        return map.get(new IdentityWeakKey(key, null));
    }

    /**
     * Returns {@code true} if a mapping for {@code key} (by identity) exists.
     *
     * @param key the lookup key
     * @return whether the key is present
     */
    public synchronized boolean containsKey(Object key) {
        purge();
        return map.containsKey(new IdentityWeakKey(key, null));
    }

    /**
     * Associates {@code value} with {@code key} (by identity).
     *
     * @param key   the key (retained weakly)
     * @param value the value (retained strongly; must not reference {@code key})
     * @return the previous value, or {@code null}
     */
    public synchronized V put(K key, V value) {
        purge();
        return map.put(new IdentityWeakKey(key, queue), value);
    }

    /**
     * Removes the mapping for {@code key} (by identity).
     *
     * @param key the key to remove
     * @return the removed value, or {@code null}
     */
    public synchronized V remove(Object key) {
        purge();
        return map.remove(new IdentityWeakKey(key, null));
    }

    /**
     * Returns the number of live mappings (after purging cleared keys).
     *
     * @return the size
     */
    public synchronized int size() {
        purge();
        return map.size();
    }

    /** Removes all mappings and drains the reference queue. */
    public synchronized void clear() {
        map.clear();
        while (queue.poll() != null) {
            // drain
        }
    }

    /**
     * Drops entries whose key has been reclaimed. The reference queue hands
     * back the very {@code IdentityWeakKey} instance stored in the map, so
     * {@code HashMap.remove} matches it by reference identity ({@code ==})
     * even though its referent (and thus {@link IdentityWeakKey#equals}) is
     * now dead.
     */
    private void purge() {
        Object stale;
        while ((stale = queue.poll()) != null) {
            map.remove(stale);
        }
    }

    /**
     * Weak reference to a key that hashes and compares by the referent's
     * identity. The identity hash is captured at construction so it stays
     * stable after the referent is cleared (needed for {@link #purge()} to
     * find the dead entry's bucket).
     */
    private static final class IdentityWeakKey extends WeakReference<Object> {
        private final int hash;

        IdentityWeakKey(Object referent, ReferenceQueue<Object> q) {
            super(referent, q);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof IdentityWeakKey)) {
                return false;
            }
            Object a = get();
            Object b = ((IdentityWeakKey) o).get();
            // Two distinct-but-live keys are equal iff they hold the SAME
            // instance; a cleared referent (null) matches nothing but itself
            // (handled by the == short-circuit above).
            return a != null && a == b;
        }
    }
}
