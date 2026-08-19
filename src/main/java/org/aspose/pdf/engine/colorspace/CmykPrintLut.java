package org.aspose.pdf.engine.colorspace;

/**
 * Acrobat <b>print-path</b> DeviceCMYK &rarr; sRGB conversion, active only under
 * {@code -Drender.acrobatPrintParity=true} (the harness mode that compares our
 * raster against Adobe Acrobat "print to PDF" golds).
 * <p>
 * Acrobat's print pipeline converts DeviceCMYK through its color management
 * (a perceptual-leaning press-to-monitor transform), which differs visibly from
 * the colorimetric process-ink model in {@link CmykDisplay}: e.g. solid magenta
 * prints as ~rgb(246,86,160), not the process-solid rgb(236,0,140), and saturated
 * green mixes (C+Y) keep a substantial blue component.
 * </p>
 * <p>
 * This class reproduces that transform with a measured 6&times;6&times;6&times;6
 * lattice (levels 0, 0.2, .. 1.0 per ink) and quadrilinear interpolation. The
 * lattice was <b>measured by this project</b>: a self-generated CMYK patch chart
 * ({@code visual-mass/work/cmyk-chart2.pdf}, 1296 lattice + validation patches)
 * was printed through Adobe Acrobat &rarr; Microsoft Print to PDF and the
 * resulting RGB fills were read back from the printed file. It is behavioral
 * measurement data of our own chart &mdash; <b>no ICC profile or other
 * third-party data is embedded</b>. Held-out validation against 705 independent
 * off-lattice patches: mean max-channel error 0.88/255, p95 = 4/255.
 * </p>
 * <p>
 * Kill switches: inactive unless {@code render.acrobatPrintParity=true}; can be
 * disabled separately via {@code -Drender.printParityCmykLut=false}. When
 * inactive, rendering uses {@link CmykDisplay} exactly as before.
 * </p>
 */
public final class CmykPrintLut {

    /** Lattice size per axis (levels 0, 0.2, 0.4, 0.6, 0.8, 1.0). */
    private static final int N = 6;

