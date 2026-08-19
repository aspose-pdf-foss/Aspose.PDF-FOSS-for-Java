package org.aspose.pdf.engine.pattern;

import org.aspose.pdf.engine.function.PdfFunction;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.parser.PDFParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Free-form Gouraud-shaded triangle mesh — ShadingType 4
 * (ISO 32000-1:2008, §8.7.4.5.4).
 *
 * <p>The shading stream is a sequence of vertex records — flag
 * ({@code /BitsPerFlag}), x and y ({@code /BitsPerCoordinate}) and either the
 * colour components or a single parametric value for {@code /Function}
 * ({@code /BitsPerComponent} each), every record padded to a byte boundary.
 * Values map linearly through the corresponding {@code /Decode} ranges.
 * Flag 0 starts a new triangle (the next two vertices shall carry flag 0
 * too); flags 1 and 2 continue a strip/fan off the previous triangle.</p>
 *
 * <p>Parsing is prefix-lenient the way Acrobat behaves: triangles decoded
 * before the first structural error are kept, everything after is dropped.
 * A mesh whose very first record is malformed therefore paints NOTHING —
 * corpus PDFJAVA-39739 fills its trifold panels with such degenerate meshes
 * (flags 0,1,2 with no complete first triangle) and Acrobat prints the
 * panels white, not with a fallback colour.</p>
 */
public final class FreeFormGouraudShading extends Shading {

    private static final Logger LOG = Logger.getLogger(FreeFormGouraudShading.class.getName());

    /** One vertex: device-independent shading-space x, y and colour components. */
    static final class Vertex {
        final double x;
        final double y;
        final double[] comps;
        Vertex(double x, double y, double[] comps) {
            this.x = x;
            this.y = y;
            this.comps = comps;
        }
    }

    /** Decoded triangles, each three vertices with per-vertex colours. */
    private final List<Vertex[]> triangles = new ArrayList<>();

    /**
     * Creates a FreeFormGouraudShading from its dictionary and decodes the
     * vertex stream.
     *
     * @param dict   the shading dictionary
     * @param parser the PDF parser
     * @throws IOException if parsing fails
     */
    public FreeFormGouraudShading(PdfDictionary dict, PDFParser parser) throws IOException {
        super(dict, parser);
        try {
            parseMesh(dict, parser);
        } catch (Exception e) {
            LOG.fine(() -> "Gouraud mesh parse failed: " + e);
        }
    }

    private void parseMesh(PdfDictionary dict, PDFParser parser) throws IOException {
        if (!(dict instanceof PdfStream)) return;
        byte[] data = ((PdfStream) dict).getDecodedData();
        if (data == null || data.length == 0) return;

        int bpFlag = dict.getInt("BitsPerFlag", 8);
        int bpCoord = dict.getInt("BitsPerCoordinate", 16);
        int bpComp = dict.getInt("BitsPerComponent", 8);
        double[] decode = getNumberArray(dict, "Decode");
        PdfFunction function = PdfFunction.parse(dict.get("Function"), parser);
        int nComps = function != null ? 1
                : (colorSpace != null ? colorSpace.getNumberOfComponents() : 3);
        if (decode == null || decode.length < 4 + 2 * nComps) return;

        BitReader in = new BitReader(data);
        Vertex va = null;
        Vertex vb = null;
        Vertex vc = null;
        while (true) {
            int[] flagBox = new int[1];
            Vertex v = readVertex(in, bpFlag, bpCoord, bpComp, nComps, decode, function, flagBox);
            if (v == null) break; // clean end of data or truncated record
            int flag = flagBox[0];
            if (flag == 0) {
                // New triangle: this vertex plus the next two. §8.7.4.5.4
                // says the completing vertices shall carry flag 0 too, but
                // Acrobat IGNORES their flags (corpus PDFJAVA-39739 emits
                // 0,1,2,... and Acrobat still paints the panel gradients) —
                // only a truncated record aborts.
                Vertex v2 = readVertex(in, bpFlag, bpCoord, bpComp, nComps, decode, function, flagBox);
                if (v2 == null) return;
                Vertex v3 = readVertex(in, bpFlag, bpCoord, bpComp, nComps, decode, function, flagBox);
                if (v3 == null) return;
                va = v;
                vb = v2;
                vc = v3;
            } else if (flag == 1 && vc != null) {
                va = vb;
                vb = vc;
                vc = v;
            } else if (flag == 2 && vc != null) {
                vb = vc;
                vc = v;
            } else {
                return; // flag 1/2 with no previous triangle, or flag > 2
            }
            triangles.add(new Vertex[]{va, vb, vc});
        }
    }

