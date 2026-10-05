package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.engine.pdfobjects.*;
import org.aspose.pdf.engine.parser.PDFParser;
import org.aspose.pdf.forms.xfa.XfaForm;

import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Represents the interactive form (AcroForm) of a PDF document
 * (ISO 32000-1:2008, §12.7).
 * Accessed via {@code document.getForm()}.
 */
public class Form implements Iterable<Field> {

    private static final Logger LOG = Logger.getLogger(Form.class.getName());

    private final PdfDictionary acroFormDict;
    private final Document document;
    private final PDFParser parser;
    private List<Field> fields;
    private Map<String, Field> fieldsByName;
    private XfaForm xfaForm;
    private FlattenSettings flattenSettings;

    public Form(PdfDictionary acroFormDict, Document document, PDFParser parser) {
        this.acroFormDict = acroFormDict != null ? acroFormDict : new PdfDictionary();
        this.document = document;
        this.parser = parser;
    }

    /** Get field by full name */
    public Field get(String fieldName) {
        ensureLoaded();
        return fieldsByName.get(fieldName);
    }

    /**
     * Returns whether a field with the specified name exists.
     *
     * @param fieldName the field name to look up
     * @return true if the field exists
     */
    public boolean hasField(String fieldName) {
        return hasField(fieldName, false);
    }

