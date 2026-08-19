package org.aspose.pdf.sdm.doc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Minimal OLE2 / Compound File Binary ([MS-CFB]) reader — the container of the
 * legacy Word {@code .doc} format. Zero-dependency: plain byte-array parsing.
 *
 * <p>Supports: 512/4096-byte sectors, header + chained DIFAT, FAT chains,
 * directory tree (flattened to a name&rarr;entry map), and the mini stream
 * (streams under the 4096-byte cutoff live in 64-byte mini sectors chained
 * through the miniFAT inside the root entry's stream).</p>
 */
public final class CompoundFile {

    private static final Logger LOG = Logger.getLogger(CompoundFile.class.getName());

    private static final int FREESECT = 0xFFFFFFFF;
    private static final int ENDOFCHAIN = 0xFFFFFFFE;
    private static final int FATSECT = 0xFFFFFFFD;
    private static final int DIFSECT = 0xFFFFFFFC;

    private final byte[] data;
    private final int sectorSize;
    private final int miniSectorSize;
    private final long miniCutoff;
    private final int[] fat;
    private final int[] miniFat;
    private final Map<String, DirEntry> entries = new HashMap<>();
    private final byte[] miniStream;

    /** One directory entry (a storage or a stream). */
    private static final class DirEntry {
        String name;
        int type;        // 1=storage, 2=stream, 5=root
        int startSector;
        long size;
    }

    /**
     * Parses a compound file.
     *
     * @param bytes the whole file; must not be null
     * @throws IOException when the container is malformed
     */
    public CompoundFile(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 512) {
            throw new IOException("not a compound file: too short");
        }
        this.data = bytes;
        if (u32(0) != 0xE011CFD0L || u32(4) != 0xE11AB1A1L) {
            throw new IOException("not a compound file: bad magic");
        }
        int sectorShift = u16(0x1E);
        this.sectorSize = 1 << sectorShift;               // 512 (v3) or 4096 (v4)
        this.miniSectorSize = 1 << u16(0x20);             // 64
        int numFatSectors = (int) u32(0x2C);
        int firstDirSector = (int) u32(0x30);
        this.miniCutoff = u32(0x38);                      // 4096
        int firstMiniFatSector = (int) u32(0x3C);
        int numMiniFatSectors = (int) u32(0x40);
        int firstDifatSector = (int) u32(0x44);
        int numDifatSectors = (int) u32(0x48);

        // DIFAT: 109 entries in the header, then chained DIFAT sectors.
        List<Integer> fatSectors = new ArrayList<>();
        for (int i = 0; i < 109 && fatSectors.size() < numFatSectors; i++) {
            int s = (int) u32(0x4C + i * 4);
            if (s != FREESECT && s >= 0) {
                fatSectors.add(s);
            }
        }
        int difat = firstDifatSector;
        int guard = 0;
        while (difat != ENDOFCHAIN && difat != FREESECT && guard++ < numDifatSectors + 4) {
            int base = sectorOffset(difat);
            int perSector = sectorSize / 4 - 1;
            for (int i = 0; i < perSector && fatSectors.size() < numFatSectors; i++) {
                int s = (int) u32(base + i * 4);
                if (s != FREESECT && s >= 0) {
                    fatSectors.add(s);
                }
            }
            difat = (int) u32(base + sectorSize - 4);
        }

        // FAT: concatenation of all FAT sectors, each an array of int32 links.
        int entriesPerSector = sectorSize / 4;
        this.fat = new int[fatSectors.size() * entriesPerSector];
        int fi = 0;
        for (int s : fatSectors) {
            int base = sectorOffset(s);
            for (int i = 0; i < entriesPerSector; i++) {
                fat[fi++] = (int) u32(base + i * 4);
            }
        }

        // Directory: chain of sectors of 128-byte entries.
        byte[] dir = readChain(firstDirSector, Long.MAX_VALUE);
        DirEntry root = null;
        for (int off = 0; off + 128 <= dir.length; off += 128) {
            int nameLen = u16At(dir, off + 64);
            if (nameLen < 2 || nameLen > 64) {
                continue;
            }
            DirEntry e = new DirEntry();
            e.name = new String(dir, off, nameLen - 2, StandardCharsets.UTF_16LE);
            e.type = dir[off + 66] & 0xFF;
            e.startSector = (int) u32At(dir, off + 116);
            e.size = u32At(dir, off + 120);
            if (e.type == 5) {
                root = e;
            } else if (e.type == 2) {
                entries.putIfAbsent(e.name.toLowerCase(Locale.ROOT), e);
            }
        }

        // MiniFAT + mini stream (the root entry's own stream).
        int[] mf = new int[0];
        if (numMiniFatSectors > 0 && firstMiniFatSector != ENDOFCHAIN) {
            byte[] mfBytes = readChain(firstMiniFatSector, (long) numMiniFatSectors * sectorSize);
            mf = new int[mfBytes.length / 4];
            for (int i = 0; i < mf.length; i++) {
                mf[i] = (int) u32At(mfBytes, i * 4);
            }
        }
        this.miniFat = mf;
        this.miniStream = root != null && root.startSector != ENDOFCHAIN
                ? readChain(root.startSector, root.size)
                : new byte[0];
        LOG.fine(() -> "CFB: " + entries.size() + " stream(s), sector=" + sectorSize);
    }

    /**
     * Returns a stream's bytes by name (case-insensitive), or null when absent.
     * Control-character name prefixes (e.g. {@code SummaryInformation})
     * must be included by the caller when relevant.
     *
     * @param name the stream name
     * @return the stream content, or null
     */
    public byte[] getStream(String name) {
        DirEntry e = entries.get(name.toLowerCase(Locale.ROOT));
        if (e == null) {
            return null;
        }
        if (e.size < miniCutoff) {
            return readMiniChain(e.startSector, e.size);
        }
        return readChain(e.startSector, e.size);
    }

    /** @return true when a stream with the given name exists */
    public boolean hasStream(String name) {
        return entries.containsKey(name.toLowerCase(Locale.ROOT));
    }

    // ---- chains -------------------------------------------------------------

    private byte[] readChain(int start, long size) {
        List<byte[]> parts = new ArrayList<>();
        int s = start;
        long remaining = size;
        int guard = 0;
        while (s >= 0 && s != ENDOFCHAIN && s != FREESECT && guard++ < fat.length + 4) {
            int off = sectorOffset(s);
            int take = (int) Math.min(sectorSize, Math.max(0, Math.min(remaining,
                    (long) data.length - off)));
            if (take <= 0) {
                break;
            }
            byte[] chunk = new byte[take];
            System.arraycopy(data, off, chunk, 0, take);
            parts.add(chunk);
            remaining -= take;
            if (remaining <= 0 && size != Long.MAX_VALUE) {
                break;
            }
            s = s < fat.length ? fat[s] : ENDOFCHAIN;
            if (s == FATSECT || s == DIFSECT) {
                break;
            }
        }
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private byte[] readMiniChain(int start, long size) {
        byte[] out = new byte[(int) size];
        int pos = 0;
        int s = start;
        int guard = 0;
        while (s >= 0 && s != ENDOFCHAIN && s != FREESECT && pos < size
                && guard++ < miniFat.length + 4) {
            int off = s * miniSectorSize;
            int take = (int) Math.min(miniSectorSize, size - pos);
            if (off + take > miniStream.length) {
                take = Math.max(0, miniStream.length - off);
            }
            if (take <= 0) {
                break;
            }
            System.arraycopy(miniStream, off, out, pos, take);
            pos += take;
            s = s < miniFat.length ? miniFat[s] : ENDOFCHAIN;
        }
        return pos == size ? out : java.util.Arrays.copyOf(out, pos);
    }

    private int sectorOffset(int sector) {
        return 512 + sector * sectorSize;
    }

    // ---- little-endian helpers ---------------------------------------------

    private int u16(int off) {
        return u16At(data, off);
    }

    private long u32(int off) {
        return u32At(data, off);
    }

    private static int u16At(byte[] b, int off) {
        if (off < 0 || off + 2 > b.length) {
            return 0;
        }
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32At(byte[] b, int off) {
        if (off < 0 || off + 4 > b.length) {
            return 0;
        }
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }
}
