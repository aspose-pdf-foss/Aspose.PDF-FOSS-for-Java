package org.aspose.pdf.engine.font.ttf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Resolves a logical font name (e.g. {@code "SimSun"}) to its raw TrueType
 * bytes on disk, returning a freshly assembled TTF buffer suitable for
 * embedding into a PDF {@code /FontFile2} stream.
 *
 * <p>The lookup walks the OS-specific font directories (Windows
 * {@code C:\Windows\Fonts}, macOS {@code /Library/Fonts} +
 * {@code /System/Library/Fonts}, Linux {@code /usr/share/fonts}) and tries
 * a small set of filename variants for each: lowercase, no-spaces, and
 * common suffixes ({@code .ttf}, {@code .ttc}, {@code .otf}).</p>
 *
 * <p>When the match is a TrueType Collection ({@code .ttc} starting with
 * the {@code ttcf} magic), the first face is extracted into a brand-new
 * standalone TTF buffer — its sfnt header is rebuilt, table-directory
 * offsets are rewritten relative to the buffer start, and the shared
 * tables are copied from the source. PDF readers do not understand TTC
 * directly, so this re-packaging is what lets {@code SimSun} embed at all.</p>
 */
public final class FontDiskLookup {

    private static final Logger LOG = Logger.getLogger(FontDiskLookup.class.getName());

    private FontDiskLookup() {}