    /**
     * Returns whether a field with the specified name exists.
     *
     * @param fieldName the field name to look up
     * @param ignoreCase true to compare names case-insensitively
     * @return true if the field exists
     */
    public boolean hasField(String fieldName, boolean ignoreCase) {
        ensureLoaded();
        if (fieldName == null) {
            return false;
        }
        if (!ignoreCase) {
            return fieldsByName.containsKey(fieldName);
        }
        for (String name : fieldsByName.keySet()) {
            if (fieldName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Get field by 1-based index */
    public Field get(int index) {
        ensureLoaded();
        if (index < 1 || index > fields.size())
            throw new IndexOutOfBoundsException("Index " + index + " out of [1," + fields.size() + "]");
        return fields.get(index - 1);
    }

    /** Get all fields */
    public Field[] getFields() {
        ensureLoaded();
        return fields.toArray(new Field[0]);
    }

    /** Total field count */
    public int getCount() {
        ensureLoaded();
        return fields.size();
    }

    /**
     * Exports all fields to a JSON array (indented) and returns the produced JSON text.
     *
     * @param jsonStream destination stream; must not be {@code null}
     * @return the JSON text written (never {@code null})
     */
    public String exportToJson(java.io.OutputStream jsonStream) {
        if (jsonStream == null) {
            throw new IllegalArgumentException("jsonStream must not be null");
        }
        ensureLoaded();
        java.util.List<FormJsonSupport.FieldEntry> entries = new java.util.ArrayList<>();
        for (Field field : fields) {
            String name = field.getPartialName();
            if (name == null || name.isEmpty()) {
                name = field.getFullName();
            }
            if (name == null || name.isEmpty()) {
                continue;
            }
            String value = field.getValue();
            entries.add(new FormJsonSupport.FieldEntry(name, field.getFieldFlags(),
                    value == null ? "" : value));
        }
        String json = FormJsonSupport.toJson(entries, true);
        try {
            jsonStream.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jsonStream.flush();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to write JSON form data", e);
        }
        return json;
    }

    /**
     * Imports field values from a JSON array (as produced by {@link #exportToJson}).
     *
     * @param jsonStream the JSON input stream; must not be {@code null}
     * @return the number of fields whose value was applied (never {@code null})
     */
    @SuppressWarnings("unchecked")
    public Integer importFromJson(java.io.InputStream jsonStream) {
        if (jsonStream == null) {
            throw new IllegalArgumentException("jsonStream must not be null");
        }
        String json;
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = jsonStream.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            json = new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to read JSON form data", e);
        }
        Object parsed = FormJsonSupport.parse(json);
        int applied = 0;
        if (parsed instanceof java.util.List) {
            for (Object item : (java.util.List<Object>) parsed) {
                if (!(item instanceof java.util.Map)) {
                    continue;
                }
                java.util.Map<String, Object> obj = (java.util.Map<String, Object>) item;
                Object name = obj.get("Name");
                Object value = obj.get("Value");
                if (name == null) {
                    continue;
                }
                Field field = get(name.toString());
                if (field != null && value != null) {
                    try {
                        field.setValue(value.toString());
                        applied++;
                    } catch (Exception e) {
                        LOG.fine("importFromJson: could not set '" + name + "': " + e.getMessage());
                    }
                }
            }
        }
        return applied;
    }

    @Override
    public Iterator<Field> iterator() {
        ensureLoaded();
        return fields.iterator();
    }

    /** Form type — detects XFA presence from the /XFA entry in the AcroForm dictionary. */
    public FormType getType() {
        PdfBase xfa = resolveRef(acroFormDict.get("XFA"));
        if (xfa == null) return FormType.Standard;
        return FormType.XFA;
    }

    /**
     * Sets the form type. When set to {@link FormType#Standard} on an XFA form,
     * the XFA template is converted into ordinary AcroForm fields at their laid-out
     * positions (static content painted, dynamic forms paginated) and the /XFA
     * entry is removed — matching Aspose {@code Form.Type = FormType.Standard}
     * semantics. If the conversion cannot run (no document bound, or the XFA data
     * is unusable) the /XFA entry is still removed so the form degrades to the
     * existing /Fields AcroForm content.
     *
     * @param type the desired form type
     */
    public void setType(FormType type) {
        if (type == FormType.Standard) {
            XfaForm xfa = getXFA();
            if (xfa != null && document != null) {
                try {
                    // Convert XFA → AcroForm (DROP policy removes /XFA itself);
                    // invalidate the field cache so converted fields are visible.
                    xfa.convertToAcroForm(document);
                    fields = null;
                    fieldsByName = null;
                } catch (Exception e) {
                    LOG.warning("XFA to AcroForm conversion failed; stripping /XFA only: " + e.getMessage());
                }
            }
            acroFormDict.remove(PdfName.of("XFA"));
            this.xfaForm = null;
        }
    }

    /**
     * Returns the XFA form object for accessing XFA-specific data.
     * Returns null if the form does not contain XFA data.
     *
     * @return the XfaForm, or null if no /XFA entry exists
     */
    public XfaForm getXFA() {
        PdfBase xfa = resolveRef(acroFormDict.get("XFA"));
        if (xfa == null) return null;
        if (xfaForm == null) {
            try {
                xfaForm = new XfaForm(acroFormDict);
            } catch (Exception e) {
                LOG.warning("Failed to parse XFA data: " + e.getMessage());
                return null;
            }
        }
        return xfaForm;
    }

    /** /NeedAppearances */
    public boolean getNeedAppearances() {
        return acroFormDict.getBoolean("NeedAppearances", false);
    }
    public void setNeedAppearances(boolean value) {
        acroFormDict.set(PdfName.of("NeedAppearances"), PdfBoolean.valueOf(value));
    }

    /** /DA — default appearance */
    public String getDefaultAppearance() {
        PdfBase da = acroFormDict.get("DA");
        return (da instanceof PdfString) ? ((PdfString) da).getString() : null;
    }

    /** /DR — default resources */
    public Resources getDefaultResources() {
        PdfBase dr = resolveRef(acroFormDict.get("DR"));
        return (dr instanceof PdfDictionary) ? new Resources((PdfDictionary) dr) : null;
    }

    /** Add a field */
    public void add(Field field) {
        ensureLoaded();
        if (field == null) {
            return;
        }
        ensureDefaultResources();
        if (field.getPage() != null) {
            field.getPdfDictionary().set(PdfName.of("P"), field.getPage().getPdfDictionary());
            field.getPage().getAnnotations().add(field);
        }
        fields.add(field);
        fieldsByName.put(field.getFullName(), field);
        PdfArray fieldsArray = getFieldsArray();
        PdfBase fieldEntry = field.getPdfDictionary();
        if (document != null && fieldEntry.getObjectKey() == null) {
            fieldEntry = document.registerImportedObject(fieldEntry);
        }
        fieldsArray.add(fieldEntry);
    }

    /**
     * Lazy-populates the AcroForm {@code /DR /Font} dictionary with the two
     * Standard-14 entries every variable-text widget needs to resolve its
     * {@code /DA} font selector: {@code /Helv} (Helvetica/WinAnsiEncoding) and
     * {@code /ZaDb} (ZapfDingbats). Without these, poppler/mupdf log
     * "Missing 'Tf' operator in field's DA string" and leave the field blank.
     *
     * <p>Idempotent: a second call leaves existing entries untouched.</p>
     */
    private void ensureDefaultResources() {
        PdfBase drVal = resolveRef(acroFormDict.get("DR"));
        PdfDictionary dr;
        if (drVal instanceof PdfDictionary) {
            dr = (PdfDictionary) drVal;
        } else {
            dr = new PdfDictionary();
            acroFormDict.set(PdfName.of("DR"), dr);
        }
        PdfBase fontsVal = resolveRef(dr.get("Font"));
        PdfDictionary fonts;
        if (fontsVal instanceof PdfDictionary) {
            fonts = (PdfDictionary) fontsVal;
        } else {
            fonts = new PdfDictionary();
            dr.set(PdfName.of("Font"), fonts);
        }
        ensureStandardFont(fonts, "Helv", "Helvetica", "Type1");
        ensureStandardFont(fonts, "ZaDb", "ZapfDingbats", "Type1");

        // Document-wide default appearance (ISO 32000-1 §12.7.2 Table 218).
        // Fields that don't carry their own /DA inherit this; without it
        // poppler/mupdf log "Missing 'Tf' operator in field's DA string" for
        // such fields. Uses /Helv which the /DR above provides.
        if (acroFormDict.get("DA") == null) {
            acroFormDict.set(PdfName.of("DA"), new PdfString("/Helv 0 Tf 0 g"));
        }
    }

    private void ensureStandardFont(PdfDictionary fonts, String resName,
                                    String baseFont, String subtype) {
        if (fonts.get(resName) != null) return;
        PdfDictionary f = new PdfDictionary();
        f.set(PdfName.of("Type"), PdfName.of("Font"));
        f.set(PdfName.of("Subtype"), PdfName.of(subtype));
        f.set(PdfName.of("BaseFont"), PdfName.of(baseFont));
        f.set(PdfName.of("Name"), PdfName.of(resName));
        if (!"ZapfDingbats".equals(baseFont)) {
            f.set(PdfName.of("Encoding"), PdfName.of("WinAnsiEncoding"));
        }
        // Adobe Acrobat regenerates the appearance of EDITABLE (non-read-only)
        // variable-text fields on open, resolving the /DA font against this /DR.
        // It only accepts the default font when it is an INDIRECT object — an
        // inline /DR font dict makes Acrobat's regeneration fail silently and the
        // field shows blank until it first gains focus (read-only fields keep
        // their baked /AP and are unaffected). Register the font as a top-level
        // object so it serialises as an indirect reference.
        PdfBase entry = f;
        if (document != null) {
            try {
                PdfBase ref = document.registerImportedObject(f);
                if (ref != null) {
                    entry = ref;
                }
            } catch (RuntimeException e) {
                LOG.fine("could not register /DR font indirectly: " + e.getMessage());
            }
        }
        fonts.set(PdfName.of(resName), entry);
    }

    /**
     * Adds a field to the specified page (1-based index).
     *
     * @param field     the field to add
     * @param pageNumber the 1-based page number
     */
    public void add(Field field, int pageNumber) {
        if (document != null) {
            try {
                Page page = document.getPages().get(pageNumber);
                if (page != null) {
                    field.setPage(page);
                }
            } catch (Exception e) {
                // ignore
            }
        }
        add(field);
    }

    /**
     * Creates a copy of the specified field, assigns it a new name, places it on the
     * requested page, and adds it to the form.
     * <p>
     * This mirrors the common Aspose API workflow used by regression tests:
     * the original field remains in the form, while the returned field is a newly
     * created copy with an independent PDF dictionary.
     * </p>
     *
     * @param field      the source field to copy
     * @param newName    the name for the copied field
     * @param pageNumber the 1-based target page number
     * @return the newly added copied field
     */
    public Field add(Field field, String newName, int pageNumber) {
        ensureLoaded();
        if (field == null) {
            return null;
        }

        Page page = null;
        if (document != null) {
            try {
                page = document.getPages().get(pageNumber);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid page number: " + pageNumber, e);
            }
        }

        PdfDictionary clonedDict = cloneDictionary(field.getPdfDictionary());
        materializeKidsArray(clonedDict);
        clonedDict.remove(PdfName.of("Parent"));
        clonedDict.set(PdfName.of("T"), new PdfString((newName != null ? newName : "")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        if (page != null) {
            rebindWidgetsToPage(clonedDict, page.getPdfDictionary());
        } else {
            clearWidgetPageReferences(clonedDict);
        }

        PdfBase ft = clonedDict.get("FT");
        if (ft == null) {
            ft = field.getPdfDictionary().get("FT");
        }

        Field copiedField = Field.fromDictionary(clonedDict, ft, newName, page, parser);
        add(copiedField);
        return copiedField;
    }

    /**
     * Returns the number of fields in the form.
     *
     * @return the field count
     */
    public int size() {
        return getCount();
    }

    /** Delete field by name */
    public void delete(String fieldName) {
        ensureLoaded();
        Field field = fieldsByName.get(fieldName);
        if (field == null) {
            return;
        }

        removeWidgets(field.getPdfDictionary());

        while (true) {
            Field removed = fieldsByName.remove(fieldName);
            if (removed == null) {
                break;
            }
            fields.remove(removed);
        }

        PdfArray fieldsArray = getFieldsArray();
        for (int i = fieldsArray.size() - 1; i >= 0; i--) {
            PdfBase item = resolveRef(fieldsArray.get(i));
            if (item == field.getPdfDictionary()) {
                fieldsArray.remove(i);
            }
        }
    }

    /**
     * Flattens the form by baking each field's widget appearance into its page's
     * content stream and then removing the AcroForm /Fields array.
     * <p>
     * For each field, the widget annotation dictionary (the field itself if it has
     * /Rect, or each item in /Kids) is located on its page. If the widget has a
     * normal appearance stream (/AP /N), that appearance is flattened into the
     * page content via {@link Page#flattenAnnotations()}. The field is then removed
     * from the /Fields array.
     * </p>
     *
     * @throws IOException if reading appearance streams or modifying content fails
     */
    public void flatten() throws IOException {
        ensureLoaded();

        // Collect all pages that contain form widget annotations
        Set<Page> pagesToFlatten = new LinkedHashSet<>();
        for (Field field : fields) {
            Page page = field.getPage();
            if (page != null) {
                pagesToFlatten.add(page);
            }
            // Also check /Kids for widget annotations on different pages
            PdfBase kids = resolveRef(field.getPdfDictionary().get("Kids"));
            if (kids instanceof PdfArray) {
                PdfArray kidsArr = (PdfArray) kids;
                for (int i = 0; i < kidsArr.size(); i++) {
                    PdfBase kid = resolveRef(kidsArr.get(i));
                    if (kid instanceof PdfDictionary) {
                        Page kidPage = findPage((PdfDictionary) kid);
                        if (kidPage != null) pagesToFlatten.add(kidPage);
                    }
                }
            }
        }

        // Flatten annotations on each affected page (this bakes AP streams into content)
        for (Page page : pagesToFlatten) {
            page.flattenAnnotations();
        }

        // Clear the fields list and AcroForm /Fields array
        fields.clear();
        fieldsByName.clear();
        PdfArray fieldsArray = getFieldsArray();
        while (fieldsArray.size() > 0) fieldsArray.remove(0);
    }

    /**
     * Flattens the form using the specified settings.
     * <p>
     * Behaves like {@link #flatten()} but allows control over the flattening process
     * via {@link FlattenSettings}, such as whether to update appearances before flattening
     * or whether to hide buttons.
     * </p>
     *
     * @param settings the flatten settings, or null to use defaults
     * @throws IOException if reading appearance streams or modifying content fails
     */
    public void flatten(FlattenSettings settings) throws IOException {
        // For now, delegate to the standard flatten; settings are stored for future use
        flatten();
    }

    /**
     * Returns the flatten settings used by {@link #flatten()}.
     * If no settings have been explicitly set, a default instance is returned.
     *
     * @return the flatten settings (never null)
     */
    public FlattenSettings getFlattenSettings() {
        if (flattenSettings == null) {
            flattenSettings = new FlattenSettings();
        }
        return flattenSettings;
    }

    /**
     * Sets the flatten settings to be used by {@link #flatten()}.
     *
     * @param settings the flatten settings
     */
    public void setFlattenSettings(FlattenSettings settings) {
        this.flattenSettings = settings;
    }

    public PdfDictionary getPdfDictionary() { return acroFormDict; }

    /**
     * Settings that control how form fields are flattened into page content.
     */
    public static class FlattenSettings {

        private boolean applyRedactions = false;
        private boolean hideButtons = false;
        private boolean updateAppearances = true;
        private boolean callEvents = true;

        /**
         * Creates a new FlattenSettings with default values.
         */
        public FlattenSettings() {
        }

        /**
         * Returns whether redaction annotations should be applied during flattening.
         *
         * @return true if redactions are applied
         */
        public boolean isApplyRedactions() {
            return applyRedactions;
        }

        /**
         * Returns whether redaction annotations should be applied during flattening.
         *
         * @return true if redactions are applied
         */
        public boolean getApplyRedactions() {
            return applyRedactions;
        }

        /**
         * Sets whether redaction annotations should be applied during flattening.
         *
         * @param applyRedactions true to apply redactions
         */
        public void setApplyRedactions(boolean applyRedactions) {
            this.applyRedactions = applyRedactions;
        }

        /**
         * Returns whether button fields should be hidden (not rendered) during flattening.
         *
         * @return true if buttons are hidden
         */
        public boolean isHideButtons() {
            return hideButtons;
        }

        /**
         * Returns whether button fields should be hidden (not rendered) during flattening.
         *
         * @return true if buttons are hidden
         */
        public boolean getHideButtons() {
            return hideButtons;
        }

        /**
         * Sets whether button fields should be hidden (not rendered) during flattening.
         *
         * @param hideButtons true to hide buttons
         */
        public void setHideButtons(boolean hideButtons) {
            this.hideButtons = hideButtons;
        }

        /**
         * Returns whether field appearances should be updated before flattening.
         *
         * @return true if appearances are updated
         */
        public boolean isUpdateAppearances() {
            return updateAppearances;
        }

        /**
         * Returns whether field appearances should be updated before flattening.
         *
         * @return true if appearances are updated
         */
        public boolean getUpdateAppearances() {
            return updateAppearances;
        }

        /**
         * Sets whether field appearances should be updated before flattening.
         *
         * @param updateAppearances true to update appearances
         */
        public void setUpdateAppearances(boolean updateAppearances) {
            this.updateAppearances = updateAppearances;
        }

        /**
         * Returns whether events should be triggered during flattening.
         *
         * @return true if events are called
         */
        public boolean isCallEvents() {
            return callEvents;
        }

        /**
         * Returns whether events should be triggered during flattening.
         *
         * @return true if events are called
         */
        public boolean getCallEvents() {
            return callEvents;
        }

        /**
         * Sets whether events should be triggered during flattening.
         *
         * @param callEvents true to call events
         */
        public void setCallEvents(boolean callEvents) {
            this.callEvents = callEvents;
        }
    }

    /**
     * Form type enumeration.
     */
    public enum FormType {
        /** Pure AcroForm, no XFA. */
        Standard,
        /** XFA static form (XFA foreground over PDF background). */
        Static,
        /** XFA dynamic form (fully XFA-driven layout). */
        Dynamic,
        /** Generic XFA (when static/dynamic distinction is not determinable). */
        XFA
    }

    // ── Internal ──

    private PdfArray getFieldsArray() {
        PdfBase f = resolveRef(acroFormDict.get("Fields"));
        if (f instanceof PdfArray) return (PdfArray) f;
        PdfArray arr = new PdfArray();
        acroFormDict.set(PdfName.of("Fields"), arr);
        return arr;
    }

    /**
     * Drops the cached field index so the next field-access call rescans
     * {@code /AcroForm/Fields}. Call after structurally mutating the AcroForm
     * dictionary outside this Form facade (e.g. {@link org.aspose.pdf.facades.FormEditor#copyOuterField}).
     */
    public void invalidate() {
        this.fields = null;
        this.fieldsByName = null;
    }

    private void ensureLoaded() {
        if (fields != null) return;
        fields = new ArrayList<>();
        fieldsByName = new HashMap<>();

        PdfBase fieldsRef = acroFormDict.get("Fields");
        PdfBase resolved = resolveRef(fieldsRef);
        if (!(resolved instanceof PdfArray)) return;

        collectFields((PdfArray) resolved, null, "");
    }

    private void collectFields(PdfArray fieldsArray, PdfDictionary parent, String parentName) {
        for (int i = 0; i < fieldsArray.size(); i++) {
            PdfBase item = resolveRef(fieldsArray.get(i));
            if (!(item instanceof PdfDictionary)) continue;
            PdfDictionary fieldDict = (PdfDictionary) item;

            String partialName = getStringValue(fieldDict, "T");
            String fullName;
            if (parentName.isEmpty()) {
                fullName = partialName != null ? partialName : "";
            } else {
                fullName = partialName != null ? parentName + "." + partialName : parentName;
            }

            PdfBase ft = fieldDict.get("FT");
            if (ft == null && parent != null) ft = parent.get("FT");

            PdfBase kids = resolveRef(fieldDict.get("Kids"));

            if (kids instanceof PdfArray) {
                PdfArray kidsArray = (PdfArray) kids;
                boolean hasFieldKids = false;
                for (int j = 0; j < kidsArray.size(); j++) {
                    PdfBase kid = resolveRef(kidsArray.get(j));
                    if (kid instanceof PdfDictionary && ((PdfDictionary) kid).get("T") != null) {
                        hasFieldKids = true;
                        break;
                    }
                }
                if (hasFieldKids) {
                    collectFields(kidsArray, fieldDict, fullName);
                } else {
                    Field field = Field.fromDictionary(fieldDict, ft, fullName, findPage(fieldDict), parser);
                    fields.add(field);
                    fieldsByName.put(fullName, field);
                }
            } else {
                Field field = Field.fromDictionary(fieldDict, ft, fullName, findPage(fieldDict), parser);
                fields.add(field);
                fieldsByName.put(fullName, field);
            }
        }
    }

    private Page findPage(PdfDictionary fieldDict) {
        if (document == null) return null;
        Page byP = pageForDict(resolveRef(fieldDict.get("P")));
        if (byP != null) return byP;
        // A parent field (e.g. a radio group) carries no /P of its own — its
        // widget kids do. Fall back to the first kid's page; failing that, scan
        // the pages' /Annots arrays for one of the kids (PDFNET_46293).
        PdfBase kids = resolveRef(fieldDict.get("Kids"));
        if (kids instanceof PdfArray) {
            for (PdfBase kid : (PdfArray) kids) {
                PdfBase kd = resolveRef(kid);
                if (kd instanceof PdfDictionary) {
                    Page kidPage = pageForDict(resolveRef(((PdfDictionary) kd).get("P")));
                    if (kidPage != null) return kidPage;
                }
            }
        }
        // Merged field+widget without /P, or kids without /P: find the page whose
        // /Annots array contains the field dict itself or one of its kids.
        try {
            PageCollection pages = document.getPages();
            for (int i = 1; i <= pages.getCount(); i++) {
                PdfBase annots = resolveRef(pages.get(i).getPdfDictionary().get("Annots"));
                if (!(annots instanceof PdfArray)) continue;
                for (PdfBase a : (PdfArray) annots) {
                    PdfBase ad = resolveRef(a);
                    if (ad == fieldDict) return pages.get(i);
                    if (kids instanceof PdfArray) {
                        for (PdfBase kid : (PdfArray) kids) {
                            if (ad != null && ad == resolveRef(kid)) return pages.get(i);
                        }
                    }
                }
            }
        } catch (IOException e) { /* ignore */ }
        return null;
    }

    /** Resolves a page dictionary to its Page, or null. */
    private Page pageForDict(PdfBase p) {
        if (!(p instanceof PdfDictionary) || document == null) return null;
        try {
            PageCollection pages = document.getPages();
            for (int i = 1; i <= pages.getCount(); i++) {
                if (pages.get(i).getPdfDictionary() == p) return pages.get(i);
            }
        } catch (IOException e) { /* ignore */ }
        return null;
    }

    private String getStringValue(PdfDictionary dict, String key) {
        PdfBase val = dict.get(key);
        if (val instanceof PdfString) return ((PdfString) val).getString();
        if (val instanceof PdfName) return ((PdfName) val).getName();
        return null;
    }

    private PdfBase resolveRef(PdfBase val) {
        if (val instanceof PdfObjectReference) {
            try { return ((PdfObjectReference) val).dereference(); }
            catch (Exception e) { return null; }
        }
        return val;
    }

    private static PdfDictionary cloneDictionary(PdfDictionary source) {
        return (PdfDictionary) deepClone(source, new java.util.IdentityHashMap<>());
    }

    /**
     * Deep-clones a direct object graph. Field dictionaries can contain direct
     * back-pointers (a widget kid's /Parent, a /P page dict), so the clone maps
     * every visited node to its copy and reuses it on revisit — cycles terminate
     * and shared substructure stays shared instead of exploding the recursion
     * (StackOverflow on XFA-converted forms during concatenate).
     */
    private static PdfBase deepClone(PdfBase value, java.util.IdentityHashMap<PdfBase, PdfBase> seen) {
        if (value == null) {
            return null;
        }
        PdfBase already = seen.get(value);
        if (already != null) {
            return already;
        }
        if (value instanceof PdfStream) {
            PdfStream stream = (PdfStream) value;
            PdfDictionary dictCopy = new PdfDictionary();
            PdfStream copy = new PdfStream(dictCopy, stream.getEncodedData());
            copy.setObjectKey(null);
            seen.put(value, copy);
            for (Map.Entry<PdfName, PdfBase> entry : stream) {
                copy.set(entry.getKey(), deepClone(entry.getValue(), seen));
            }
            return copy;
        }
        if (value instanceof PdfDictionary) {
            PdfDictionary copy = new PdfDictionary();
            seen.put(value, copy);
            for (Map.Entry<PdfName, PdfBase> entry : (PdfDictionary) value) {
                copy.set(entry.getKey(), deepClone(entry.getValue(), seen));
            }
            return copy;
        }
        if (value instanceof PdfArray) {
            PdfArray sourceArray = (PdfArray) value;
            PdfArray copy = new PdfArray(sourceArray.size());
            seen.put(value, copy);
            for (PdfBase item : sourceArray) {
                copy.add(deepClone(item, seen));
            }
            return copy;
        }
        if (value instanceof PdfString) {
            return new PdfString(((PdfString) value).getBytes());
        }
        return value;
    }

    private static void rebindWidgetsToPage(PdfDictionary fieldDict, PdfDictionary pageDict) {
        fieldDict.set(PdfName.of("P"), pageDict);
        PdfBase kids = fieldDict.get("Kids");
        if (kids instanceof PdfArray) {
            PdfArray kidsArray = (PdfArray) kids;
            for (PdfBase kid : kidsArray) {
                if (kid instanceof PdfDictionary) {
                    PdfDictionary kidDict = (PdfDictionary) kid;
                    kidDict.set(PdfName.of("Parent"), fieldDict);
                    kidDict.set(PdfName.of("P"), pageDict);
                    kidDict.remove(PdfName.of("T"));
                    kidDict.remove(PdfName.of("FT"));
                }
            }
        }
    }

    private static void materializeKidsArray(PdfDictionary fieldDict) {
        PdfBase kids = fieldDict.get("Kids");
        if (!(kids instanceof PdfArray)) {
            return;
        }
        PdfArray kidsArray = (PdfArray) kids;
        for (int i = 0; i < kidsArray.size(); i++) {
            PdfBase kid = kidsArray.get(i);
            if (kid instanceof PdfObjectReference) {
                try {
                    kid = ((PdfObjectReference) kid).dereference();
                } catch (Exception e) {
                    continue;
                }
            }
            if (kid instanceof PdfDictionary) {
                kidsArray.set(i, cloneDictionary((PdfDictionary) kid));
            }
        }
    }

    private static void clearWidgetPageReferences(PdfDictionary fieldDict) {
        fieldDict.remove(PdfName.of("P"));
        PdfBase kids = fieldDict.get("Kids");
        if (kids instanceof PdfArray) {
            PdfArray kidsArray = (PdfArray) kids;
            for (PdfBase kid : kidsArray) {
                if (kid instanceof PdfDictionary) {
                    ((PdfDictionary) kid).remove(PdfName.of("P"));
                }
            }
        }
    }

    private void removeWidgets(PdfDictionary fieldDict) {
        if (fieldDict == null) {
            return;
        }
        removeWidgetAnnotation(fieldDict);
        PdfBase kids = resolveRef(fieldDict.get("Kids"));
        if (kids instanceof PdfArray) {
            PdfArray kidsArray = (PdfArray) kids;
            for (int i = 0; i < kidsArray.size(); i++) {
                PdfBase kid = resolveRef(kidsArray.get(i));
                if (kid instanceof PdfDictionary) {
                    removeWidgetAnnotation((PdfDictionary) kid);
                }
            }
        }
    }

    private void removeWidgetAnnotation(PdfDictionary widgetDict) {
        Page page = findPage(widgetDict);
        if (page == null) {
            return;
        }
        page.getAnnotations().delete(Annotation.fromDictionary(widgetDict, page));
    }
}