    // 6^4 x 3 bytes, C-major order lut[c][m][y][k] -> (r,g,b); see class doc
    // for provenance (measured from our own printed chart, not an ICC profile).
    private static final String LUT_B64 =
            "////ysrKmZeXbGpnSUU/AAAA//zSzMenmpZ8bWlTSkUvAAAA//mozcSEmpNhbWc9SkUaAAAA//aBzsFlmpFHbGYnSUUAAAAA"
            + "//RYz8BCmo8qbGUASUQAAAAA//Mmz78Tmo4AbGUASUQAAAAA/dLlyae2mn6JcFddTzc4AAAA/8+/yqSYmXxxb1ZLTzcpAAAA"
            + "/8ybyaF6mHlZblQ3TjcVAAAA/8p6yZ9fl3dDbVMjTTYAAAAA/8hXyZ1Cl3YqbVIDTDYAAAAA/8c0yZ0ll3UNbFIATDYAAAAA"
            + "+6nMx4ijmmZ7ckVTUykyAAAA+6esx4WJmGRlcERCUikjAAAA/KSNxoJvlmFQb0IwUCgPAAAA/KJxxYBYlV89bUEeTygAAAAA"
            + "/KBVxX9BlF4pbUACTygAAAAA/KA7xH4rlF4VbEAATycAAAAA+Yi5xm6UmVJvczVLVRssAAAA+IadxWt9l1BccTQ7VBsdAAAA"
            + "+IOCw2hmlU1JbzIqUhsIAAAA+IFqwmZSlEw4bTEZURoAAAAA+H9TwWU/kksnbDAAUBoAAAAA934+wWQukkoXbDAAUBoAAAAA"
            + "92+sxVqJmENncylFVw8oAAAA9m2Sw1d0lkBWcSc2VQ8ZAAAA9mp6wlRglD5EbyUmUw4DAAAA9mhlwFJOkzw1bSQVUg0AAAAA"
            + "9WZRv1E9kjslbCMAUQ0AAAAA9GRAvlEvkTsYbCQAUQ4AAAAA9lagxEaAmDNgcxtAWAAkAAAA9VOJwkNtljFQcRkyVQAVAAAA"
            + "9FB0wEBaky5AbxciUwAAAAAA801hvz5KkiwxbRUSUgAAAAAA80tQvjw8kSskbRUAUgAAAAAA8klCvTwwkCsYbBUAUgAAAAAA"
            + "w+n8nbnHdoqUUWBlND4/AAAAyObRn7ald4h6Ul9SND4wAAAAzeSpobOFeIZiUl4+Mz4eAAAAz+GForFpeIVLUl0rMz4GAAAA"
            + "0eBho7BMeIMzUl0SMz4AAAAA0t89o64veIIaUVwAMj4AAAAAxsHjn5qzeXOGV09cPDE6AAAAyb++oJeWeXFwVk5LOzErAAAA"
            + "zL2coZV7eG9aVU05OjEbAAAAzbt9oZNjeG5GVEwoOTEFAAAAzrlfoZJLeG0zVEwTODEAAAAAzrhEoJE1d2wgU0sAODEAAAAA"
            + "yJ7LoX6ie155Wj9TQCQ0AAAAyZysoHyIel1lWT5DPyQnAAAAypqPoHpxeVtSVz0zPiQXAAAAy5l1n3lceFpBVjwkPCQDAAAA"
            + "y5dcn3hId1kxVTwSPCQAAAAAy5dGn3c4d1gjVTsAOyQAAAAAyIG5oWiTfE1vXDFMQxgwAAAAyICeoGZ9e0tdWjA+QhgjAAAA"
            + "yX6Fn2RpeUpMWS8vQBgUAAAAyX1unmNXeEk9Vy8hPxgAAAAAyXxZnmJGd0gvVi4RPhgAAAAAyXtHnWE4dkcjVi4APhgAAAAA"
            + "yWysoVeKfUBoXSZIRQ0tAAAAyGuUoFV2ez5YWyU6Qw0gAAAAyGp+n1RjeT1IWSQsQQ0RAAAAyGhqnlJTeDw6WCQeQA0AAAAA"
            + "yGdXnVFEdzstVyMPPw0AAAAAx2ZHnFE4djsjViMAPw0AAAAAyVihokeCfTNiXhtDRgArAAAAyFeLoEVwezJTXBo2RAAeAAAA"
            + "yFZ4nkRfeTBEWhkpQgAPAAAAx1RmnUNQeC84WBgcQQAAAAAAx1NVnEFCdy4sVxgOQAAAAAAAxlFHnEE4di4jVxgBQAEAAAAA"
            + "h9b5b6nDUn6QM1djGDc/AAAAj9PQc6ejVH14NFZRFzgwAAAAltGqd6WFVnthNVY+FzggAAAAms+JeKRsVnpNNVUtFjkMAAAA"
            + "nM5peaJSVnk5NFUZFjkAAAAAnc1MeqE7VnglNFQAFTkAAAAAkrPgd46xWWqEPUhbJSw7AAAAl7G9eYyVWmhuPEdKJCwtAAAA"
            + "mq+deop7WmdaO0Y6IiweAAAAnK6Ae4llWmZJO0YrIS0MAAAAnaxle4hQWWU3OkUaIS0AAAAAnatPeoc+WWQpOUUHIC0AAAAA"
            + "mZTKfHagXld4QzlULSA2AAAAm5KsfHSIXVZlQTlEKyApAAAAnZGQfXNyXVVTQDg1KSEbAAAAnpB4fXJfXFREPzgoKCEMAAAA"
            + "n49ifXFNXFM2PjcaJyEAAAAAno9PfHA/W1MqPTcMJiEAAAAAnXu4f2KTYUhvRi1OMRUzAAAAnXqef2F+YEdeRC0/LxUnAAAA"
            + "n3qHfmBrXkZOQiwyLRYaAAAAn3lyfl9aXkVAQSwlKxYKAAAAn3hefV5LXUU0QCsZKhYAAAAAn3dOfV4/XEQqQCsOKhYAAAAA"
            + "n2msgVSKYz1pSCRKMwsxAAAAn2mVgFN3YTxZRiQ8MQslAAAAoGiAf1JmXztKRCMvLwwYAAAAoGdtflFXXjo+QiMkLgwKAAAA"
            + "n2ZcflBJXjozQiIYLAwAAAAAn2VOfVA/XTkqQSIOLA0AAAAAoFmigkeDZDNjSRtGNQAvAAAAoFiOgUZyYjJVRxo6MgAjAAAA"
            + "oFh7gEViYDFHRRotMAEXAAAAoFdqf0RUXzA8RBkiLwEKAAAAn1ZafkNIXi8xQxkYLgIAAAAAn1VNfkM+Xi8qQhkPLgIAAAAA"
            + "SMj3P57AK3WNCU9hADI+AAAAWMbPSJyhMHR2DVBQADMwAAAAYsSqTpuFM3NhD08+ADQhAAAAaMKLUZltNXJOD08uADQPAAAA"
            + "a8BuU5hWNnE7D08dADQAAAAAbb9VU5dCNnArDk4IADQAAAAAY6jeUoWuO2OCIkJaCSc7AAAAaae8VYSUPGJtIUJKBSguAAAA"
            + "b6WdWIN8PWFaIEI6ASkfAAAAcqSDWYFnPWBKH0EsACkQAAAAc6NqWYBTPV86H0EdACkAAAAAc6JWWX9EPF4tHkEPACkAAAAA"
            + "cY3JXG+eRFJ3LTVTGRw3AAAAdIyrXW6IRFFlKzVFFh0rAAAAd4uRX21zRFBUKTU3Ex4eAAAAeYp7X2xhQ1BGKDQqEB4RAAAA"
            + "eYlmX2xRQ084JzQdDh4AAAAAeYhVX2tEQk8uJzQTDh8AAAAAeXe4Yl6SSkRuMipOIBI1AAAAenafYl1+SEReMCpBHRMpAAAA"
            + "fHaIY1xsSENPLio0GhQdAAAAfXV0Y1xcR0JCLSooGBQQAAAAfXRiYltORkI3LCkdFhQAAAAAfHNUYltERkIuKykUFRQAAAAA"
            + "fWetZlKKTTtpNSJLIwkzAAAAfWeWZVF4SzpaMyI+IAonAAAAf2eCZVBnSjpMMSIyHQscAAAAf2ZwZVBZSTlALyEnGwsQAAAA"
            + "fmVgZE9NSDg2LiEcGgoBAAAAfmRTZE9DSDguLiEVGQwAAAAAf1mjaEaDTzJkOBpIJgAxAAAAf1iPZ0ZzTTFWNRo7IgAmAAAA"
            + "gFl9ZkVkSzFJMxowIAEbAAAAgFhsZkVXSjA+MRkmHgIQAAAAf1ddZURLSjA1MBkcHQIDAAAAf1ZSZUNCSS8uMBkVHAMAAAAA"
            + "AL71AJa+AG6LAEpgAC4+AAAAALzOAJSgAG51AEtPADAwAAAAE7qrFJOFAG1gAEs+ADAhAAAAKLiNH5JuAGxOAEsvADERAAAA"
            + "MLdxJJBYAGw9AEseADEAAAAAM7VaJY9FAGsuAEsNADIAAAAAJqHdJn+tFF2AAD5ZACQ7AAAANp+7Ln6TGF1tAD5KACUuAAAA"
            + "QZ6eNH18G1xaAD47ACYhAAAAR52EN3xoHVtKAD4tACYSAAAASZxtN3tVHVs8AD0fACcAAAAASZtaN3pHHFowAD0TACcAAAAA"
            + "SofIPWudKk52EzJTABo4AAAAToerQGqHKk5lEDJFABssAAAAVIaSQmlzK01UDjI3ABwgAAAAVoV8Q2liK0xHCzIrABwTAAAA"
            + "VoRpQ2hTKkw6CTIgABwEAAAAVoRZQ2hHKkwxBzIWAB0AAAAAWHS4SFuSNEJuHyhODBA2AAAAWnOfSVt+M0FeHChBBRIqAAAA"
            + "XXOJSlptMkFQGSg1ARIfAAAAXnJ2SlleMkBEFygqABMTAAAAXnFlSllQMUA5FSgfABMFAAAAXXFYSlhGMUAxFCgYABMAAAAA"
            + "XmWtTlCKOTlpJCBLEgc0AAAAYGWXTVB4NzlaISE/DQkpAAAAYmWDTk9oNjhNHiEzCAoeAAAAYmVyTk9bNjhCHCEpBAoUAAAA"
            + "YWRiTU5PNTc4GyAfAAoGAAAAYGNXTU5GNDcwGSAYAAsAAAAAY1mkUkaDPTFkKBlIFwAzAAAAY1iQUUZzOjFXJBk9EQAoAAAA"
            + "ZVl+UUVlOTFLIhoyDQIeAAAAZVhuUEVYODBAHxkoCgIUAAAAZFhgUERNNzA3HhkfBgIIAAAAYlZWT0RFNy8wHRkZBgMBAAAA"
            + "ALf0AJC9AGmKAEZfACs9AAAAALTNAI6fAGl1AEdOAC0wAAAAALOrAI2FAGhgAEc+AC4hAAAAALGOAIxuAGhPAEcvAC8SAAAA"
            + "AK9zAItZAGc+AEcfAC8AAAAAAK1eAIpIAGcwAEgRADAAAAAAAJvcAHqrAFl/ADpYACE7AAAAAJq7AHmSAFlsADtKACMuAAAA"
            + "AJmeAHl8AFhaADs7ACQhAAAAAJiFAHhoAFhLADsuACQUAAAAAJdvAHdXAFc9ADshACUCAAAAAJZdAHZJAFcyADsVACUAAAAA"
            + "CITHD2ecAEt1AC9TABg4AAAAGoOrF2eHAEtkADBFABotAAAAKIOSH2ZzAkpVADA4ABshAAAALIJ9ImZjA0pHADAsABsVAAAA"
            + "LoFrI2VUAko7ADAhABsHAAAALYBcImVJAEkyADAYABsAAAAAM3G4K1iRGz9tAiZOAA42AAAAN3GfLVh+GUBfACdCABArAAAA"
            + "PHGKL1htGT9QACc2ABIgAAAAPnB3MFhfGT9EACcrABIWAAAAPW9nMVdSGD86ACchABIJAAAAPG9bMFdIFz4yACcaABMAAAAA"
            + "QGSuNk6KJTdpEB9LAAU1AAAAQmSXNk55IjhbCCBAAAgrAAAARWSEN05pITdOBCA0AAogAAAARWRzN05cITdDACAqAAoWAAAA"
            + "RGNkNk1QIDc5ACAhAAoKAAAAQmJaNk1HHzYyACAaAAsCAAAASFmlPEWEKzBkFxhJBAA0AAAASViQO0V0JzBYEBk+AAAqAAAA"
            + "S1l+PEVmJjBMDBkzAAIgAAAASlhvO0VZJTBBCBkqAAMWAAAASFdiOkROJDA4BRkhAAMMAAAARlZYOkRHJC8yBBkbAAQFAAAA";

