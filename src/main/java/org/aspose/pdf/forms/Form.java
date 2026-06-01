package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.engine.cos.*;
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

    private final COSDictionary acroFormDict;
    private final Document document;
    private final PDFParser parser;
    private List<Field> fields;
    private Map<String, Field> fieldsByName;
    private XfaForm xfaForm;
    private FlattenSettings flattenSettings;

    public Form(COSDictionary acroFormDict, Document document, PDFParser parser) {
        this.acroFormDict = acroFormDict != null ? acroFormDict : new COSDictionary();
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

    @Override
    public Iterator<Field> iterator() {
        ensureLoaded();
        return fields.iterator();
    }

    /** Form type — detects XFA presence from the /XFA entry in the AcroForm dictionary. */
    public FormType getType() {
        COSBase xfa = resolveRef(acroFormDict.get("XFA"));
        if (xfa == null) return FormType.Standard;
        return FormType.XFA;
    }

    /**
     * Sets the form type. When set to {@link FormType#Standard}, the /XFA entry
     * is removed from the AcroForm dictionary, converting the form to pure AcroForm.
     * The existing /Fields array with AcroForm fields remains intact.
     *
     * @param type the desired form type
     */
    public void setType(FormType type) {
        if (type == FormType.Standard) {
            acroFormDict.remove(COSName.of("XFA"));
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
        COSBase xfa = resolveRef(acroFormDict.get("XFA"));
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
        acroFormDict.set(COSName.of("NeedAppearances"), COSBoolean.valueOf(value));
    }

    /** /DA — default appearance */
    public String getDefaultAppearance() {
        COSBase da = acroFormDict.get("DA");
        return (da instanceof COSString) ? ((COSString) da).getString() : null;
    }

    /** /DR — default resources */
    public Resources getDefaultResources() {
        COSBase dr = resolveRef(acroFormDict.get("DR"));
        return (dr instanceof COSDictionary) ? new Resources((COSDictionary) dr) : null;
    }

    /** Add a field */
    public void add(Field field) {
        ensureLoaded();
        if (field == null) {
            return;
        }
        ensureDefaultResources();
        if (field.getPage() != null) {
            field.getCOSDictionary().set(COSName.of("P"), field.getPage().getCOSDictionary());
            field.getPage().getAnnotations().add(field);
        }
        fields.add(field);
        fieldsByName.put(field.getFullName(), field);
        COSArray fieldsArray = getFieldsArray();
        COSBase fieldEntry = field.getCOSDictionary();
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
        COSBase drVal = resolveRef(acroFormDict.get("DR"));
        COSDictionary dr;
        if (drVal instanceof COSDictionary) {
            dr = (COSDictionary) drVal;
        } else {
            dr = new COSDictionary();
            acroFormDict.set(COSName.of("DR"), dr);
        }
        COSBase fontsVal = resolveRef(dr.get("Font"));
        COSDictionary fonts;
        if (fontsVal instanceof COSDictionary) {
            fonts = (COSDictionary) fontsVal;
        } else {
            fonts = new COSDictionary();
            dr.set(COSName.of("Font"), fonts);
        }
        ensureStandardFont(fonts, "Helv", "Helvetica", "Type1");
        ensureStandardFont(fonts, "ZaDb", "ZapfDingbats", "Type1");

        // Document-wide default appearance (ISO 32000-1 §12.7.2 Table 218).
        // Fields that don't carry their own /DA inherit this; without it
        // poppler/mupdf log "Missing 'Tf' operator in field's DA string" for
        // such fields. Uses /Helv which the /DR above provides.
        if (acroFormDict.get("DA") == null) {
            acroFormDict.set(COSName.of("DA"), new COSString("/Helv 0 Tf 0 g"));
        }
    }

    private static void ensureStandardFont(COSDictionary fonts, String resName,
                                           String baseFont, String subtype) {
        if (fonts.get(resName) != null) return;
        COSDictionary f = new COSDictionary();
        f.set(COSName.of("Type"), COSName.of("Font"));
        f.set(COSName.of("Subtype"), COSName.of(subtype));
        f.set(COSName.of("BaseFont"), COSName.of(baseFont));
        if (!"ZapfDingbats".equals(baseFont)) {
            f.set(COSName.of("Encoding"), COSName.of("WinAnsiEncoding"));
        }
        fonts.set(COSName.of(resName), f);
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
     * created copy with an independent COS dictionary.
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

        COSDictionary clonedDict = cloneDictionary(field.getCOSDictionary());
        materializeKidsArray(clonedDict);
        clonedDict.remove(COSName.of("Parent"));
        clonedDict.set(COSName.of("T"), new COSString((newName != null ? newName : "")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        if (page != null) {
            rebindWidgetsToPage(clonedDict, page.getCOSDictionary());
        } else {
            clearWidgetPageReferences(clonedDict);
        }

        COSBase ft = clonedDict.get("FT");
        if (ft == null) {
            ft = field.getCOSDictionary().get("FT");
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

        removeWidgets(field.getCOSDictionary());

        while (true) {
            Field removed = fieldsByName.remove(fieldName);
            if (removed == null) {
                break;
            }
            fields.remove(removed);
        }

        COSArray fieldsArray = getFieldsArray();
        for (int i = fieldsArray.size() - 1; i >= 0; i--) {
            COSBase item = resolveRef(fieldsArray.get(i));
            if (item == field.getCOSDictionary()) {
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
            COSBase kids = resolveRef(field.getCOSDictionary().get("Kids"));
            if (kids instanceof COSArray) {
                COSArray kidsArr = (COSArray) kids;
                for (int i = 0; i < kidsArr.size(); i++) {
                    COSBase kid = resolveRef(kidsArr.get(i));
                    if (kid instanceof COSDictionary) {
                        Page kidPage = findPage((COSDictionary) kid);
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
        COSArray fieldsArray = getFieldsArray();
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

    public COSDictionary getCOSDictionary() { return acroFormDict; }

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

    private COSArray getFieldsArray() {
        COSBase f = resolveRef(acroFormDict.get("Fields"));
        if (f instanceof COSArray) return (COSArray) f;
        COSArray arr = new COSArray();
        acroFormDict.set(COSName.of("Fields"), arr);
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

        COSBase fieldsRef = acroFormDict.get("Fields");
        COSBase resolved = resolveRef(fieldsRef);
        if (!(resolved instanceof COSArray)) return;

        collectFields((COSArray) resolved, null, "");
    }

    private void collectFields(COSArray fieldsArray, COSDictionary parent, String parentName) {
        for (int i = 0; i < fieldsArray.size(); i++) {
            COSBase item = resolveRef(fieldsArray.get(i));
            if (!(item instanceof COSDictionary)) continue;
            COSDictionary fieldDict = (COSDictionary) item;

            String partialName = getStringValue(fieldDict, "T");
            String fullName;
            if (parentName.isEmpty()) {
                fullName = partialName != null ? partialName : "";
            } else {
                fullName = partialName != null ? parentName + "." + partialName : parentName;
            }

            COSBase ft = fieldDict.get("FT");
            if (ft == null && parent != null) ft = parent.get("FT");

            COSBase kids = resolveRef(fieldDict.get("Kids"));

            if (kids instanceof COSArray) {
                COSArray kidsArray = (COSArray) kids;
                boolean hasFieldKids = false;
                for (int j = 0; j < kidsArray.size(); j++) {
                    COSBase kid = resolveRef(kidsArray.get(j));
                    if (kid instanceof COSDictionary && ((COSDictionary) kid).get("T") != null) {
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

    private Page findPage(COSDictionary fieldDict) {
        if (document == null) return null;
        COSBase p = resolveRef(fieldDict.get("P"));
        if (p instanceof COSDictionary) {
            try {
                PageCollection pages = document.getPages();
                for (int i = 1; i <= pages.getCount(); i++) {
                    if (pages.get(i).getCOSDictionary() == p) return pages.get(i);
                }
            } catch (IOException e) { /* ignore */ }
        }
        return null;
    }

    private String getStringValue(COSDictionary dict, String key) {
        COSBase val = dict.get(key);
        if (val instanceof COSString) return ((COSString) val).getString();
        if (val instanceof COSName) return ((COSName) val).getName();
        return null;
    }

    private COSBase resolveRef(COSBase val) {
        if (val instanceof COSObjectReference) {
            try { return ((COSObjectReference) val).dereference(); }
            catch (Exception e) { return null; }
        }
        return val;
    }

    private static COSDictionary cloneDictionary(COSDictionary source) {
        COSDictionary copy = new COSDictionary();
        for (Map.Entry<COSName, COSBase> entry : source) {
            copy.set(entry.getKey(), deepClone(entry.getValue()));
        }
        return copy;
    }

    private static COSBase deepClone(COSBase value) {
        if (value == null) {
            return null;
        }
        if (value instanceof COSDictionary && !(value instanceof COSStream)) {
            return cloneDictionary((COSDictionary) value);
        }
        if (value instanceof COSStream) {
            COSStream stream = (COSStream) value;
            COSStream copy = new COSStream(cloneDictionary(stream), stream.getEncodedData());
            copy.setObjectKey(null);
            return copy;
        }
        if (value instanceof COSArray) {
            COSArray sourceArray = (COSArray) value;
            COSArray copy = new COSArray(sourceArray.size());
            for (COSBase item : sourceArray) {
                copy.add(deepClone(item));
            }
            return copy;
        }
        if (value instanceof COSString) {
            return new COSString(((COSString) value).getBytes());
        }
        return value;
    }

    private static void rebindWidgetsToPage(COSDictionary fieldDict, COSDictionary pageDict) {
        fieldDict.set(COSName.of("P"), pageDict);
        COSBase kids = fieldDict.get("Kids");
        if (kids instanceof COSArray) {
            COSArray kidsArray = (COSArray) kids;
            for (COSBase kid : kidsArray) {
                if (kid instanceof COSDictionary) {
                    COSDictionary kidDict = (COSDictionary) kid;
                    kidDict.set(COSName.of("Parent"), fieldDict);
                    kidDict.set(COSName.of("P"), pageDict);
                    kidDict.remove(COSName.of("T"));
                    kidDict.remove(COSName.of("FT"));
                }
            }
        }
    }

    private static void materializeKidsArray(COSDictionary fieldDict) {
        COSBase kids = fieldDict.get("Kids");
        if (!(kids instanceof COSArray)) {
            return;
        }
        COSArray kidsArray = (COSArray) kids;
        for (int i = 0; i < kidsArray.size(); i++) {
            COSBase kid = kidsArray.get(i);
            if (kid instanceof COSObjectReference) {
                try {
                    kid = ((COSObjectReference) kid).dereference();
                } catch (Exception e) {
                    continue;
                }
            }
            if (kid instanceof COSDictionary) {
                kidsArray.set(i, cloneDictionary((COSDictionary) kid));
            }
        }
    }

    private static void clearWidgetPageReferences(COSDictionary fieldDict) {
        fieldDict.remove(COSName.of("P"));
        COSBase kids = fieldDict.get("Kids");
        if (kids instanceof COSArray) {
            COSArray kidsArray = (COSArray) kids;
            for (COSBase kid : kidsArray) {
                if (kid instanceof COSDictionary) {
                    ((COSDictionary) kid).remove(COSName.of("P"));
                }
            }
        }
    }

    private void removeWidgets(COSDictionary fieldDict) {
        if (fieldDict == null) {
            return;
        }
        removeWidgetAnnotation(fieldDict);
        COSBase kids = resolveRef(fieldDict.get("Kids"));
        if (kids instanceof COSArray) {
            COSArray kidsArray = (COSArray) kids;
            for (int i = 0; i < kidsArray.size(); i++) {
                COSBase kid = resolveRef(kidsArray.get(i));
                if (kid instanceof COSDictionary) {
                    removeWidgetAnnotation((COSDictionary) kid);
                }
            }
        }
    }

    private void removeWidgetAnnotation(COSDictionary widgetDict) {
        Page page = findPage(widgetDict);
        if (page == null) {
            return;
        }
        page.getAnnotations().delete(Annotation.fromDictionary(widgetDict, page));
    }
}
