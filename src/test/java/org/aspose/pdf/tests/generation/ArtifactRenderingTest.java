package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Artifact;
import org.aspose.pdf.BackgroundArtifact;
import org.aspose.pdf.Color;
import org.aspose.pdf.Document;
import org.aspose.pdf.Operator;
import org.aspose.pdf.Page;
import org.aspose.pdf.WatermarkArtifact;
import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.engine.cos.COSStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug G — newly-constructed {@link BackgroundArtifact} and
 * {@link WatermarkArtifact} must synthesise drawing operators from their
 * high-level properties when no explicit operator list is provided. Before
 * the fix they round-tripped an empty {@code /Artifact BMC ... EMC} marker
 * with no visible content.
 */
class ArtifactRenderingTest {

    @TempDir Path tempDir;

    private static String pageContentText(Page page) throws IOException {
        COSBase contents = page.getCOSDictionary().get(COSName.of("Contents"));
        if (contents instanceof COSObjectReference) {
            contents = ((COSObjectReference) contents).dereference();
        }
        StringBuilder all = new StringBuilder();
        if (contents instanceof COSStream) {
            all.append(new String(((COSStream) contents).getDecodedData(), StandardCharsets.ISO_8859_1));
        } else if (contents instanceof COSArray) {
            COSArray arr = (COSArray) contents;
            for (int i = 0; i < arr.size(); i++) {
                COSBase e = arr.get(i);
                if (e instanceof COSObjectReference) e = ((COSObjectReference) e).dereference();
                if (e instanceof COSStream) {
                    all.append(new String(((COSStream) e).getDecodedData(), StandardCharsets.ISO_8859_1));
                    all.append('\n');
                }
            }
        }
        return all.toString();
    }

    private static COSDictionary resolveDict(COSBase v) throws IOException {
        if (v instanceof COSObjectReference) v = ((COSObjectReference) v).dereference();
        return v instanceof COSDictionary ? (COSDictionary) v : null;
    }

    @Test
    @DisplayName("BackgroundArtifact with colour emits a rectangle-fill inside BMC/EMC")
    void backgroundArtifact_withColor_emitsRectangleFill() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            page.getArtifacts().add(new BackgroundArtifact(Color.fromRgb(0.5, 0.6, 0.7)));
            String cs = pageContentText(page);
            assertTrue(cs.contains("/Artifact BMC"),
                    "must wrap in /Artifact BMC ... EMC; got: " + cs);
            assertTrue(cs.contains(" re") && cs.contains(" f"),
                    "must contain a rectangle-fill (re + f)");
            assertTrue(cs.contains(" rg"),
                    "must set non-stroking RGB colour via rg");
        }
    }

    @Test
    @DisplayName("BackgroundArtifact with no colour emits an empty BMC/EMC (back-compat)")
    void backgroundArtifact_noColor_emitsEmptyBMC() throws IOException {
        // The no-synthesis path appends to the in-memory OperatorCollection,
        // which is only serialised to /Contents at save — so round-trip.
        Path out = tempDir.resolve("bg-empty.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            page.getArtifacts().add(new BackgroundArtifact());
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            String cs = pageContentText(r.getPages().get(1));
            assertTrue(cs.contains("Artifact") && cs.contains("BMC") && cs.contains("EMC"),
                    "hollow back-compat BMC/EMC pair expected; got: " + cs);
        }
    }

    @Test
    @DisplayName("WatermarkArtifact with text emits BDC ... BT (Tj) ET ... EMC")
    void watermarkArtifact_withText_emitsTextOperators() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            WatermarkArtifact wm = new WatermarkArtifact("TEST");
            wm.setFont("Helvetica-Bold", 48);
            wm.setRotation(45);
            wm.setOpacity(0.5);
            page.getArtifacts().add(wm);
            String cs = pageContentText(page);
            assertTrue(cs.contains("/Artifact"),
                    "must wrap in /Artifact marker (BDC or BMC)");
            assertTrue(cs.contains("BT") && cs.contains("ET"),
                    "must contain BT...ET text block");
            assertTrue(cs.contains("(TEST) Tj"),
                    "must show the literal watermark text via Tj");
        }
    }

    @Test
    @DisplayName("WatermarkArtifact registers its font in the page /Resources/Font")
    void watermarkArtifact_registersFontInPageResources() throws IOException {
        Path out = tempDir.resolve("wm-font.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            WatermarkArtifact wm = new WatermarkArtifact("X");
            wm.setFont("Helvetica-Bold", 32);
            page.getArtifacts().add(wm);
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            COSDictionary fonts = r.getPages().get(1).ensureResources().getFonts();
            assertNotNull(fonts);
            boolean foundBold = false;
            for (COSName k : fonts.keySet()) {
                COSDictionary f = resolveDict(fonts.get(k));
                if (f != null && "Helvetica-Bold".equals(f.getNameAsString("BaseFont"))) {
                    foundBold = true;
                    assertEquals("WinAnsiEncoding", f.getNameAsString("Encoding"));
                    break;
                }
            }
            assertTrue(foundBold, "Helvetica-Bold must be registered with WinAnsiEncoding");
        }
    }

    @Test
    @DisplayName("WatermarkArtifact with opacity<1 registers /ExtGState /ca /CA")
    void watermarkArtifact_withOpacity_registersExtGState() throws IOException {
        Path out = tempDir.resolve("wm-ext.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            WatermarkArtifact wm = new WatermarkArtifact("X");
            wm.setOpacity(0.25);
            page.getArtifacts().add(wm);
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            COSDictionary ext = r.getPages().get(1).ensureResources().getExtGState();
            assertNotNull(ext, "/ExtGState must be present on page");
            boolean found = false;
            for (COSName k : ext.keySet()) {
                COSDictionary gs = resolveDict(ext.get(k));
                if (gs != null && Math.abs(gs.getFloat("ca", -1f) - 0.25) < 0.001) {
                    assertEquals(0.25, gs.getFloat("CA", -1f), 0.001);
                    found = true;
                    break;
                }
            }
            assertTrue(found, "ExtGState entry with /ca 0.25 must exist");
        }
    }

    @Test
    @DisplayName("Parsed artifact with explicit contents bypasses synthesis")
    void parsedArtifact_explicitContents_unchanged() throws IOException {
        // Build an artifact whose operators were populated as if read from a
        // source PDF. The page must emit them verbatim instead of synthesising
        // anything from the high-level properties (which are unset).
        Path out = tempDir.resolve("parsed.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            Artifact a = new Artifact();
            List<Operator> ops = new ArrayList<>();
            ops.add(new Operator("q"));
            ops.add(new Operator("Q"));
            a.setContents(ops);
            page.getArtifacts().add(a);
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            String cs = pageContentText(r.getPages().get(1));
            // q and Q are present; no rg/re/f from the synthesis path.
            assertTrue(cs.contains("q"));
            assertTrue(cs.contains("Q"));
            assertFalse(cs.contains(" re") && cs.contains(" f"),
                    "synthesis path must NOT fire for artifacts with explicit operators");
        }
    }
}
