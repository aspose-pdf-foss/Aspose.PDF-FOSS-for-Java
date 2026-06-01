package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.forms.RadioButtonField;
import org.aspose.pdf.forms.RadioButtonOptionField;
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
 * Bug T — {@code RadioButtonField} writes {@code /Kids} as inline dictionaries.
 * ISO 32000-1:2008 §12.7.4.1 Table 220 requires {@code /Kids} to be an array of
 * indirect references. Inline kids make poppler report "Invalid form field
 * reference" + "Bad bounding box". This test asserts that, after save, every
 * {@code /Kids} entry is an {@code N G R} reference resolving to its own
 * top-level object.
 */
class RadioButtonKidsIndirectTest {

    @TempDir Path tempDir;

    /** Returns the bytes of the radio field object whose /T is {@code name}. */
    private static String findFieldObject(byte[] pdf, String name) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        Matcher om = Pattern.compile("(\\d+) (\\d+) obj(.*?)endobj", Pattern.DOTALL).matcher(s);
        while (om.find()) {
            String body = om.group(3);
            if (body.contains("/FT /Btn") && body.contains("(" + name + ")")) {
                return body;
            }
        }
        return null;
    }

    /** Extracts the raw text inside the first /Kids [ ... ] array of {@code objBody}. */
    private static String kidsArrayText(String objBody) {
        Matcher m = Pattern.compile("/Kids\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(objBody);
        return m.find() ? m.group(1).trim() : null;
    }

    @Test
    @DisplayName("addOption(String,Rect): kid is an indirect reference, not inline")
    void addOptionByRect_kidIsIndirectObject() throws IOException {
        Path out = tempDir.resolve("r1.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            RadioButtonField rb = new RadioButtonField(page);
            rb.setPartialName("plan");
            rb.addOption("Yes", new Rectangle(100, 700, 114, 714));
            doc.getForm().add(rb, 1);
            doc.save(out.toString());
        }
        String body = findFieldObject(Files.readAllBytes(out), "plan");
        assertNotNull(body, "radio field object must exist");
        String kids = kidsArrayText(body);
        assertNotNull(kids, "/Kids array must be present");
        assertFalse(kids.contains("<<"),
                "/Kids must not contain inline dictionaries; got: " + kids);
        assertTrue(kids.matches("\\s*\\d+\\s+\\d+\\s+R\\s*"),
                "/Kids must be a single indirect reference; got: " + kids);
    }

    @Test
    @DisplayName("add(RadioButtonOptionField): kid is an indirect reference")
    void addOptionByOptionField_kidIsIndirectObject() throws IOException {
        Path out = tempDir.resolve("r2.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            RadioButtonField rb = new RadioButtonField(page);
            rb.setPartialName("plan");
            RadioButtonOptionField opt = new RadioButtonOptionField(page,
                    new Rectangle(100, 700, 114, 714));
            opt.setOptionName("Yes");
            rb.add(opt);
            doc.getForm().add(rb, 1);
            doc.save(out.toString());
        }
        String body = findFieldObject(Files.readAllBytes(out), "plan");
        assertNotNull(body);
        String kids = kidsArrayText(body);
        assertNotNull(kids);
        assertFalse(kids.contains("<<"), "kid must be indirect; got: " + kids);
        assertTrue(Pattern.compile("\\d+\\s+\\d+\\s+R").matcher(kids).find());
    }

    @Test
    @DisplayName("Multiple options: /Kids is [N1 R N2 R N3 R] with distinct object numbers")
    void multipleOptions_eachIsSeparateIndirectObject() throws IOException {
        Path out = tempDir.resolve("r3.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            RadioButtonField rb = new RadioButtonField(page);
            rb.setPartialName("plan");
            rb.addOption("Free", new Rectangle(100, 700, 114, 714));
            rb.addOption("Pro", new Rectangle(180, 700, 194, 714));
            rb.addOption("Enterprise", new Rectangle(260, 700, 274, 714));
            doc.getForm().add(rb, 1);
            doc.save(out.toString());
        }
        byte[] pdf = Files.readAllBytes(out);
        String body = findFieldObject(pdf, "plan");
        String kids = kidsArrayText(body);
        assertNotNull(kids);
        assertFalse(kids.contains("<<"), "no inline kids allowed; got: " + kids);
        Matcher m = Pattern.compile("(\\d+)\\s+\\d+\\s+R").matcher(kids);
        java.util.Set<Integer> nums = new java.util.HashSet<>();
        while (m.find()) nums.add(Integer.parseInt(m.group(1)));
        assertEquals(3, nums.size(), "three distinct indirect kid objects expected; got: " + kids);
        // Each referenced object must exist as a top-level "N 0 obj".
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        for (int n : nums) {
            assertTrue(Pattern.compile("(?m)^" + n + " \\d+ obj").matcher(s).find(),
                    "kid object " + n + " must be emitted at top level");
        }
    }

    @Test
    @DisplayName("Each kid's /Parent resolves back to the radio field after reopen")
    void kidParentReferencesParentField() throws IOException {
        Path out = tempDir.resolve("r4.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            RadioButtonField rb = new RadioButtonField(page);
            rb.setPartialName("plan");
            rb.addOption("Free", new Rectangle(100, 700, 114, 714));
            rb.addOption("Pro", new Rectangle(180, 700, 194, 714));
            doc.getForm().add(rb, 1);
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            COSDictionary parentDict =
                    r.getForm().get("plan").getCOSDictionary();
            COSBase kidsVal = parentDict.get("Kids");
            if (kidsVal instanceof COSObjectReference) {
                kidsVal = ((COSObjectReference) kidsVal).dereference();
            }
            assertTrue(kidsVal instanceof COSArray);
            COSArray kids = (COSArray) kidsVal;
            assertTrue(kids.size() >= 2);
            for (int i = 0; i < kids.size(); i++) {
                COSBase kid = kids.get(i);
                assertTrue(kid instanceof COSObjectReference,
                        "every /Kids entry must be an indirect reference on reopen");
                COSBase kidObj = ((COSObjectReference) kid).dereference();
                assertTrue(kidObj instanceof COSDictionary);
                COSBase parent = ((COSDictionary) kidObj).get("Parent");
                if (parent instanceof COSObjectReference) {
                    parent = ((COSObjectReference) parent).dereference();
                }
                assertSame(parentDict, parent,
                        "kid /Parent must resolve back to the radio field");
            }
        }
    }

    @Test
    @DisplayName("Demo radio setup: no inline dict inside any /Kids array")
    void popplerWouldNotReportInvalidFormFieldReference() throws IOException {
        Path out = tempDir.resolve("r5.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            RadioButtonField rb = new RadioButtonField(page);
            rb.setPartialName("plan");
            rb.addOption("Free", new Rectangle(100, 700, 114, 714));
            rb.addOption("Pro", new Rectangle(180, 700, 194, 714));
            rb.addOption("Enterprise", new Rectangle(260, 700, 274, 714));
            doc.getForm().add(rb, 1);
            rb.setValue("Pro");
            doc.save(out.toString());
        }
        String s = new String(Files.readAllBytes(out), StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("/Kids\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(s);
        while (m.find()) {
            assertFalse(m.group(1).contains("<<"),
                    "no /Kids array may contain an inline dictionary; got: " + m.group(1));
        }
    }

    @Test
    @DisplayName("Kid widgets are surfaced in the page /Annots as indirect references")
    void kidWidgetsAppearInPageAnnots() throws IOException {
        Path out = tempDir.resolve("r6.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            RadioButtonField rb = new RadioButtonField(page);
            rb.setPartialName("plan");
            rb.addOption("Free", new Rectangle(100, 700, 114, 714));
            rb.addOption("Pro", new Rectangle(180, 700, 194, 714));
            doc.getForm().add(rb, 1);
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            Page page = r.getPages().get(1);
            COSBase annotsVal = page.getCOSDictionary().get(COSName.ANNOTS);
            if (annotsVal instanceof COSObjectReference) {
                annotsVal = ((COSObjectReference) annotsVal).dereference();
            }
            assertTrue(annotsVal instanceof COSArray, "page must have /Annots");
            COSArray annots = (COSArray) annotsVal;
            // Collect the kid object keys from the radio field's /Kids.
            COSDictionary parent = r.getForm().get("plan").getCOSDictionary();
            COSBase kidsVal = parent.get("Kids");
            if (kidsVal instanceof COSObjectReference) kidsVal = ((COSObjectReference) kidsVal).dereference();
            COSArray kids = (COSArray) kidsVal;
            int matched = 0;
            for (int i = 0; i < kids.size(); i++) {
                COSBase kid = kids.get(i);
                if (!(kid instanceof COSObjectReference)) continue;
                for (int j = 0; j < annots.size(); j++) {
                    COSBase a = annots.get(j);
                    if (a instanceof COSObjectReference
                            && ((COSObjectReference) a).getKey().equals(
                                    ((COSObjectReference) kid).getKey())) {
                        matched++;
                        break;
                    }
                }
            }
            assertEquals(kids.size(), matched,
                    "every kid widget must be referenced from the page /Annots");
        }
    }
}