    /**
     * Searches the OS font directories for {@code logicalName} and returns
     * the bytes of a standalone TTF for the first matching face. Returns
     * {@code null} if no candidate file is found or none decodes cleanly.
     *
     * @param logicalName the font name as a caller would type it
     *                    ({@code "SimSun"}, {@code "Arial"}, etc.)
     * @return TTF bytes (TTC's first face extracted to a standalone TTF), or
     *         {@code null} if not resolvable
     */
    public static byte[] loadByName(String logicalName) {
        if (logicalName == null || logicalName.isEmpty()) return null;
        String[] dirs = osFontDirs();
        String[] basenames = candidateBasenames(logicalName);
        for (String dir : dirs) {
            Path dp = Paths.get(dir);
            if (!Files.isDirectory(dp)) continue;
            for (String base : basenames) {
                for (String ext : new String[]{".ttf", ".otf", ".ttc"}) {
                    Path p = dp.resolve(base + ext);
                    if (Files.isRegularFile(p)) {
                        byte[] bytes = readAll(p);
                        if (bytes == null) continue;
                        byte[] ttf = ext.equals(".ttc")
                                ? extractFirstFaceFromTTC(bytes)
                                : bytes;
                        if (ttf != null) {
                            LOG.fine("Resolved font " + logicalName + " → " + p);
                            return ttf;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Loads a font file by an exact basename (no candidate-list expansion).
     * Returns the file's bytes unchanged when it's a standalone TTF/OTF, or
     * the first face unpacked into a standalone TTF when it's a TTC.
     *
     * <p>Used by callers that have already narrowed the choice down to one
     * physical face — e.g. picking {@code simsunb} over {@code simsun} when
     * the text being rendered carries CJK Extension B characters.</p>
     *
     * @param basename file basename without extension (e.g. {@code "simsunb"})
     * @return font bytes, or {@code null} when the file is missing
     */
    public static byte[] loadByExactBasename(String basename) {
        if (basename == null || basename.isEmpty()) return null;
        for (String dir : osFontDirs()) {
            Path dp = Paths.get(dir);
            if (!Files.isDirectory(dp)) continue;
            for (String ext : new String[]{".ttf", ".otf", ".ttc"}) {
                Path p = dp.resolve(basename + ext);
                if (!Files.isRegularFile(p)) continue;
                byte[] bytes = readAll(p);
                if (bytes == null) continue;
                byte[] ttf = ext.equals(".ttc") ? extractFirstFaceFromTTC(bytes) : bytes;
                if (ttf != null) return ttf;
            }
        }
        return null;
    }

    /**
     * Resolves a font by {@code family} + style, searching {@code extraDirs} first (e.g. a
     * {@code -Dxfa.fontDir} override) then the OS font roots. Tries style-aware filename variants
     * ({@code arial}+bold → {@code arialbd}, {@code …i}, {@code …bi}, plus {@code -Bold}/{@code -Italic}
     * forms) and falls back to the regular face. Returns standalone TTF bytes (TTC first face
     * unpacked) or {@code null}; never throws (a missing dir / unreadable / malformed file is skipped).
     *
     * @param family    the requested family, e.g. {@code "Arial"} (style words may be present)
     * @param bold      bold requested
     * @param italic    italic requested
     * @param extraDirs directories to search before the OS roots ({@code null}/empty = OS roots only)
     * @return TTF bytes or {@code null}
     */
    public static byte[] loadStyled(String family, boolean bold, boolean italic, String[] extraDirs) {
        if (family == null || family.isEmpty()) {
            return null;
        }
        java.util.List<String> dirs = new java.util.ArrayList<>();
        if (extraDirs != null) {
            for (String d : extraDirs) {
                if (d != null && !d.isEmpty()) {
                    dirs.add(d);
                }
            }
        }
        java.util.Collections.addAll(dirs, osFontDirs());
        String[] basenames = styledBasenames(family, bold, italic);
        for (String dir : dirs) {
            Path dp = Paths.get(dir);
            if (!Files.isDirectory(dp)) {
                continue;
            }
            for (String base : basenames) {
                for (String ext : new String[]{".ttf", ".otf", ".ttc"}) {
                    Path p = dp.resolve(base + ext);
                    if (!Files.isRegularFile(p)) {
                        continue;
                    }
                    byte[] bytes = readAll(p);
                    if (bytes == null) {
                        continue;
                    }
                    byte[] ttf = ext.equals(".ttc") ? extractFirstFaceFromTTC(bytes) : bytes;
                    if (ttf != null) {
                        LOG.fine("Resolved font " + family + (bold ? " bold" : "") + (italic ? " italic" : "")
                                + " → " + p);
                        return ttf;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Last-resort lookup of a guaranteed Unicode-capable system font when the requested family
     * cannot be resolved. Picks a real OS font that covers Latin Extended-A/B (Czech, Polish,
     * Turkish, …) so non-WinAnsi text is never lost to a {@code '?'} substitution: a sans, serif or
     * monospace face on Windows (Arial/Times New Roman/Courier New), Linux (DejaVu/Liberation) or
     * macOS, matched to the requested category and style. Searches {@code extraDirs} then the OS
     * roots, recursively (depth-limited) so the nested Linux font tree is covered. Returns standalone
     * TTF bytes (TTC first face unpacked) or {@code null} if none of the well-known faces exist.
     *
     * @param serif     prefer a serif face (the requested family mapped to Times/serif)
     * @param mono      prefer a monospace face (mapped to Courier/mono); takes precedence over serif
     * @param bold      bold requested
     * @param italic    italic requested
     * @param extraDirs directories to search before the OS roots ({@code null}/empty = OS roots only)
     * @return TTF bytes or {@code null}
     */
    public static byte[] loadFallback(boolean serif, boolean mono, boolean bold, boolean italic,
                                      String[] extraDirs) {
        java.util.LinkedHashSet<String> bases = new java.util.LinkedHashSet<>(
                java.util.Arrays.asList(fallbackBasenames(serif, mono, bold, italic)));
        java.util.List<String> dirs = new java.util.ArrayList<>();
        if (extraDirs != null) {
            for (String d : extraDirs) {
                if (d != null && !d.isEmpty()) {
                    dirs.add(d);
                }
            }
        }
        java.util.Collections.addAll(dirs, osFontDirs());
        for (String dir : dirs) {
            byte[] ttf = searchRecursive(Paths.get(dir), bases, 0);
            if (ttf != null) {
                LOG.fine("Resolved fallback Unicode font → basenames " + bases + " in " + dir);
                return ttf;
            }
        }
        return null;
    }

    /** Candidate basenames for the fallback face, most-specific (styled, Windows-first) first. */
    private static String[] fallbackBasenames(boolean serif, boolean mono, boolean bold, boolean italic) {
        String winShort;   // Windows short basename stem (arial/times/cour)
        String dejavu;     // Linux DejaVu stem
        String liberation; // Linux Liberation stem
        if (mono) {
            winShort = "cour";
            dejavu = "DejaVuSansMono";
            liberation = "LiberationMono";
        } else if (serif) {
            winShort = "times";
            dejavu = "DejaVuSerif";
            liberation = "LiberationSerif";
        } else {
            winShort = "arial";
            dejavu = "DejaVuSans";
            liberation = "LiberationSans";
        }
        String winSuffix = (bold && italic) ? "bi" : bold ? "bd" : italic ? "i" : "";
        // DejaVu sans/mono use "Oblique"; DejaVu serif uses "Italic".
        String dvObl = serif ? "Italic" : "Oblique";
        String dvSuffix = (bold && italic) ? "-Bold" + dvObl : bold ? "-Bold" : italic ? "-" + dvObl : "";
        String libSuffix = (bold && italic) ? "-BoldItalic" : bold ? "-Bold" : italic ? "-Italic" : "-Regular";
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(winShort + winSuffix);          // styled Windows face
        out.add(dejavu + dvSuffix);
        out.add(liberation + libSuffix);
        // regular faces as the final fallback so a styled request still embeds *a* covering face
        out.add(winShort);
        out.add(dejavu);
        out.add(liberation + "-Regular");
        return out.toArray(new String[0]);
    }

    /** Depth-limited recursive search for {@code <base>.{ttf,otf,ttc}} under {@code dir}. */
    private static byte[] searchRecursive(Path dir, java.util.Set<String> bases, int depth) {
        if (depth > 4 || !Files.isDirectory(dir)) {
            return null;
        }
        try (java.util.stream.Stream<Path> list = Files.list(dir)) {
            java.util.List<Path> entries = list.collect(java.util.stream.Collectors.toList());
            // files first (cheaper, and the common Windows flat case hits immediately)
            for (Path p : entries) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot < 0) {
                    continue;
                }
                String stem = name.substring(0, dot);
                String ext = name.substring(dot).toLowerCase(Locale.ROOT);
                if (!ext.equals(".ttf") && !ext.equals(".otf") && !ext.equals(".ttc")) {
                    continue;
                }
                if (!bases.contains(stem)) {
                    continue;
                }
                byte[] bytes = readAll(p);
                if (bytes == null) {
                    continue;
                }
                byte[] ttf = ext.equals(".ttc") ? extractFirstFaceFromTTC(bytes) : bytes;
                if (ttf != null) {
                    return ttf;
                }
            }
            for (Path p : entries) {
                if (Files.isDirectory(p)) {
                    byte[] ttf = searchRecursive(p, bases, depth + 1);
                    if (ttf != null) {
                        return ttf;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.fine("Fallback font search skipped " + dir + ": " + e.getMessage());
        }
        return null;
    }

    /** Style-aware filename candidates for {@code family}: most-specific (styled) first, regular last. */
    private static String[] styledBasenames(String family, boolean bold, boolean italic) {
        String compact = family.toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "");
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String[] styleSuffixes;
        if (bold && italic) {
            styleSuffixes = new String[]{"bi", "bd_italic", "z", "bolditalic", "-bolditalic", "boldoblique", "-boldoblique"};
        } else if (bold) {
            styleSuffixes = new String[]{"bd", "b", "bold", "-bold"};
        } else if (italic) {
            styleSuffixes = new String[]{"i", "it", "italic", "-italic", "oblique", "-oblique"};
        } else {
            styleSuffixes = new String[]{"", "-regular", "regular"};
        }
        for (String s : styleSuffixes) {
            out.add(compact + s);
        }
        // also the "<Family> <Style>" compact form (e.g. "arialbold") and the well-known shortcuts
        for (String b : candidateBasenames(family)) {
            out.add(b);
        }
        // last resort: the regular face by plain name (so a styled request still embeds *a* face)
        out.add(compact);
        return out.toArray(new String[0]);
    }

    /** OS-specific font search roots, ordered most-likely first. */
    private static String[] osFontDirs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String winDir = System.getenv("WINDIR");
            if (winDir == null || winDir.isEmpty()) winDir = "C:\\Windows";
            return new String[]{winDir + "\\Fonts"};
        }
        if (os.contains("mac")) {
            return new String[]{"/Library/Fonts", "/System/Library/Fonts",
                    System.getProperty("user.home") + "/Library/Fonts"};
        }
        return new String[]{"/usr/share/fonts", "/usr/local/share/fonts",
                System.getProperty("user.home") + "/.fonts"};
    }

    /**
     * Generates filename candidates from a logical font name. SimSun lives at
     * {@code simsun.ttc}; {@code Arial Unicode MS} at {@code arialuni.ttf} —
     * exact mapping is intractable without a font catalog, so we try a few
     * sensible derivations (lowercase, strip spaces, well-known shortcuts)
     * and rely on the caller catching a {@code null} return.
     */
    private static String[] candidateBasenames(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String compact = lower.replace(" ", "").replace("-", "");
        String[] base;
        if ("arial unicode ms".equals(lower)) {
            base = new String[]{"arialuni", "arial"};
        } else if ("simsun".equals(lower)) {
            base = new String[]{"simsun", "simsunb"};
        } else if ("simhei".equals(lower)) {
            base = new String[]{"simhei"};
        } else if ("microsoft yahei".equals(lower)) {
            base = new String[]{"msyh", "msyhl"};
        } else {
            // Common Windows core families whose file basename differs from the
            // compacted family name (e.g. "Courier New" → cour.ttf, style suffixes
            // bd/i/bi). Prepend the mapped basename(s) before the naive guesses.
            String[] win = windowsCoreBasenames(lower);
            if (win != null) {
                base = new String[win.length + 2];
                System.arraycopy(win, 0, base, 0, win.length);
                base[win.length] = compact;
                base[win.length + 1] = lower;
            } else {
                base = new String[]{compact, lower};
            }
        }
        return base;
    }

    /**
     * Maps a Windows core-font family name (with optional bold/italic words) to
     * its actual on-disk file basename(s), most-specific first. Returns
     * {@code null} for families not in the well-known set.
     *
     * @param lower the lowercased family name (e.g. {@code "times new roman bold"})
     * @return candidate basenames (e.g. {@code {"timesbd"}}), or {@code null}
     */
    private static String[] windowsCoreBasenames(String lower) {
        boolean bold = lower.contains("bold");
        boolean italic = lower.contains("italic") || lower.contains("oblique");
        String stem = lower.replace("bold", "").replace("italic", "")
                .replace("oblique", "").replace("regular", "").trim();
        String prefix;
        switch (stem) {
            case "courier new": case "courier": prefix = "cour"; break;
            case "times new roman": case "times": prefix = "times"; break;
            case "arial": prefix = "arial"; break;
            case "calibri": prefix = "calibri"; break;
            case "verdana": prefix = "verdana"; break;
            case "tahoma": prefix = "tahoma"; break;
            case "georgia": prefix = "georgia"; break;
            case "comic sans ms": prefix = "comic"; break;
            case "trebuchet ms": prefix = "trebuc"; break;
            default: return null;
        }
        // Windows style suffix convention: bd (bold), i (italic), bi (bold+italic).
        String suffix = (bold ? "bd" : "") + (italic ? (bold ? "" : "i") : "");
        if (bold && italic) suffix = "bi";
        return suffix.isEmpty() ? new String[]{prefix} : new String[]{prefix + suffix, prefix};
    }

    private static byte[] readAll(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            LOG.fine("Failed to read " + p + ": " + e.getMessage());
            return null;
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  TTC → standalone TTF
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when {@code bytes} starts with the {@code ttcf}
     * magic of a TrueType Collection.
     */
    public static boolean isTTC(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && bytes[0] == 't' && bytes[1] == 't'
                && bytes[2] == 'c' && bytes[3] == 'f';
    }

    /**
     * Rewrites the first face of a TrueType Collection into a standalone
     * TTF buffer. Returns {@code null} if the input is malformed or does
     * not advertise at least one face.
     */
    public static byte[] extractFirstFaceFromTTC(byte[] ttc) {
        if (!isTTC(ttc) || ttc.length < 16) return null;
        int numFonts = readInt32(ttc, 8);
        if (numFonts <= 0 || 12 + numFonts * 4 > ttc.length) return null;
        int faceOffset = readInt32(ttc, 12);   // first face's sfnt header
        return extractFaceAt(ttc, faceOffset);
    }

    /**
     * Extracts the face whose sfnt header begins at {@code faceOffset} into
     * a fresh standalone TTF. Public for callers that pick non-default
     * faces from a TTC.
     */
    public static byte[] extractFaceAt(byte[] ttc, int faceOffset) {
        if (ttc == null || faceOffset < 0 || faceOffset + 12 > ttc.length) return null;
        int sfntVersion = readInt32(ttc, faceOffset);
        int numTables = readUInt16(ttc, faceOffset + 4);
        int searchRange = readUInt16(ttc, faceOffset + 6);
        int entrySelector = readUInt16(ttc, faceOffset + 8);
        int rangeShift = readUInt16(ttc, faceOffset + 10);

        int[] tableTags = new int[numTables];
        int[] checksums = new int[numTables];
        int[] offsets = new int[numTables];
        int[] lengths = new int[numTables];

        int totalLen = 12 + 16 * numTables;
        for (int i = 0; i < numTables; i++) {
            int entryOff = faceOffset + 12 + i * 16;
            if (entryOff + 16 > ttc.length) return null;
            tableTags[i] = readInt32(ttc, entryOff);
            checksums[i] = readInt32(ttc, entryOff + 4);
            offsets[i] = readInt32(ttc, entryOff + 8);
            lengths[i] = readInt32(ttc, entryOff + 12);
            totalLen += alignTo4(lengths[i]);
        }

        byte[] out = new byte[totalLen];
        writeInt32(out, 0, sfntVersion);
        writeUInt16(out, 4, numTables);
        writeUInt16(out, 6, searchRange);
        writeUInt16(out, 8, entrySelector);
        writeUInt16(out, 10, rangeShift);

        int writeCursor = 12 + 16 * numTables;
        for (int i = 0; i < numTables; i++) {
            int entryOff = 12 + i * 16;
            writeInt32(out, entryOff, tableTags[i]);
            writeInt32(out, entryOff + 4, checksums[i]);
            writeInt32(out, entryOff + 8, writeCursor);
            writeInt32(out, entryOff + 12, lengths[i]);
            if (offsets[i] + lengths[i] > ttc.length) return null;
            System.arraycopy(ttc, offsets[i], out, writeCursor, lengths[i]);
            writeCursor += alignTo4(lengths[i]);
        }
        return out;
    }

    private static int alignTo4(int n) {
        return (n + 3) & ~3;
    }

    private static int readInt32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static int readUInt16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static void writeInt32(byte[] b, int o, int v) {
        b[o]     = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    private static void writeUInt16(byte[] b, int o, int v) {
        b[o]     = (byte) (v >>> 8);
        b[o + 1] = (byte) v;
    }
}