    private static final byte[] LUT = java.util.Base64.getDecoder().decode(LUT_B64);

    /**
     * A CMYK→RGB lattice with its own inverse cache. The default instance is
     * the measured Acrobat print lattice; per-document instances are sampled
     * through the document's /OutputIntents ICC profile (PDF/X: Acrobat's
     * print pipeline converts DeviceCMYK through the output condition, e.g.
     * FOGRA27 prints (0.3,0,0,0.3) as rgb(146,178,193) where the default
     * US-press lattice gives (117,154,172) — corpus 35126).
     */
    private static final class Lattice {
        final byte[] lut;
        final java.util.concurrent.ConcurrentHashMap<Integer, Integer> inverseCache =
                new java.util.concurrent.ConcurrentHashMap<>();

        Lattice(byte[] lut) {
            this.lut = lut;
        }
    }

    private static final Lattice DEFAULT = new Lattice(LUT);
    /** Per-render override (thread-local: compare harnesses render concurrently). */
    private static final ThreadLocal<Lattice> CURRENT = new ThreadLocal<>();
    /** Output-intent lattices keyed by a hash of the ICC profile bytes. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Lattice> PROFILE_LATTICES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Lattice current() {
        Lattice l = CURRENT.get();
        return l != null ? l : DEFAULT;
    }

    /**
     * Installs a per-document CMYK output-intent transform for the current
     * thread's render: the 6^4 lattice is sampled through the given ICC
     * profile (JDK CMM), so DeviceCMYK conversion — and the ink-space
     * compositing built on it — follows the document's declared print
     * condition instead of the default measured lattice. No-op for non-CMYK
     * or unparseable profiles.
     *
     * @param iccProfileBytes the /DestOutputProfile stream data (decoded)
     */
    public static void setOutputIntent(byte[] iccProfileBytes) {
        if (iccProfileBytes == null || iccProfileBytes.length < 128) return;
        try {
            String key = java.util.Arrays.hashCode(iccProfileBytes) + ":" + iccProfileBytes.length;
            Lattice lat = PROFILE_LATTICES.get(key);
            if (lat == null) {
                java.awt.color.ICC_Profile profile =
                        java.awt.color.ICC_Profile.getInstance(iccProfileBytes);
                if (profile.getNumComponents() != 4) return;
                java.awt.color.ICC_ColorSpace cs = new java.awt.color.ICC_ColorSpace(profile);
                byte[] lut = new byte[N * N * N * N * 3];
                float[] in = new float[4];
                for (int ic = 0; ic < N; ic++) {
                    for (int im = 0; im < N; im++) {
                        for (int iy = 0; iy < N; iy++) {
                            for (int ik = 0; ik < N; ik++) {
                                in[0] = ic / (float) (N - 1);
                                in[1] = im / (float) (N - 1);
                                in[2] = iy / (float) (N - 1);
                                in[3] = ik / (float) (N - 1);
                                float[] rgb = cs.toRGB(in);
                                int off = 3 * (((ic * N + im) * N + iy) * N + ik);
                                lut[off] = (byte) clamp255(Math.round(rgb[0] * 255));
                                lut[off + 1] = (byte) clamp255(Math.round(rgb[1] * 255));
                                lut[off + 2] = (byte) clamp255(Math.round(rgb[2] * 255));
                            }
                        }
                    }
                }
                lat = new Lattice(lut);
                PROFILE_LATTICES.putIfAbsent(key, lat);
            }
            CURRENT.set(lat);
        } catch (Exception e) {
            // unparseable profile — keep the default lattice
        }
    }

