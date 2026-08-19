package org.aspose.pdf.engine.font.cmap;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * CID &rarr; Unicode tables for STANDARD character collections (ISO 32000-1
 * §9.7.3 /CIDSystemInfo). A CID font whose registry-ordering names a public
 * collection (e.g. {@code Adobe-Korea1}) assigns every CID a fixed character
 * meaning even when the font ships no {@code /ToUnicode} CMap and no usable
 * glyph names — the mapping is defined by Adobe's published collection data.
 *
 * <p>The bundled tables are derived from Adobe's open-source
 * <a href="https://github.com/adobe-type-tools/cmap-resources">cmap-resources</a>
 * ({@code cid2code.txt}, BSD-3-Clause; the license text is retained in each
 * resource file). One text resource per collection: line N (0-based, after
 * {@code #} comments) is CID N's BMP code point in hex, empty = unmapped.</p>
 */
public final class CidOrderingUnicode {

    private static final Logger LOG = Logger.getLogger(CidOrderingUnicode.class.getName());

    /** registry-ordering &rarr; bundled resource (grow as collections are added). */
    private static final Map<String, String> RESOURCES = Map.of(
            "Adobe-Korea1", "adobe-korea1-ucs2.txt");

    private static final ConcurrentHashMap<String, char[]> CACHE = new ConcurrentHashMap<>();

    private CidOrderingUnicode() {
        // static lookup only
    }

    /**
     * Looks up the Unicode code point of a CID in a standard collection.
     *
     * @param registryOrdering the collection key, {@code Registry + "-" + Ordering}
     *                         (e.g. {@code "Adobe-Korea1"}); null-safe
     * @param cid              the character identifier
     * @return the BMP code point, or 0 when the collection is unknown or the
     *         CID is unmapped
     */
    public static int lookup(String registryOrdering, int cid) {
        if (registryOrdering == null || cid < 0) {
            return 0;
        }
        String resource = RESOURCES.get(registryOrdering);
        if (resource == null) {
            return 0;
        }
        char[] table = CACHE.computeIfAbsent(resource, CidOrderingUnicode::load);
        return cid < table.length ? table[cid] : 0;
    }

    private static char[] load(String name) {
        InputStream in = CidOrderingUnicode.class.getResourceAsStream(name);
        if (in == null) {
            LOG.warning("CID ordering table resource missing: " + name);
            return new char[0];
        }
        StringBuilder sb = new StringBuilder(20_000);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) {
                    continue;
                }
                String hex = line.trim();
                sb.append(hex.isEmpty() ? '\0' : (char) Integer.parseInt(hex, 16));
            }
        } catch (Exception e) {
            LOG.warning("CID ordering table " + name + " failed to load: " + e);
            return new char[0];
        }
        LOG.fine(() -> "CID ordering table " + name + ": " + sb.length() + " CIDs");
        return sb.toString().toCharArray();
    }
}
