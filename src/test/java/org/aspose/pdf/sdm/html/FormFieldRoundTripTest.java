package org.aspose.pdf.sdm.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.forms.CheckboxField;
import org.aspose.pdf.forms.ComboBoxField;
import org.aspose.pdf.forms.TextBoxField;
import org.aspose.pdf.sdm.FormField;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmNodeType;
import org.aspose.pdf.sdm.layout.SdmPdfLayout;
import org.aspose.pdf.sdm.reader.PdfSdmReader;
import org.junit.jupiter.api.Test;

/**
 * IR form-field projection: PDF widgets &rarr; {@link FormField} nodes &rarr;
 * real HTML controls &rarr; back to {@link FormField} &rarr; real AcroForm
 * fields in a laid-out PDF.
 */
public class FormFieldRoundTripTest {

    // ---- PDF → SDM ---------------------------------------------------------

    private static Document docWithFields() throws Exception {
        Document doc = new Document();
        Page page = doc.getPages().add();
        TextBoxField text = new TextBoxField(page, new Rectangle(50, 700, 250, 720));
        text.setPartialName("payname");
        text.setValue("John Doe");
        text.setMaxLen(40);
        doc.getForm().add(text);
        CheckboxField check = new CheckboxField(page, new Rectangle(50, 660, 62, 672));
        check.setPartialName("agree");
        check.setChecked(true);
        doc.getForm().add(check);
        ComboBoxField combo = new ComboBoxField(page, new Rectangle(50, 620, 150, 640));
        combo.setPartialName("state");
        combo.addOption("CA");
        combo.addOption("NY");
        combo.setSelected("NY");
        doc.getForm().add(combo);
        return doc;
    }

    private static List<FormField> fieldsOf(SdmDocument sdm) {
        return sdm.getChildren().stream()
                .filter(b -> b.getType() == SdmNodeType.FORM_FIELD)
                .map(b -> (FormField) b)
                .collect(Collectors.toList());
    }

    @Test
    public void pdfWidgetsProjectToFormFieldNodes() throws Exception {
        Document doc = docWithFields();
        SdmDocument sdm = new PdfSdmReader().read(doc).getSdm();
        List<FormField> fields = fieldsOf(sdm);
        assertEquals(3, fields.size(), "three widgets project to three FormField nodes");

        FormField text = byName(fields, "payname");
        assertEquals(FormField.Kind.TEXT, text.getKind());
        assertEquals("John Doe", text.getValue());
        assertEquals(Integer.valueOf(40), text.getMaxLen());
        assertTrue(((Number) text.getAttributes().get("display-width")).doubleValue() > 190,
                "footprint mirrored into display-width");

        FormField check = byName(fields, "agree");
        assertEquals(FormField.Kind.CHECKBOX, check.getKind());
        assertTrue(check.isChecked(), "checkbox read as checked");

        FormField combo = byName(fields, "state");
        assertEquals(FormField.Kind.COMBOBOX, combo.getKind());
        assertEquals(List.of("CA", "NY"), combo.getOptions());
        assertEquals("NY", combo.getValue());
    }

    // ---- SDM → HTML → SDM --------------------------------------------------

    @Test
    public void writerEmitsRealControlsAndReaderRestoresThem() throws Exception {
        Document doc = docWithFields();
        SdmDocument sdm = new PdfSdmReader().read(doc).getSdm();
        String html = new SdmHtmlWriter().write(sdm);

        assertTrue(html.contains("<input class=\"ff\" type=\"text\" name=\"payname\""),
                "text field emitted as input");
        assertTrue(html.contains("value=\"John Doe\""), "value carried");
        assertTrue(html.contains("maxlength=\"40\""), "maxlength carried");
        assertTrue(html.contains("type=\"checkbox\" name=\"agree\""), "checkbox emitted");
        assertTrue(html.contains(" checked"), "checked state carried");
        assertTrue(html.contains("<select class=\"ff\" name=\"state\""), "combo emitted as select");
        assertTrue(html.contains("<option selected>NY</option>"), "selection carried");
        assertFalse(html.contains("data-render-hint=\"annotation\""),
                "widgets no longer degrade to opaque placeholders");

        SdmDocument back = new HtmlSdmReader().read(html, null, null);
        List<FormField> fields = fieldsOf(back);
        assertEquals(3, fields.size(), "reader restores all three fields");
        FormField text = byName(fields, "payname");
        assertEquals(FormField.Kind.TEXT, text.getKind());
        assertEquals("John Doe", text.getValue());
        assertEquals(Integer.valueOf(40), text.getMaxLen());
        FormField check = byName(fields, "agree");
        assertEquals(FormField.Kind.CHECKBOX, check.getKind());
        assertTrue(check.isChecked());
        FormField combo = byName(fields, "state");
        assertEquals(FormField.Kind.COMBOBOX, combo.getKind());
        assertEquals(List.of("CA", "NY"), combo.getOptions());
        assertEquals("NY", combo.getValue());
    }

    @Test
    public void readerParsesTextareaRadioAndListbox() {
        SdmDocument d = new HtmlSdmReader().read("<html><body>"
                + "<textarea name=\"notes\" readonly style=\"width:100pt;height:40pt\">line</textarea>"
                + "<input type=\"radio\" name=\"g\" value=\"a\" checked/>"
                + "<select name=\"lst\" data-field-kind=\"listbox\" size=\"3\">"
                + "<option>x</option><option selected>y</option></select>"
                + "</body></html>", null, null);
        List<FormField> fields = fieldsOf(d);
        assertEquals(3, fields.size());
        FormField ta = byName(fields, "notes");
        assertEquals(FormField.Kind.TEXT, ta.getKind());
        assertTrue(ta.isMultiline(), "textarea is multiline");
        assertTrue(ta.isReadOnly(), "readonly parsed");
        assertEquals("line", ta.getValue());
        assertEquals(100.0, ((Number) ta.getAttributes().get("display-width")).doubleValue(), 0.01);
        FormField radio = byName(fields, "g");
        assertEquals(FormField.Kind.RADIO, radio.getKind());
        assertTrue(radio.isChecked());
        assertEquals("a", radio.getExportValue());
        FormField list = byName(fields, "lst");
        assertEquals(FormField.Kind.LISTBOX, list.getKind());
        assertEquals("y", list.getValue());
    }

    // ---- SDM → PDF (layout) ------------------------------------------------

    @Test
    public void layoutCreatesRealAcroFormFields() throws Exception {
        Document doc = docWithFields();
        SdmDocument sdm = new PdfSdmReader().read(doc).getSdm();
        SdmPdfLayout.Result result = new SdmPdfLayout().render(sdm, null);
        Document out = result.getDocument();
        org.aspose.pdf.forms.Field[] fields = out.getForm().getFields();
        assertEquals(3, fields.length, "layout re-creates all three AcroForm fields");
        java.util.Set<String> names = java.util.Arrays.stream(fields)
                .map(org.aspose.pdf.forms.Field::getFullName)
                .collect(Collectors.toSet());
        assertTrue(names.contains("payname"), "text field name survives: " + names);
        assertTrue(names.contains("agree"), "checkbox name survives");
        assertTrue(names.contains("state"), "combo name survives");
    }

    private static FormField byName(List<FormField> fields, String name) {
        for (FormField f : fields) {
            if (name.equals(f.getName())) {
                return f;
            }
        }
        throw new AssertionError("no field named " + name + " in "
                + fields.stream().map(FormField::getName).collect(Collectors.toList()));
    }
}