    /** Removes the per-thread output-intent override (call in finally). */
    public static void clearOutputIntent() {
        CURRENT.remove();
    }

    private CmykPrintLut() {}

    /**
     * Whether the print-parity CMYK LUT should replace {@link CmykDisplay}.
     *
     * @return true when {@code render.acrobatPrintParity} is set and the LUT
     *         has not been disabled via {@code render.printParityCmykLut=false}
     */
    public static boolean active() {
        return Boolean.getBoolean("render.acrobatPrintParity")
                && !"false".equals(System.getProperty("render.printParityCmykLut"));
    }

    /**
     * Converts CMYK components (each 0..1) to a packed ARGB int by
     * quadrilinear interpolation over the measured print lattice.
     *
     * @param c cyan (0..1)
     * @param m magenta (0..1)
     * @param y yellow (0..1)
     * @param k black (0..1)
     * @return packed ARGB int (alpha=0xFF)
     */
    public static int toRGBInt(double c, double m, double y, double k) {
        double[] rgb = toRGB(c, m, y, k);
        return 0xFF000000
                | (clamp255((int) Math.round(rgb[0])) << 16)
                | (clamp255((int) Math.round(rgb[1])) << 8)
                | clamp255((int) Math.round(rgb[2]));
    }

    /**
     * Quadrilinear interpolation over the measured lattice, unrounded.
     *
     * @param c cyan (0..1)
     * @param m magenta (0..1)
     * @param y yellow (0..1)
     * @param k black (0..1)
     * @return {r, g, b} in 0..255 (double precision)
     */
    public static double[] toRGB(double c, double m, double y, double k) {
        byte[] lut = current().lut;
        double sc = clamp01(c) * (N - 1), sm = clamp01(m) * (N - 1),
               sy = clamp01(y) * (N - 1), sk = clamp01(k) * (N - 1);
        int ic = cell(sc), im = cell(sm), iy = cell(sy), ik = cell(sk);
        double fc = sc - ic, fm = sm - im, fy = sy - iy, fk = sk - ik;
        double r = 0, g = 0, b = 0;
        for (int dc = 0; dc <= 1; dc++) {
            double wc = dc == 1 ? fc : 1 - fc;
            if (wc == 0) continue;
            for (int dm = 0; dm <= 1; dm++) {
                double wm = wc * (dm == 1 ? fm : 1 - fm);
                if (wm == 0) continue;
                for (int dy = 0; dy <= 1; dy++) {
                    double wy = wm * (dy == 1 ? fy : 1 - fy);
                    if (wy == 0) continue;
                    for (int dk = 0; dk <= 1; dk++) {
                        double w = wy * (dk == 1 ? fk : 1 - fk);
                        if (w == 0) continue;
                        int off = 3 * ((((ic + dc) * N + (im + dm)) * N + (iy + dy)) * N + (ik + dk));
                        r += w * (lut[off] & 0xFF);
                        g += w * (lut[off + 1] & 0xFF);
                        b += w * (lut[off + 2] & 0xFF);
                    }
                }
            }
        }
        return new double[]{r, g, b};
    }