    private Vertex readVertex(BitReader in, int bpFlag, int bpCoord, int bpComp,
                              int nComps, double[] decode, PdfFunction function,
                              int[] flagBox) {
        in.alignToByte(); // every vertex record starts on a byte boundary
        if (!in.has(bpFlag + 2L * bpCoord + (long) nComps * bpComp)) return null;
        flagBox[0] = (int) in.read(bpFlag);
        double x = decodeValue(in.read(bpCoord), bpCoord, decode[0], decode[1]);
        double y = decodeValue(in.read(bpCoord), bpCoord, decode[2], decode[3]);
        double[] comps = new double[nComps];
        for (int i = 0; i < nComps; i++) {
            comps[i] = decodeValue(in.read(bpComp), bpComp,
                    decode[4 + 2 * i], decode[5 + 2 * i]);
        }
        if (function != null) {
            try {
                double[] out = function.evaluate(new double[]{comps[0]});
                if (out != null) comps = out;
            } catch (Exception e) {
                LOG.fine(() -> "Mesh colour function failed: " + e);
            }
        }
        return new Vertex(x, y, comps);
    }

    private static double decodeValue(long raw, int bits, double dMin, double dMax) {
        double max = Math.pow(2, bits) - 1;
        return dMin + (raw / max) * (dMax - dMin);
    }

    /** MSB-first bit reader over the decoded shading stream. */
    private static final class BitReader {
        private final byte[] data;
        private long bitPos;

        BitReader(byte[] data) {
            this.data = data;
        }

        boolean has(long bits) {
            return bitPos + bits <= (long) data.length * 8;
        }

        void alignToByte() {
            if ((bitPos & 7) != 0) bitPos = (bitPos + 7) & ~7L;
        }

        long read(int bits) {
            long v = 0;
            for (int i = 0; i < bits; i++) {
                int byteIdx = (int) (bitPos >> 3);
                int bitIdx = 7 - (int) (bitPos & 7);
                v = (v << 1) | ((data[byteIdx] >> bitIdx) & 1);
                bitPos++;
            }
            return v;
        }
    }

    /** Returns the decoded triangles (empty for a malformed mesh). */
    List<Vertex[]> getTriangles() {
        return triangles;
    }

    @Override
    public int getShadingType() { return 4; }

    /**
     * Point query used by the generic sampling path: returns the
     * barycentric-interpolated colour of the first triangle containing the
     * point, or {@code null} outside the mesh (nothing painted there).
     */
    @Override
    public double[] getColorAt(double x, double y) {
        for (Vertex[] t : triangles) {
            double[] bary = barycentric(t, x, y);
            if (bary != null) {
                int n = t[0].comps.length;
                double[] c = new double[n];
                for (int i = 0; i < n; i++) {
                    c[i] = bary[0] * t[0].comps[i] + bary[1] * t[1].comps[i]
                            + bary[2] * t[2].comps[i];
                }
                return c;
            }
        }
        return null;
    }

    /** Barycentric coordinates of (x,y) in triangle t, or null if outside. */
    static double[] barycentric(Vertex[] t, double x, double y) {
        double x1 = t[0].x, y1 = t[0].y, x2 = t[1].x, y2 = t[1].y, x3 = t[2].x, y3 = t[2].y;
        double det = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3);
        if (det == 0) return null;
        double l1 = ((y2 - y3) * (x - x3) + (x3 - x2) * (y - y3)) / det;
        double l2 = ((y3 - y1) * (x - x3) + (x1 - x3) * (y - y3)) / det;
        double l3 = 1 - l1 - l2;
        final double eps = -1e-9;
        if (l1 < eps || l2 < eps || l3 < eps) return null;
        return new double[]{l1, l2, l3};
    }
}
