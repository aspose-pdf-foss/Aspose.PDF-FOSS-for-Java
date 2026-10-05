package org.aspose.pdf.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.PdfSaveOptions;
import org.aspose.pdf.text.TextAbsorber;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Linearize -> modify -> save round-trip validity.
 *
 * <p>Scenario requested: take a PDF, linearize and re-save, then modify it and
 * save again, verifying the document stays a VALID PDF at every stage (and,
 * where applicable, still linearized).</p>
 *
 * <p>"Valid" here means: the byte stream carries the PDF header + {@code startxref}
 * + {@code %%EOF}; it re-opens through the public {@link Document} API with a live
 * catalog and trailer; the page count matches; and the authored text of every page
 * survives extraction (so content, not just structure, round-trips). See
 * {@link #assertValidPdf}.</p>
 *
 * <p>Note on expectations: a plain modify+save of a linearized file appends an
 * incremental update (or fully rewrites), which legitimately BREAKS Fast Web View
 * -- {@code /L} no longer matches the file length, so {@link Document#isLinearized()}
 * may report false afterwards. That is correct behaviour; these tests assert
 * VALIDITY after an edit and only assert re-linearization when the edit is saved
 * with {@code setLinearize(true)} again.</p>
 *
 * <p>The project forbids reading corpus files from {@code pdf/src/test}; every
 * source document here is built in memory through the public API.</p>
 */
class LinearizeModifyRoundTripTest {

    @TempDir
    Path tempDir;

    // ────────────────────────────────────────────────────────────────
    //  Core scenario (the one requested)
    // ────────────────────────────────────────────────────────────────

    /** linearize -> modify -> save -> modify -> save, valid at every stage. */
    @Test
    void linearizeThenModifyTwiceStaysValid() throws Exception {
        File src = buildSourceDoc("src.pdf", 3);

        // Stage 1: linearize.
        File lin1 = tempDir.resolve("lin1.pdf").toFile();
        try (Document doc = new Document(src.getAbsolutePath())) {
            doc.save(lin1.getAbsolutePath(), new PdfSaveOptions().setLinearize(true));
        }
        assertValidPdf(lin1, 3, "LINTESTPAGE1", "LINTESTPAGE2", "LINTESTPAGE3");
        try (Document check = new Document(lin1.getAbsolutePath())) {
            assertTrue(check.isLinearized(), "Stage 1 output must be linearized");
        }

        // Stage 2: modify (append a page) and save normally.
        File mod1 = tempDir.resolve("mod1.pdf").toFile();
        try (Document doc = new Document(lin1.getAbsolutePath())) {
            addTextPage(doc, 4);
            doc.save(mod1.getAbsolutePath());
        }
        assertValidPdf(mod1, 4, "LINTESTPAGE1", "LINTESTPAGE3", "LINTESTPAGE4");

        // Stage 3: modify again (metadata + another page) and save.
        File mod2 = tempDir.resolve("mod2.pdf").toFile();
        try (Document doc = new Document(mod1.getAbsolutePath())) {
            doc.getInfo().setTitle("edited-title");
            addTextPage(doc, 5);
            doc.save(mod2.getAbsolutePath());
        }
        assertValidPdf(mod2, 5, "LINTESTPAGE1", "LINTESTPAGE4", "LINTESTPAGE5");
        try (Document check = new Document(mod2.getAbsolutePath())) {
            assertEquals("edited-title", check.getInfo().getTitle(),
                    "Edited metadata must survive the final save");
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Save-mode variants for the modify step
    // ────────────────────────────────────────────────────────────────

    /** Default save() after editing a linearized file (engine picks incremental/full). */
    @Test
    void modifyLinearizedThenDefaultSaveStaysValid() throws Exception {
        File lin = linearize(buildSourceDoc("a-src.pdf", 2), "a-lin.pdf");
        assertValidPdf(lin, 2, "LINTESTPAGE1", "LINTESTPAGE2");

        File out = tempDir.resolve("a-mod.pdf").toFile();
        try (Document doc = new Document(lin.getAbsolutePath())) {
            addTextPage(doc, 3);
            doc.save(out.getAbsolutePath());
        }
        assertValidPdf(out, 3, "LINTESTPAGE1", "LINTESTPAGE2", "LINTESTPAGE3");
    }

    /** Forced full rewrite after editing a linearized file (object renumbering path). */
    @Test
    void modifyLinearizedThenFullRewriteStaysValid() throws Exception {
        File lin = linearize(buildSourceDoc("b-src.pdf", 2), "b-lin.pdf");

        File out = tempDir.resolve("b-mod.pdf").toFile();
        try (Document doc = new Document(lin.getAbsolutePath())) {
            addTextPage(doc, 3);
            doc.requestFullRewrite();
            doc.save(out.getAbsolutePath());
        }
        assertValidPdf(out, 3, "LINTESTPAGE1", "LINTESTPAGE2", "LINTESTPAGE3");
    }

    /** Re-linearize after editing: output must be valid AND linearized again. */
    @Test
    void modifyLinearizedThenReLinearizeStaysValidAndLinearized() throws Exception {
        File lin = linearize(buildSourceDoc("c-src.pdf", 2), "c-lin.pdf");

        File out = tempDir.resolve("c-relin.pdf").toFile();
        try (Document doc = new Document(lin.getAbsolutePath())) {
            addTextPage(doc, 3);
            doc.save(out.getAbsolutePath(), new PdfSaveOptions().setLinearize(true));
        }
        assertValidPdf(out, 3, "LINTESTPAGE1", "LINTESTPAGE2", "LINTESTPAGE3");
        try (Document check = new Document(out.getAbsolutePath())) {
            assertTrue(check.isLinearized(), "Re-linearized output must be linearized");
        }
    }

    /** Several linearize -> modify cycles must not accumulate corruption. */
    @Test
    void repeatedLinearizeModifyCyclesStayValid() throws Exception {
        File current = buildSourceDoc("cyc-src.pdf", 2);
        int pages = 2;
        for (int cycle = 1; cycle <= 3; cycle++) {
            File lin = tempDir.resolve("cyc-lin-" + cycle + ".pdf").toFile();
            try (Document doc = new Document(current.getAbsolutePath())) {
                doc.save(lin.getAbsolutePath(), new PdfSaveOptions().setLinearize(true));
            }
            assertValidPdf(lin, pages);
            try (Document check = new Document(lin.getAbsolutePath())) {
                assertTrue(check.isLinearized(), "Cycle " + cycle + " must be linearized");
            }

            File mod = tempDir.resolve("cyc-mod-" + cycle + ".pdf").toFile();
            try (Document doc = new Document(lin.getAbsolutePath())) {
                addTextPage(doc, ++pages);
                doc.save(mod.getAbsolutePath());
            }
            assertValidPdf(mod, pages);
            current = mod;
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────

    /** Builds a fresh {@code n}-page PDF file, each page carrying "LINTESTPAGE&lt;i&gt;".
     *  A new in-memory document cannot be linearized directly, so it is saved once
     *  (normally) and returned as a file the tests then parse and linearize. */
    private File buildSourceDoc(String name, int pages) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (Document doc = new Document()) {
            for (int i = 1; i <= pages; i++) {
                addTextPage(doc, i);
            }
            doc.save(file.getAbsolutePath());
        }
        return file;
    }

    /** Appends one page tagged with a distinctive, whitespace-free token. */
    private void addTextPage(Document doc, int index) throws Exception {
        Page page = doc.getPages().add();
        page.getParagraphs().add(new TextFragment("LINTESTPAGE" + index));
    }

    /** Linearizes {@code src} into {@code outName} and returns the output file. */
    private File linearize(File src, String outName) throws Exception {
        File out = tempDir.resolve(outName).toFile();
        try (Document doc = new Document(src.getAbsolutePath())) {
            doc.save(out.getAbsolutePath(), new PdfSaveOptions().setLinearize(true));
        }
        return out;
    }

    /** Asserts {@code file} is a valid PDF: PDF header + startxref + %%EOF, re-opens
     *  with a live catalog/trailer, has {@code expectedPages} pages each with a rect,
     *  and its extracted text contains every {@code expectedText} token. */
    private void assertValidPdf(File file, int expectedPages, String... expectedText)
            throws Exception {
        assertTrue(file.exists() && file.length() > 0, file.getName() + " must be non-empty");

        byte[] bytes = Files.readAllBytes(file.toPath());
        String head = new String(bytes, 0, Math.min(16, bytes.length),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(head.startsWith("%PDF-"), file.getName() + " must start with %PDF-");
        String whole = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(whole.contains("startxref"), file.getName() + " must contain startxref");
        assertTrue(whole.contains("%%EOF"), file.getName() + " must contain %%EOF");

        try (Document doc = new Document(file.getAbsolutePath())) {
            assertNotNull(doc.getCatalog(), file.getName() + " must have a catalog");
            assertEquals(expectedPages, doc.getPages().getCount(),
                    file.getName() + " page count");
            for (int i = 1; i <= doc.getPages().getCount(); i++) {
                assertNotNull(doc.getPages().get(i).getRect(),
                        file.getName() + " page " + i + " must have a rect");
            }
            if (expectedText.length > 0) {
                TextAbsorber absorber = new TextAbsorber();
                absorber.visit(doc);
                String extracted = absorber.getText();
                assertNotNull(extracted, file.getName() + " text extraction returned null");
                String flat = extracted.replaceAll("\\s+", "");
                for (String token : expectedText) {
                    assertTrue(flat.contains(token),
                            file.getName() + " must still contain '" + token
                                    + "' (content survived the save)");
                }
            }
        }
    }
}