    /**
     * Whether alpha compositing should be performed in ink (CMYK) space under
     * print-parity mode. Acrobat's print pipeline flattens transparency in the
     * page blending colorspace (DeviceCMYK for CMYK-authored artwork) and only
     * then converts to RGB; compositing the already-converted RGB values gives
     * visibly lighter results for ink-heavy overlays (e.g. a rich-black panel
     * at /ca 0.35 renders ~rgb(160) instead of Acrobat's ~rgb(115)).
     *
     * @return true when {@link #active()} and not disabled via
     *         {@code -Drender.printParityInkBlend=false}
     */
    public static boolean inkBlendActive() {
        return active() && !"false".equals(System.getProperty("render.printParityInkBlend"));
    }

    /** Cache cap — beyond this, inversions are computed but not stored. */
    private static final int INV_CACHE_MAX = 1 << 18;

    /**
     * Approximate inverse of {@link #toRGB}: finds CMYK ink values whose
     * lattice interpolation reproduces the given RGB. The inverse is not
     * unique (metamers); among near-equal solutions the seed prefers the
     * highest K (print blacks are K-heavy), then coordinate descent refines.
     *
     * @param rgb packed RGB (alpha ignored)
     * @return {c, m, y, k} each 0..1
     */
    public static double[] inverse(int rgb) {
        Lattice lat = current();
        int key = rgb & 0xFFFFFF;
        Integer packed = lat.inverseCache.get(key);
        if (packed == null) {
            double[] cmyk = solveInverse((key >> 16) & 0xFF, (key >> 8) & 0xFF, key & 0xFF);
            packed = ((int) Math.round(cmyk[0] * 255) << 24)
                    | ((int) Math.round(cmyk[1] * 255) << 16)
                    | ((int) Math.round(cmyk[2] * 255) << 8)
                    | (int) Math.round(cmyk[3] * 255);
            if (lat.inverseCache.size() < INV_CACHE_MAX) {
                lat.inverseCache.put(key, packed);
            }
        }
        return new double[]{
                ((packed >> 24) & 0xFF) / 255.0, ((packed >> 16) & 0xFF) / 255.0,
                ((packed >> 8) & 0xFF) / 255.0, (packed & 0xFF) / 255.0};
    }

