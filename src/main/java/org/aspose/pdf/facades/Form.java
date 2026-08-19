package org.aspose.pdf.facades;

import org.aspose.pdf.Document;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfString;
import org.aspose.pdf.engine.io.RandomAccessReader;
import org.aspose.pdf.engine.parser.PDFParser;
import org.aspose.pdf.forms.ButtonField;
import org.aspose.pdf.forms.CheckboxField;
import org.aspose.pdf.forms.ComboBoxField;
import org.aspose.pdf.forms.Field;
import org.aspose.pdf.forms.ListBoxField;
import org.aspose.pdf.forms.Option;
import org.aspose.pdf.forms.OptionCollection;
import org.aspose.pdf.forms.RadioButtonField;
import org.aspose.pdf.forms.SignatureField;
import org.aspose.pdf.forms.TextBoxField;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A convenience facade for working with PDF interactive forms (AcroForms).
 * This wraps {@link org.aspose.pdf.forms.Form} to provide a simpler API
 * for common form operations.
 * <p>
 * Not to be confused with {@link org.aspose.pdf.forms.Form}, which is the
 * core form model class.
 */
public class Form implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Form.class.getName());

    private Document document;
    private boolean autoRestoreForm = true;

    /** Where to send {@link #save()} output when the (input,output) ctor is used. */
    private String pendingOutputFile;
    private OutputStream pendingOutputStream;

    /**
     * Creates a new facade {@code Form} instance.
     */
    public Form() {
    }

    /**
     * Creates a new facade {@code Form} instance bound to the specified PDF file.
     *
     * @param inputFile path to the PDF file
     */
    public Form(String inputFile) {
        bindPdf(inputFile);
    }

    /**
     * Creates a {@code Form} bound to {@code inputFile}, remembering
     * {@code outputFile} for {@link #save()} (legacy Aspose convention).
     *
     * @param inputFile  path to the source PDF
     * @param outputFile path the no-arg {@link #save()} writes to
     */
    public Form(String inputFile, String outputFile) {
        bindPdf(inputFile);
        this.pendingOutputFile = outputFile;
    }

    /**
     * Creates a new facade {@code Form} instance bound to the specified PDF stream.
     *
     * @param inputStream the stream containing PDF data
     */
    public Form(InputStream inputStream) {
        bindPdf(inputStream);
    }

    /**
     * Creates a {@code Form} bound to {@code inputStream}, remembering
     * {@code outputStream} for {@link #save()}. The input stream is fully
     * read during construction; the caller may close it. Output is flushed
     * but not closed by {@link #save()}/{@link #close()}.
     */
    public Form(InputStream inputStream, OutputStream outputStream) {
        bindPdf(inputStream);
        this.pendingOutputStream = outputStream;
    }

    /**
     * Creates a new facade {@code Form} instance bound to an existing document.
     *
     * @param document the document to bind
     */
    public Form(Document document) {
        bindPdf(document);
    }

    /**
     * Binds a PDF file to this form facade.
     *
     * @param inputFile path to the PDF file
     * @return {@code true} on success
     */
    public boolean bindPdf(String inputFile) {
        try {
            this.document = new Document(inputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to bind PDF from file: " + inputFile, e);
            return false;
        }
    }

    /**
     * Binds a PDF from an input stream.
     *
     * @param inputStream the input stream containing PDF data
     * @return {@code true} on success
     */
    public boolean bindPdf(InputStream inputStream) {
        try {
            this.document = new Document(inputStream);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to bind PDF from stream", e);
            return false;
        }
    }

    /**
     * Binds an existing {@link Document} to this form facade.
     *
     * @param document the document to bind
     * @return {@code true} on success
     */
    public boolean bindPdf(Document document) {
        if (document == null) {
            LOG.warning("Cannot bind null document");
            return false;
        }
        this.document = document;
        return true;
    }

    /**
     * Returns the currently bound document.
     *
     * @return the bound document, or {@code null} if none is bound
     */
    public Document getDocument() {
        return document;
    }

    /**
     * Returns the names of all form fields in the document.
     *
     * @return array of field names, or an empty array on error
     */
    public String[] getFieldNames() {
        try {
            org.aspose.pdf.forms.Form form = document.getForm();
            if (form == null) {
                return new String[0];
            }
            Field[] fields = form.getFields();
            if (fields == null) {
                return new String[0];
            }
            // Aspose omits fields without a usable name (a widget with no /T
            // yields an empty full name — PDFNEWNET-34179 counts 81, not 82).
            java.util.List<String> names = new java.util.ArrayList<>(fields.length);
            for (Field field : fields) {
                String name = field.getFullName();
                if (name != null && !name.isEmpty()) {
                    names.add(name);
                }
            }
            return names.toArray(new String[0]);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to get field names", e);
            return new String[0];
        }
    }

    /**
     * Fills a form field with the specified value.
     *
     * @param fieldName the full name of the field
     * @param value     the value to set
     * @return {@code true} on success
     */
    public boolean fillField(String fieldName, String value) {
        try {
            org.aspose.pdf.forms.Form form = requireBoundForm();
            if (form == null) {
                return false;
            }
            Field field = form.get(fieldName);
            if (field == null) {
                LOG.warning("Field not found: " + fieldName);
                return false;
            }
            // Aspose semantics: a non-editable choice field rejects a value that is
            // not one of its options — FillField returns false and the field keeps
            // its old value (PDFNEWNET_34300).
            if (field instanceof ComboBoxField && !((ComboBoxField) field).isEditable()
                    && !isChoiceOption(((ComboBoxField) field).getOptions(), value)) {
                LOG.fine("Rejected non-option value '" + value + "' for combo box '" + fieldName + "'");
                return false;
            }
            if (field instanceof ListBoxField
                    && !isChoiceOption(((ListBoxField) field).getOptions(), value)) {
                LOG.fine("Rejected non-option value '" + value + "' for list box '" + fieldName + "'");
                return false;
            }
            field.setValue(value);
            LOG.fine("Set field '" + fieldName + "' to '" + value + "'");
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to fill field: " + fieldName, e);
            return false;
        }
    }

    /** Returns whether {@code value} matches an option's export value or display name. */
    private static boolean isChoiceOption(OptionCollection options, String value) {
        if (options == null || value == null) {
            return false;
        }
        for (Option option : options) {
            if (value.equals(option.getValue()) || value.equals(option.getDisplayName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fills a checkbox-like field using a boolean value.
     *
     * @param fieldName the full name of the field
     * @param value the value to set
     * @return {@code true} on success
     */
    public boolean fillField(String fieldName, boolean value) {
        try {
            org.aspose.pdf.forms.Form form = requireBoundForm();
            if (form == null) {
                return false;
            }
            Field field = form.get(fieldName);
            if (field == null) {
                LOG.warning("Field not found: " + fieldName);
                return false;
            }
            if (field instanceof CheckboxField) {
                ((CheckboxField) field).setChecked(value);
                return true;
            }
            field.setValue(value ? "Yes" : "Off");
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to fill boolean field: " + fieldName, e);
            return false;
        }
    }

    /**
     * Fills a field using an integer value.
     *
     * @param fieldName the full name of the field
     * @param value the integer value to set
     * @return {@code true} on success
     */
    public boolean fillField(String fieldName, int value) {
        try {
            org.aspose.pdf.forms.Form form = requireBoundForm();
            if (form == null) {
                return false;
            }
            Field field = form.get(fieldName);
            if (field == null) {
                LOG.warning("Field not found: " + fieldName);
                return false;
            }
            if (field instanceof RadioButtonField) {
                ((RadioButtonField) field).setSelected(value);
                return true;
            }
            field.setValue(String.valueOf(value));
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to fill integer field: " + fieldName, e);
            return false;
        }
    }

    /**
     * Returns the string value of a field.
     *
     * @param fieldName the full name of the field
     * @return the field value, or {@code null} if the field is absent
     */
    public String getField(String fieldName) {
        try {
            org.aspose.pdf.forms.Form form = requireBoundForm();
            if (form == null) {
                return null;
            }
            Field field = form.get(fieldName);
            return field != null ? field.getValue() : null;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to read field: " + fieldName, e);
            return null;
        }
    }

    /**
     * Flattens all form fields, making them non-interactive.
     *
     * @return {@code true} on success
     */
    public boolean flattenAllFields() {
        try {
            org.aspose.pdf.forms.Form form = requireBoundForm();
            if (form == null) {
                return false;
            }
            form.flatten();
            LOG.fine("Flattened all form fields");
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to flatten form fields", e);
            return false;
        }
    }

    /**
     * Flattens a single field and removes it from the interactive form.
     *
     * @param fieldName the full name of the field to flatten
     * @return {@code true} if the field was flattened
     */
    public boolean flattenField(String fieldName) {
        try {
            org.aspose.pdf.forms.Form form = requireBoundForm();
            if (form == null) {
                return false;
            }
            Field field = form.get(fieldName);
            if (field == null) {
                LOG.warning("Field not found: " + fieldName);
                return false;
            }
            field.flatten();
            form.delete(fieldName);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to flatten field: " + fieldName, e);
            return false;
        }
    }

    /**
     * Returns a field facade object for the specified field.
     *
     * @param fieldName the full name of the field
     * @return the field object, or {@code null} if absent
     */
    public Object getFieldFacade(String fieldName) {
        try {
            org.aspose.pdf.forms.Form form = requireBoundForm();
            if (form == null) {
                return null;
            }
            return form.get(fieldName);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to get field facade: " + fieldName, e);
            return null;
        }
    }

    /**
     * Returns the logical field type name in an Aspose-compatible style.
     *
     * @param fieldName the full name of the field
     * @return the field type name, or {@code null} if the field is absent
     */
    public String getFieldType(String fieldName) {
        try {
            org.aspose.pdf.forms.Form form = requireBoundForm();
            if (form == null) {
                return null;
            }
            return mapFieldType(form.get(fieldName));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to get field type: " + fieldName, e);
            return null;
        }
    }

    /**
     * Returns the {@link FieldType} enum classification for {@code fieldName},
     * mirroring the C# {@code Form.GetFieldType} return type. Defaults to
     * {@link FieldType#InvalidName} when the field is missing or unsupported.
     */
    public FieldType getFieldTypeAsEnum(String fieldName) {
        try {
            org.aspose.pdf.forms.Form form = requireBoundForm();
            if (form == null) return FieldType.InvalidName;
            org.aspose.pdf.forms.Field f = form.get(fieldName);
            if (f == null) return FieldType.InvalidName;
            if (f instanceof org.aspose.pdf.forms.ComboBoxField) return FieldType.ComboBox;
            if (f instanceof org.aspose.pdf.forms.ListBoxField) return FieldType.ListBox;
            if (f instanceof org.aspose.pdf.forms.CheckboxField) return FieldType.CheckBox;
            if (f instanceof org.aspose.pdf.forms.RadioButtonField) return FieldType.RadioButton;
            if (f instanceof org.aspose.pdf.forms.SignatureField) return FieldType.Signature;
            if (f instanceof org.aspose.pdf.forms.ButtonField) return FieldType.PushButton;
            if (f instanceof org.aspose.pdf.forms.TextBoxField) return FieldType.Text;
            return FieldType.InvalidName;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to get field type enum: " + fieldName, e);
            return FieldType.InvalidName;
        }
    }

    /**
     * Returns whether the bound document contains XFA data.
     *
     * @return {@code true} if the form exposes XFA data
     */
    public boolean hasXfa() {
        try {
            org.aspose.pdf.forms.Form form = requireBoundForm();
            return form != null && form.getXFA() != null;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to inspect XFA presence", e);
            return false;
        }
    }

    /**
     * Returns whether the bound document contains signature fields.
     *
     * @return {@code true} if at least one signature field exists
     */
    public boolean isSignaturesExist() {
        try {
            org.aspose.pdf.forms.Form form = requireBoundForm();
            if (form == null) {
                return false;
            }
            for (Field field : form.getFields()) {
                if (field instanceof SignatureField) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to inspect signature fields", e);
            return false;
        }
    }

    /**
     * Sets the auto-restore flag for Aspose-compatible workflows.
     *
     * @param autoRestoreForm the desired flag value
     */
    public void setAutoRestoreForm(boolean autoRestoreForm) {
        this.autoRestoreForm = autoRestoreForm;
    }

    /**
     * Returns the auto-restore flag for Aspose-compatible workflows.
     *
     * @return the current auto-restore flag
     */
    public boolean getAutoRestoreForm() {
        return autoRestoreForm;
    }

    /**
     * Imports form field values from an FDF (Forms Data Format) input stream.
     * <p>
     * FDF is a simplified PDF-like format defined in ISO 32000-1:2008 §12.7.7.
     * The FDF file contains a catalog with an {@code /FDF} dictionary that holds
     * a {@code /Fields} array, where each entry maps {@code /T} (field name) to
     * {@code /V} (field value).
     *
     * @param fdfInputStream the FDF input stream
     */
    public void importFdf(InputStream fdfInputStream) {
        if (document == null) {
            LOG.warning("No document bound — cannot import FDF");
            return;
        }
        try {
            RandomAccessReader reader = RandomAccessReader.fromStream(fdfInputStream);
            PDFParser fdfParser = new PDFParser(reader);
            fdfParser.parse();
            PdfDictionary catalog = fdfParser.getCatalog();
            PdfBase fdfBase = catalog.get("FDF");
            if (fdfBase instanceof PdfObjectReference) {
                fdfBase = fdfParser.resolveReference(fdfBase);
            }
            if (!(fdfBase instanceof PdfDictionary)) {
                LOG.warning("FDF catalog does not contain /FDF dictionary");
                fdfParser.close();
                return;
            }
            PdfDictionary fdfDict = (PdfDictionary) fdfBase;
            PdfBase fieldsBase = fdfDict.get("Fields");
            if (fieldsBase instanceof PdfObjectReference) {
                fieldsBase = fdfParser.resolveReference(fieldsBase);
            }
            if (!(fieldsBase instanceof PdfArray)) {
                LOG.warning("FDF dictionary does not contain /Fields array");
                fdfParser.close();
                return;
            }
            PdfArray fields = (PdfArray) fieldsBase;
            org.aspose.pdf.forms.Form form = document.getForm();
            if (form == null) {
                LOG.warning("Document has no AcroForm — cannot import FDF fields");
                fdfParser.close();
                return;
            }
            for (int i = 0; i < fields.size(); i++) {
                PdfBase entry = fields.get(i);
                if (entry instanceof PdfObjectReference) {
                    entry = fdfParser.resolveReference(entry);
                }
                if (!(entry instanceof PdfDictionary)) {
                    continue;
                }
                PdfDictionary fieldDict = (PdfDictionary) entry;
                String fieldName = fieldDict.getString("T");
                if (fieldName == null) {
                    continue;
                }
                PdfBase valueObj = fieldDict.get("V");
                if (valueObj instanceof PdfObjectReference) {
                    valueObj = fdfParser.resolveReference(valueObj);
                }
                String value = null;
                if (valueObj instanceof PdfString) {
                    value = ((PdfString) valueObj).getString();
                } else if (valueObj instanceof PdfName) {
                    value = ((PdfName) valueObj).getName();
                }
                if (value != null) {
                    Field field = form.get(fieldName);
                    if (field != null) {
                        field.setValue(value);
                        LOG.fine("Imported FDF field '" + fieldName + "' = '" + value + "'");
                    } else {
                        LOG.fine("FDF field '" + fieldName + "' not found in document form");
                    }
                }
            }
            fdfParser.close();
            LOG.fine("FDF import completed");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to import FDF data", e);
        }
    }

    /**
     * Imports form data from an XML input stream.
     * <p>
     * <strong>Stub implementation.</strong> XML form data import is not yet supported.
     *
     * @param xmlStream the XML input stream
     */
    public void importXml(InputStream xmlStream) {
        LOG.warning("importXml is not yet implemented");
    }

    /**
     * Exports form data to an XML output stream.
     * <p>
     * <strong>Stub implementation.</strong> XML form data export is not yet supported.
     *
     * @param xmlStream the XML output stream
     */
    public void exportXml(OutputStream xmlStream) {
        LOG.warning("exportXml is not yet implemented");
    }

    /**
     * Saves to the destination remembered by the {@code (input, output)} ctor.
     * Throws {@link IllegalStateException} if no output destination was bound.
     *
     * @return {@code true} on success
     */
    public boolean save() {
        if (pendingOutputFile != null) {
            return save(pendingOutputFile);
        }
        if (pendingOutputStream != null) {
            return save(pendingOutputStream);
        }
        throw new IllegalStateException(
                "Form.save(): no output destination — use Form(input, output) ctor or save(File/Stream)");
    }

    /**
     * Saves the bound document to a file.
     *
     * @param outputFile path to the output file
     * @return {@code true} on success
     */
    public boolean save(String outputFile) {
        try {
            document.requestFullRewrite();
            document.save(outputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to save PDF to file: " + outputFile, e);
            return false;
        }
    }

    /**
     * Saves the bound document to an output stream.
     *
     * @param outputStream the output stream
     * @return {@code true} on success
     */
    public boolean save(OutputStream outputStream) {
        try {
            document.requestFullRewrite();
            document.save(outputStream);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to save PDF to stream", e);
            return false;
        }
    }

    /**
     * Closes the form facade and releases the bound document.
     */
    public void close() {
        if (document != null) {
            try {
                document.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Error closing document", e);
            }
            document = null;
        }
    }

    private org.aspose.pdf.forms.Form requireBoundForm() {
        if (document == null) {
            LOG.warning("No document bound");
            return null;
        }
        org.aspose.pdf.forms.Form form;
        try {
            form = document.getForm();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to access document form", e);
            return null;
        }
        if (form == null) {
            LOG.warning("Document has no form");
        }
        return form;
    }

    private String mapFieldType(Field field) {
        if (field == null) {
            return null;
        }
        if (field instanceof CheckboxField) {
            return "CheckBox";
        }
        if (field instanceof RadioButtonField) {
            return "Radio";
        }
        if (field instanceof ComboBoxField) {
            return "ComboBox";
        }
        if (field instanceof ListBoxField) {
            return "ListBox";
        }
        if (field instanceof SignatureField) {
            return "Signature";
        }
        if (field instanceof ButtonField) {
            return "PushButton";
        }
        if (field instanceof TextBoxField) {
            return "Text";
        }
        return field.getClass().getSimpleName();
    }
}
