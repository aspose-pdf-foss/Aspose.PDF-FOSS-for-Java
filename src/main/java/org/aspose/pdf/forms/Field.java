package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.annotations.Border;
import org.aspose.pdf.annotations.WidgetAnnotation;
import org.aspose.pdf.engine.cos.*;
import org.aspose.pdf.engine.parser.PDFParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Abstract base for all form fields (ISO 32000-1:2008, §12.7.3).
 * <p>
 * Extends {@link WidgetAnnotation} since in PDF a form field and its widget
 * annotation are often merged into the same dictionary. Subclasses represent
 * specific field types: text, checkbox, radio, combo, list, button, and signature.
 * </p>
 */
public abstract class
Field extends WidgetAnnotation implements Iterable<Field> {

    private static final Logger LOG = Logger.getLogger(Field.class.getName());

    /** The fully-qualified dotted field name. */
    protected String fullName;

    /** The border of this form field. */
    private Border border;

    /** Cached list of child fields loaded from /Kids. */
    private List<Field> childFields;

    /**
     * Constructs a field from an existing COS dictionary.
     *
     * @param dict     the COS dictionary backing this field
     * @param page     the page this field belongs to (may be null)
     * @param fullName the fully-qualified dotted name of the field
     */
    protected Field(COSDictionary dict, Page page, String fullName) {
        super(dict, page);
        this.fullName = fullName != null ? fullName : "";
    }

    /**
     * Returns the border of this form field.
     *
     * @return the border, or null if not set
     */
    public Border getBorder() { return border; }

    /**
     * Sets the border of this form field.
     *
     * @param border the border to set
     */
    public void setBorder(Border border) { this.border = border; }

    /** Actions collection for this field. */
    private org.aspose.pdf.annotations.AnnotationActionCollection actions;

    /**
     * Returns the action collection for this field.
     *
     * @return the annotation action collection
     */
    public org.aspose.pdf.annotations.AnnotationActionCollection getActions() {
        if (actions == null) {
            actions = new org.aspose.pdf.annotations.AnnotationActionCollection();
        }
        return actions;
    }

    /**
     * Sets the width of the field widget by adjusting the annotation rectangle.
     *
     * @param width the width in user-space units
     */
    public void setWidth(double width) {
        // Clamp width to a strictly positive value — Annotation.setRect rejects
        // degenerate rectangles per ISO 32000-1:2008 §12.5.2.
        double w = Math.max(width, 1);
        Rectangle r = getRect();
        if (r != null) {
            setRect(new Rectangle(r.getLLX(), r.getLLY(), r.getLLX() + w, r.getURY()));
        } else {
            // No prior rect: install a placeholder with positive height so
            // setRect's validation passes. A subsequent setHeight call can
            // overwrite the placeholder dimension.
            setRect(new Rectangle(0, 0, w, 1));
        }
    }

    /**
     * Sets the height of the field widget by adjusting the annotation rectangle.
     *
     * @param height the height in user-space units
     */
    public void setHeight(double height) {
        // See setWidth for rationale on the positive-area clamp.
        double h = Math.max(height, 1);
        Rectangle r = getRect();
        if (r != null) {
            setRect(new Rectangle(r.getLLX(), r.getLLY(), r.getURX(), r.getLLY() + h));
        } else {
            setRect(new Rectangle(0, 0, 1, h));
        }
    }

    /**
     * Returns the partial field name (/T entry).
     *
     * @return the partial name, or null if not set
     */
    public String getPartialName() {
        COSBase t = dict.get("T");
        return (t instanceof COSString) ? ((COSString) t).getString() : null;
    }

    /**
     * Sets the partial field name (/T entry).
     *
     * @param name the partial name
     */
    public void setPartialName(String name) {
        // Same BUG-054 path as setValue — let COSString(String) decide between
        // PDFDocEncoding and UTF-16BE+BOM so non-Latin partial names round-trip.
        dict.set(COSName.of("T"), new COSString(name));
    }

    /**
     * Returns the fully-qualified dotted name of this field.
     *
     * @return the full name
     */
    public String getFullName() {
        // If fullName was not set at construction, derive from partial name
        if ((fullName == null || fullName.isEmpty()) && getPartialName() != null) {
            return getPartialName();
        }
        return fullName;
    }

    /**
     * Returns the alternate (tooltip) name (/TU entry).
     *
     * @return the alternate name, or null if not set
     */
    public String getAlternateName() {
        COSBase tu = dict.get("TU");
        return (tu instanceof COSString) ? ((COSString) tu).getString() : null;
    }

    /**
     * Returns the field value (/V entry) as a string.
     * <p>
     * If /V is a {@link COSString}, its string value is returned.
     * If /V is a {@link COSName}, the name string is returned.
     * Otherwise null.
     * </p>
     *
     * @return the value string, or null
     */
    public String getValue() {
        COSBase v = dict.get("V");
        if (v instanceof COSString) return ((COSString) v).getString();
        if (v instanceof COSName) return ((COSName) v).getName();
        return null;
    }

    /**
     * Sets the field value (/V entry).
     *
     * @param value the value string, or null to remove
     */
    public void setValue(String value) {
        if (value != null) {
            // BUG-054 fix: route via COSString(String) so it picks PDFDocEncoding
            // for ASCII/Win-1252 and falls back to UTF-16BE+BOM for non-Latin text
            // (Cyrillic, CJK, etc. — see ISO 32000-1 §7.9.2.2). The previous
            // raw UTF-8 byte path stored multi-byte sequences with no BOM, so
            // getString() decoded them as PDFDocEncoding garbage on read-back.
            dict.set(COSName.of("V"), new COSString(value));
        } else {
            dict.remove(COSName.of("V"));
        }
    }

    /**
     * Returns the default value (/DV entry) as a string.
     *
     * @return the default value, or null
     */
    public String getDefaultValue() {
        COSBase dv = dict.get("DV");
        if (dv instanceof COSString) return ((COSString) dv).getString();
        if (dv instanceof COSName) return ((COSName) dv).getName();
        return null;
    }

    /**
     * Returns the field flags (/Ff entry).
     *
     * @return the flags integer (0 if not set)
     */
    public int getFieldFlags() {
        return dict.getInt("Ff", 0);
    }

    /**
     * Sets the field flags (/Ff entry).
     *
     * @param flags the flags integer
     */
    public void setFieldFlags(int flags) {
        dict.set(COSName.of("Ff"), COSInteger.valueOf(flags));
    }

    /**
     * Returns whether this field is read-only (bit 1 of /Ff).
     *
     * @return true if read-only
     */
    public boolean isReadOnly() {
        return (getFieldFlags() & 1) != 0;
    }

    /**
     * Returns whether this field is required (bit 2 of /Ff).
     *
     * @return true if required
     */
    public boolean isRequired() {
        return (getFieldFlags() & 2) != 0;
    }

    /**
     * Returns whether this field is excluded from export (bit 3 of /Ff).
     *
     * @return true if no-export
     */
    public boolean isNoExport() {
        return (getFieldFlags() & 4) != 0;
    }

    /**
     * Returns the default appearance string (/DA entry).
     *
     * @return the DA string, or null
     */
    public String getDefaultAppearance() {
        COSBase da = dict.get("DA");
        return (da instanceof COSString) ? ((COSString) da).getString() : null;
    }

    /**
     * Sets the field's default appearance string (/DA entry).
     *
     * <p>Per ISO 32000-1:2008 §12.7.3.3, /DA shall contain at least a font
     * selector (Tf) and a colour-setting operator. For example
     * {@code "/Helv 12 Tf 0 g"} selects the Helvetica resource named
     * {@code "Helv"} at 12pt and sets non-stroking colour to black.</p>
     *
     * <p>The font resource name referenced here must be present in the
     * AcroForm's {@code /DR /Font} dictionary; {@link Form#add(Field)}
     * lazily populates {@code /DR} with {@code /Helv} (Helvetica) and
     * {@code /ZaDb} (ZapfDingbats) entries.</p>
     *
     * @param da the /DA string, or {@code null} to clear the entry
     */
    public void setDefaultAppearance(String da) {
        if (da == null) {
            dict.remove(COSName.of("DA"));
        } else {
            dict.set(COSName.of("DA"), new COSString(da));
        }
    }

    /**
     * Returns the appearance characteristics helper backed by the /MK dictionary.
     *
     * @return the appearance characteristics wrapper
     */
    public AppearanceCharacteristics getCharacteristics() {
        return new AppearanceCharacteristics(dict);
    }

    /**
     * Returns a typed view over this field's {@code /AP} appearance dictionary
     * (ISO 32000-1:2008 §12.5.5).
     *
     * <p>For multi-state fields (checkbox, radio), iterate with
     * {@link AppearanceDictionary#getStateNames()} and fetch each with
     * {@link AppearanceDictionary#get(String)}. For single-state fields (text,
     * button), use {@link AppearanceDictionary#getNormal()}.</p>
     *
     * <p>The /AP sub-dictionary is lazily created if absent so this method
     * never returns {@code null}.</p>
     *
     * @return the typed appearance dictionary (never null)
     */
    public AppearanceDictionary getAppearance() {
        COSBase ap = dict.get(COSName.of("AP"));
        COSDictionary apDict;
        if (ap instanceof COSDictionary) {
            apDict = (COSDictionary) ap;
        } else {
            apDict = new COSDictionary();
            dict.set(COSName.of("AP"), apDict);
        }
        return new AppearanceDictionary(apDict);
    }

    /**
     * Factory method: creates the appropriate {@link Field} subclass from a COS dictionary.
     *
     * @param dict     the field dictionary
     * @param ftObj    the field type COS object (may be null)
     * @param fullName the fully-qualified field name
     * @param page     the page (may be null)
     * @param parser   the PDF parser (may be null)
     * @return the concrete field instance
     */
    public static Field fromDictionary(COSDictionary dict, COSBase ftObj, String fullName,
                                       Page page, PDFParser parser) {
        String ft = null;
        if (ftObj instanceof COSName) ft = ((COSName) ftObj).getName();
        if (ft == null) ft = dict.getNameAsString("FT");
        if (ft == null) ft = "";

        switch (ft) {
            case "Tx":
                return new TextBoxField(dict, page, fullName);
            case "Btn":
                return createButtonField(dict, page, fullName);
            case "Ch":
                return createChoiceField(dict, page, fullName);
            case "Sig":
                return new SignatureField(dict, page, fullName);
            default:
                return new TextBoxField(dict, page, fullName);
        }
    }

    /**
     * Creates the appropriate button field subclass based on /Ff flags.
     */
    private static Field createButtonField(COSDictionary dict, Page page, String fullName) {
        int ff = dict.getInt("Ff", 0);
        if ((ff & (1 << 16)) != 0) return new ButtonField(dict, page, fullName);
        if ((ff & (1 << 15)) != 0) return new RadioButtonField(dict, page, fullName);
        return new CheckboxField(dict, page, fullName);
    }

    /**
     * Creates the appropriate choice field subclass based on /Ff flags.
     */
    private static Field createChoiceField(COSDictionary dict, Page page, String fullName) {
        int ff = dict.getInt("Ff", 0);
        if ((ff & (1 << 17)) != 0) return new ComboBoxField(dict, page, fullName);
        return new ListBoxField(dict, page, fullName);
    }

    /**
     * Returns the number of child fields (sub-widgets) of this field.
     * <p>
     * Returns the number of entries in the /Kids array that represent
     * field dictionaries. For leaf fields with no children, returns 0.
     * </p>
     *
     * @return the number of child fields
     */
    public int getCount() {
        return ensureChildFields().size();
    }

    /**
     * Returns the child field at the given 1-based index.
     *
     * @param index the 1-based index
     * @return the child field
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public Field get(int index) {
        List<Field> children = ensureChildFields();
        if (index < 1 || index > children.size()) {
            throw new IndexOutOfBoundsException(
                    "Index " + index + " out of [1," + children.size() + "]");
        }
        return children.get(index - 1);
    }

    /**
     * Returns an iterator over child fields (sub-widgets) of this field.
     * <p>
     * Iterates over the /Kids array entries that represent field or widget
     * annotation dictionaries. For leaf fields with no children, this returns
     * an empty iterator.
     * </p>
     *
     * @return an iterator over child fields
     */
    @Override
    public Iterator<Field> iterator() {
        return ensureChildFields().iterator();
    }

    /**
     * Returns the 1-based page index on which this field's widget annotation resides.
     * <p>
     * Looks up the page associated with this field via its /P entry or the page
     * set during field loading. Returns 0 if the page cannot be determined.
     * </p>
     *
     * @return the 1-based page index, or 0 if unknown
     */
    public int getPageIndex() {
        Page p = getPage();
        if (p != null) {
            return p.getNumber();
        }
        return 0;
    }

    /**
     * Returns the 0-based annotation index of this field within its page's
     * annotation array. Returns -1 if not found or the page is unknown.
     *
     * @return the 0-based annotation index, or -1
     */
    public int getAnnotationIndex() {
        Page p = getPage();
        if (p == null) return -1;
        try {
            var annots = p.getAnnotations();
            if (annots != null) {
                for (int i = 1; i <= annots.size(); i++) {
                    if (annots.get(i).getCOSDictionary() == this.dict) {
                        return i - 1;
                    }
                }
            }
        } catch (Exception e) {
            LOG.fine("Could not determine annotation index: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Loads and caches child fields from the /Kids array.
     *
     * @return the list of child fields (never null)
     */
    private List<Field> ensureChildFields() {
        if (childFields != null) return childFields;
        childFields = new ArrayList<>();
        COSBase kids = dict.get("Kids");
        if (kids instanceof COSObjectReference) {
            try { kids = ((COSObjectReference) kids).dereference(); }
            catch (Exception e) { kids = null; }
        }
        if (!(kids instanceof COSArray)) return childFields;

        COSArray kidsArr = (COSArray) kids;
        for (int i = 0; i < kidsArr.size(); i++) {
            COSBase kid = kidsArr.get(i);
            if (kid instanceof COSObjectReference) {
                try { kid = ((COSObjectReference) kid).dereference(); }
                catch (Exception e) { continue; }
            }
            if (kid instanceof COSDictionary) {
                COSDictionary kidDict = (COSDictionary) kid;
                // Determine field type — inherit from parent if not present
                COSBase ft = kidDict.get("FT");
                if (ft == null) ft = dict.get("FT");
                // Build child full name
                String childPartial = null;
                COSBase t = kidDict.get("T");
                if (t instanceof COSString) childPartial = ((COSString) t).getString();
                String childFullName;
                if (childPartial != null) {
                    childFullName = (fullName != null && !fullName.isEmpty())
                            ? fullName + "." + childPartial : childPartial;
                } else {
                    childFullName = fullName != null ? fullName : "";
                }
                Field child = Field.fromDictionary(kidDict, ft, childFullName, page, null);
                childFields.add(child);
            }
        }
        return childFields;
    }
}