    private static double[] solveInverse(int r, int g, int b) {
        byte[] lut = current().lut;
        // Coarse seed: scan the 1296 lattice nodes; among nodes within a small
        // error margin of the best, prefer the highest K (GCR-heavy metamer).
        double bestErr = Double.MAX_VALUE;
        for (int i = 0; i < lut.length; i += 3) {
            double e = sq((lut[i] & 0xFF) - r) + sq((lut[i + 1] & 0xFF) - g) + sq((lut[i + 2] & 0xFF) - b);
            if (e < bestErr) bestErr = e;
        }
        int seed = -1, seedK = -1;
        double margin = bestErr + 100;
        for (int i = 0; i < lut.length; i += 3) {
            double e = sq((lut[i] & 0xFF) - r) + sq((lut[i + 1] & 0xFF) - g) + sq((lut[i + 2] & 0xFF) - b);
            if (e <= margin) {
                int node = i / 3, k = node % N;
                if (k > seedK) { seedK = k; seed = node; }
            }
        }
        double[] x = {
                (seed / (N * N * N)) / (double) (N - 1),
                (seed / (N * N) % N) / (double) (N - 1),
                (seed / N % N) / (double) (N - 1),
                (seed % N) / (double) (N - 1)};
        double ex = invErr(x, r, g, b);
        for (double step : new double[]{0.1, 0.05, 0.02, 0.01}) {
            for (int pass = 0; pass < 8; pass++) {
                boolean improved = false;
                for (int ch = 0; ch < 4; ch++) {
                    for (int dir = -1; dir <= 1; dir += 2) {
                        double old = x[ch];
                        x[ch] = clamp01(old + dir * step);
                        double e = invErr(x, r, g, b);
                        if (e < ex - 1e-9) { ex = e; improved = true; } else { x[ch] = old; }
                    }
                }
                if (!improved) break;
            }
        }
        return x;
    }

    private static double invErr(double[] cmyk, int r, int g, int b) {
        double[] p = toRGB(cmyk[0], cmyk[1], cmyk[2], cmyk[3]);
        return sq(p[0] - r) + sq(p[1] - g) + sq(p[2] - b);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static int cell(double scaled) {
        int i = (int) scaled;
        return i >= N - 1 ? N - 2 : i;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
