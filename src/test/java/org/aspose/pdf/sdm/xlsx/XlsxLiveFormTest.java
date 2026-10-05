package org.aspose.pdf.sdm.xlsx;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.aspose.pdf.Document;
import org.aspose.pdf.ExcelLoadOptions;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfString;
import org.aspose.pdf.forms.Field;
import org.aspose.pdf.forms.Form;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test for the "live spreadsheet" XLSX &rarr; interactive PDF feature.
 * Builds a minimal workbook in memory (two numeric inputs B1/B2 and a formula
 * B3 = B1+B2, plus C3 = SUM(B1:B2)), converts it with
 * {@link ExcelLoadOptions#setInteractiveForms(boolean)} and asserts that cells
 * became AcroForm fields, formulas became {@code /AA /C} calculate actions, the
 * runtime and {@code /CO} are installed, and the static path is unchanged.
 * Reads no files from disk.
 */
class XlsxLiveFormTest {

    @Test
    void formulasBecomeCalculateFields() throws Exception {
        byte[] xlsx = miniWorkbook();
        ExcelLoadOptions opt = new ExcelLoadOptions();
        opt.setInteractiveForms(true);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (Document doc = new Document(new ByteArrayInputStream(xlsx), opt)) {
            doc.save(bos);
        }

        try (Document re = new Document(new ByteArrayInputStream(bos.toByteArray()))) {
            Form form = re.getForm();
            // B1,B2 (inputs) + B3,C3 (formulas) = 4 fields (label cells stay static).
            assertTrue(form.getCount() >= 4, "expected >=4 fields, got " + form.getCount());

            Field b3 = form.get("S0_B3");
            assertNotNull(b3, "formula field S0_B3 missing");
            assertTrue(b3.isReadOnly(), "formula field should be read-only");
            assertEquals("try{event.value = (AXL.f(\"S0_B1\")+AXL.f(\"S0_B2\"));}catch(_e){}", calcJs(b3));

            Field c3 = form.get("S0_C3");
            assertNotNull(c3);
            assertEquals("try{event.value = AXL.sum(AXL.f(\"S0_B1\"),AXL.f(\"S0_B2\"));}catch(_e){}", calcJs(c3));

            Field b1 = form.get("S0_B1");
            assertNotNull(b1, "input field S0_B1 missing");
            assertFalse(b1.isReadOnly(), "numeric input should be editable");

            // AcroForm /CO (calculation order) lists both formula fields.
            Object co = form.getPdfDictionary().get("CO");
            assertTrue(co instanceof PdfArray && ((PdfArray) co).size() == 2, "expected /CO of 2");

            // Document-level AXL runtime present in /Names /JavaScript.
            assertNotNull(re.getCatalog().get(org.aspose.pdf.engine.pdfobjects.PdfName.of("Names")));
        }
    }

    @Test
    void staticPathHasNoFields() throws Exception {
        byte[] xlsx = miniWorkbook();
        ExcelLoadOptions opt = new ExcelLoadOptions(); // interactiveForms defaults false
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (Document doc = new Document(new ByteArrayInputStream(xlsx), opt)) {
            doc.save(bos);
        }
        try (Document re = new Document(new ByteArrayInputStream(bos.toByteArray()))) {
            assertEquals(0, re.getForm().getCount(), "static conversion must not emit form fields");
        }
    }

    private static String calcJs(Field f) {
        PdfDictionary d = f.getPdfDictionary();
        Object aa = d.get("AA");
        assertTrue(aa instanceof PdfDictionary, "field has no /AA");
        Object c = ((PdfDictionary) aa).get("C");
        assertTrue(c instanceof PdfDictionary, "field has no /AA /C");
        Object js = ((PdfDictionary) c).get("JS");
        assertTrue(js instanceof PdfString, "calc action has no /JS");
        return ((PdfString) js).getString();
    }

    // ---- minimal in-memory .xlsx --------------------------------------------

    private static byte[] miniWorkbook() throws Exception {
        String sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
            + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\"><v>3</v></c></row>"
            + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"B2\"><v>4</v></c></row>"
            + "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>2</v></c>"
            + "<c r=\"B3\"><f>B1+B2</f><v>7</v></c>"
            + "<c r=\"C3\"><f>SUM(B1:B2)</f><v>7</v></c></row>"
            + "</sheetData></worksheet>";
        String shared = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"3\" uniqueCount=\"3\">"
            + "<si><t>Alpha</t></si><si><t>Beta</t></si><si><t>Sum</t></si></sst>";
        String styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
            + "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>"
            + "<borders count=\"1\"><border/></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>"
            + "</styleSheet>";
        String workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
            + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
        String wbRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
            + "</Relationships>";
        String rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>";
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
            + "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>"
            + "</Types>";

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            put(zos, "[Content_Types].xml", contentTypes);
            put(zos, "_rels/.rels", rootRels);
            put(zos, "xl/workbook.xml", workbook);
            put(zos, "xl/_rels/workbook.xml.rels", wbRels);
            put(zos, "xl/styles.xml", styles);
            put(zos, "xl/sharedStrings.xml", shared);
            put(zos, "xl/worksheets/sheet1.xml", sheet);
        }
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream zos, String name, String body) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(body.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
