package org.aspose.pdf.tests.generation;

import org.aspose.pdf.CryptoAlgorithm;
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.forms.RadioButtonField;
import org.aspose.pdf.forms.TextBoxField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug R — no Form XObject in any saved PDF may have {@code /BBox [0 0 0 0]}
 * (degenerate appearance) or {@code /Length 0} (Adobe Reader rejects empty
 * encrypted streams). Earlier versions of {@link RadioButtonField#addOption}
 * created such placeholder Form XObjects; this test guards against the
 * regression.
 */
class EmptyAppearanceStreamsTest {

    @TempDir Path tempDir;

    private static final Pattern OBJ_RE = Pattern.compile(
            "(\\d+) (\\d+) obj\\s*(.*?)\\s*endobj", Pattern.DOTALL);
    private static final Pattern LEN_RE = Pattern.compile("/Length\\s+(\\d+)");
    private static final Pattern BBOX_RE = Pattern.compile(
            "/BBox\\s*\\[\\s*([-\\d.eE]+)\\s+([-\\d.eE]+)\\s+([-\\d.eE]+)\\s+([-\\d.eE]+)\\s*\\]");

    private static void assertNoEmptyXObject(byte[] pdfBytes) {
        String body = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        Matcher om = OBJ_RE.matcher(body);
        StringBuilder problems = new StringBuilder();
        while (om.find()) {
            String objBody = om.group(3);
            if (!objBody.contains("/Subtype /Form")) continue;
            // Length 0 is fatal under encryption; degenerate BBox is wrong even unencrypted
            Matcher lm = LEN_RE.matcher(objBody);
            int length = lm.find() ? Integer.parseInt(lm.group(1)) : -1;
            Matcher bm = BBOX_RE.matcher(objBody);
            double w = 0, h = 0;
            if (bm.find()) {
                w = Double.parseDouble(bm.group(3)) - Double.parseDouble(bm.group(1));
                h = Double.parseDouble(bm.group(4)) - Double.parseDouble(bm.group(2));
            }
            if (length == 0) {
                problems.append("obj ").append(om.group(1))
                        .append(" Form XObject has /Length 0\n");
            }
            if (w == 0 || h == 0) {
                problems.append("obj ").append(om.group(1))
                        .append(" Form XObject has degenerate BBox (").append(w).append('x').append(h).append(")\n");
            }
        }
        if (problems.length() > 0) {
            fail(problems.toString());
        }
    }

    @Test
    @DisplayName("Plain doc with no widgets has no degenerate Form XObjects")
    void freshDocumentWithoutAppearances_emitsNoZeroBBoxFormXObjects() throws IOException {
        Path out = tempDir.resolve("plain.pdf");
        try (Document doc = new Document()) {
            doc.getPages().add();
            doc.save(out.toString());
        }
        assertNoEmptyXObject(Files.readAllBytes(out));
    }

    @Test
    @DisplayName("Radio button widget appearances are non-empty after addOption()")
    void radioOptionAppearances_areNotEmpty() throws IOException {
        Path out = tempDir.resolve("radio.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            RadioButtonField rb = new RadioButtonField(page);
            rb.setPartialName("plan");
            rb.addOption("Free",       new Rectangle(100, 700, 114, 714));
            rb.addOption("Pro",        new Rectangle(180, 700, 194, 714));
            doc.getForm().add(rb, 1);
            doc.save(out.toString());
        }
        assertNoEmptyXObject(Files.readAllBytes(out));
    }

    @Test
    @DisplayName("Radio button widget appearances are non-empty under AES-256 encryption")
    void radioOptionAppearances_areNotEmpty_underEncryption() throws IOException {
        Path out = tempDir.resolve("radio-aes.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            RadioButtonField rb = new RadioButtonField(page);
            rb.setPartialName("plan");
            rb.addOption("Free",       new Rectangle(100, 700, 114, 714));
            rb.addOption("Pro",        new Rectangle(180, 700, 194, 714));
            rb.addOption("Enterprise", new Rectangle(260, 700, 274, 714));
            doc.getForm().add(rb, 1);
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(out.toString());
        }
        assertNoEmptyXObject(Files.readAllBytes(out));
    }

    @Test
    @DisplayName("Widget without explicit appearance does not emit a zero-Length Form XObject")
    void widgetWithoutAppearance_doesNotEmitZeroLengthXObject() throws IOException {
        Path out = tempDir.resolve("tb-aes.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tb = new TextBoxField(page, new Rectangle(100, 700, 300, 720));
            tb.setPartialName("tb");
            doc.getForm().add(tb, 1);
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(out.toString());
        }
        assertNoEmptyXObject(Files.readAllBytes(out));
    }
}
