package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.cos.*;

/**
 * A single option in a radio button group.
 * <p>
 * Each radio button option is a widget annotation dictionary that is a child
 * (/Kids entry) of a {@link RadioButtonField}. The option's value is determined
 * by the non-"Off" key in its /AP/N appearance sub-dictionary.
 * </p>
 */
public class RadioButtonOptionField {

    /** The underlying COS dictionary for this option. */
    private final COSDictionary dict;

    /** The page this option belongs to (may be null). */
    private final Page page;

    /** Per-option appearance style (defaults to Circle for radios). */
    private BoxStyle style = BoxStyle.Circle;

    /** Cached typed accessor over the kid widget's /MK entry. */
    private AppearanceCharacteristics cachedCharacteristics;

    /**
     * Constructs a radio button option from a COS dictionary.
     *
     * @param dict the COS dictionary
     * @param page the page (may be null)
     */
    public RadioButtonOptionField(COSDictionary dict, Page page) {
        this.dict = dict;
        this.page = page;
    }

    /**
     * Creates a fresh, unattached radio-button option widget. The new
     * dictionary is populated with the minimum entries every widget needs:
     * {@code /Type /Annot}, {@code /Subtype /Widget}, {@code /FT /Btn}.
     * <p>Use this when constructing radio button groups programmatically;
     * the option should be attached to a {@link RadioButtonField} via
     * {@code radio.add(option)} before saving.</p>
     */
    public RadioButtonOptionField() {
        this(buildDefaultWidgetDict(), null);
    }

    /**
     * Creates a fresh radio-button option widget attached to {@code page} with
     * the given rectangle. Convenience overload — equivalent to
     * {@link #RadioButtonOptionField()} followed by setting the page-link and
     * {@code /Rect}.
     *
     * @param page the page this widget sits on (may be null)
     * @param rect the widget rectangle in page coordinates (may be null)
     */
    public RadioButtonOptionField(Page page, Rectangle rect) {
        this(buildDefaultWidgetDict(), page);
        if (rect != null) {
            this.dict.set(COSName.of("Rect"), rect.toCOSArray());
        }
        if (page != null) {
            this.dict.set(COSName.of("P"), page.getCOSDictionary());
        }
    }

    private static COSDictionary buildDefaultWidgetDict() {
        COSDictionary d = new COSDictionary();
        d.set(COSName.TYPE, COSName.of("Annot"));
        d.set(COSName.SUBTYPE, COSName.of("Widget"));
        d.set(COSName.of("FT"), COSName.of("Btn"));
        return d;
    }

    /**
     * Returns the value this option represents.
     * <p>
     * Determined by finding the non-"Off" key in the /AP/N sub-dictionary.
     * </p>
     *
     * @return the option value, or null if undetermined
     */
    public String getOptionValue() {
        COSBase ap = dict.get("AP");
        if (ap instanceof COSDictionary) {
            COSBase n = ((COSDictionary) ap).get("N");
            if (n instanceof COSDictionary) {
                for (COSName key : ((COSDictionary) n).keySet()) {
                    if (!"Off".equals(key.getName())) return key.getName();
                }
            }
        }
        return null;
    }

    /**
     * Returns the rectangle of this option widget.
     *
     * @return the rectangle, or null if /Rect is not present
     */
    public Rectangle getRect() {
        COSBase r = dict.get("Rect");
        return (r instanceof COSArray) ? Rectangle.fromCOSArray((COSArray) r) : null;
    }

    /**
     * Returns the underlying COS dictionary.
     *
     * @return the COS dictionary
     */
    public COSDictionary getCOSDictionary() {
        return dict;
    }

    /**
     * Returns the per-option box style. Defaults to {@link BoxStyle#Circle}
     * for radio buttons (mirrors Aspose semantics).
     *
     * @return the box style
     */
    public BoxStyle getStyle() {
        return style;
    }

    /**
     * Sets the per-option box style.
     *
     * @param style the new style (must not be null)
     */
    public void setStyle(BoxStyle style) {
        if (style == null) throw new IllegalArgumentException("style must not be null");
        this.style = style;
    }

    /**
     * Returns typed access to this option's appearance characteristics
     * ({@code /MK} sub-dictionary on the kid widget). The wrapper creates
     * the entry lazily.
     *
     * @return the characteristics wrapper (never null)
     */
    public AppearanceCharacteristics getCharacteristics() {
        if (cachedCharacteristics == null) {
            cachedCharacteristics = new AppearanceCharacteristics(dict);
        }
        return cachedCharacteristics;
    }

    /**
     * Returns the export value (option name) of this option. Resolves first
     * through {@link #getOptionValue()} (the non-"Off" key in {@code /AP/N});
     * falls back to {@code /AS} when /AP isn't populated yet (typical for
     * freshly-constructed options where the user has only called
     * {@link #setOptionName(String)}).
     *
     * @return the option name, or null if neither /AP/N nor /AS contains one
     */
    public String getOptionName() {
        String fromAp = getOptionValue();
        if (fromAp != null) return fromAp;
        COSBase as = dict.get("AS");
        if (as instanceof COSName) return ((COSName) as).getName();
        return null;
    }

    /**
     * Sets the export value (option name) of this option. The given name
     * becomes the active appearance state ({@code /AS}) and is registered as
     * a key in {@code /AP/N} alongside {@code /Off} so that subsequent reads
     * via {@link #getOptionValue()} round-trip.
     *
     * <p>Passing {@code null} removes {@code /AS} and the named entry; the
     * "Off" placeholder remains so the widget stays toggleable.</p>
     *
     * @param name the option name (export value)
     */
    public void setOptionName(String name) {
        // Ensure /AP/N substructure exists
        COSDictionary ap = (COSDictionary) dict.get(COSName.of("AP"));
        if (ap == null) {
            ap = new COSDictionary();
            dict.set(COSName.of("AP"), ap);
        }
        COSDictionary n = (COSDictionary) ap.get(COSName.of("N"));
        if (n == null) {
            n = new COSDictionary();
            ap.set(COSName.of("N"), n);
        }
        // Drop any previous non-Off entries so a single name remains
        java.util.List<COSName> toRemove = new java.util.ArrayList<>();
        for (COSName key : n.keySet()) {
            if (!"Off".equals(key.getName())) toRemove.add(key);
        }
        for (COSName key : toRemove) n.remove(key);

        // Always keep an Off placeholder
        if (n.get(COSName.of("Off")) == null) {
            n.set(COSName.of("Off"), emptyAppearanceStream());
        }

        if (name == null) {
            dict.remove(COSName.of("AS"));
            return;
        }
        n.set(COSName.of(name), emptyAppearanceStream());
        dict.set(COSName.of("AS"), COSName.of(name));
    }

    /**
     * Sets the widget width by adjusting the right edge of {@code /Rect}.
     * If no rectangle is set, creates a 0,0,width,0 rectangle.
     *
     * @param width new width in page units
     */
    public void setWidth(double width) {
        Rectangle r = getRect();
        Rectangle next = r != null
                ? new Rectangle(r.getLLX(), r.getLLY(), r.getLLX() + width, r.getURY())
                : new Rectangle(0, 0, width, 0);
        dict.set(COSName.of("Rect"), next.toCOSArray());
    }

    /**
     * Sets the widget height by adjusting the top edge of {@code /Rect}.
     * If no rectangle is set, creates a 0,0,0,height rectangle.
     *
     * @param height new height in page units
     */
    public void setHeight(double height) {
        Rectangle r = getRect();
        Rectangle next = r != null
                ? new Rectangle(r.getLLX(), r.getLLY(), r.getURX(), r.getLLY() + height)
                : new Rectangle(0, 0, 0, height);
        dict.set(COSName.of("Rect"), next.toCOSArray());
    }

    private static COSStream emptyAppearanceStream() {
        COSStream s = new COSStream();
        s.set(COSName.TYPE, COSName.of("XObject"));
        s.set(COSName.SUBTYPE, COSName.of("Form"));
        s.setDecodedData(new byte[0]);
        return s;
    }

    /**
     * Returns the page this option's widget belongs to.
     *
     * @return the page, or null
     */
    public Page getPage() {
        return page;
    }

    /**
     * Sets the widget rectangle ({@code /Rect}) by directly writing into the
     * underlying dictionary. Convenience to mirror Aspose's C# property setter.
     *
     * @param rect the rectangle, or null to clear the entry
     */
    public void setRect(Rectangle rect) {
        if (rect == null) {
            dict.remove(COSName.of("Rect"));
        } else {
            dict.set(COSName.of("Rect"), rect.toCOSArray());
        }
    }

    /**
     * Returns the border ({@code /BS} dictionary) attached to this option's
     * widget, or {@code null} if absent.
     *
     * @return the border wrapper, or null
     */
    public org.aspose.pdf.annotations.Border getBorder() {
        COSBase v = dict.get("BS");
        if (v instanceof COSDictionary) {
            org.aspose.pdf.annotations.Border b =
                    new org.aspose.pdf.annotations.Border((org.aspose.pdf.annotations.Annotation) null);
            // Width is the only entry our Border class round-trips through COS today
            COSBase w = ((COSDictionary) v).get("W");
            if (w instanceof org.aspose.pdf.engine.cos.COSFloat) {
                b.setWidth(((org.aspose.pdf.engine.cos.COSFloat) w).doubleValue());
            } else if (w instanceof org.aspose.pdf.engine.cos.COSInteger) {
                b.setWidth(((org.aspose.pdf.engine.cos.COSInteger) w).intValue());
            }
            return b;
        }
        return null;
    }

    /**
     * Sets the border ({@code /BS} sub-dictionary) on this option's widget.
     * Passing {@code null} removes the entry.
     *
     * @param border the border wrapper, or null
     */
    public void setBorder(org.aspose.pdf.annotations.Border border) {
        if (border == null) {
            dict.remove(COSName.of("BS"));
            return;
        }
        COSDictionary bs = new COSDictionary();
        bs.set(COSName.TYPE, COSName.of("Border"));
        bs.set(COSName.of("W"), new org.aspose.pdf.engine.cos.COSFloat(border.getWidth()));
        // /S = solid/dashed/beveled/inset/underline — we serialise the enum's first letter
        bs.set(COSName.of("S"),
                COSName.of(border.getStyle() != null ? border.getStyle().name().substring(0, 1) : "S"));
        dict.set(COSName.of("BS"), bs);
    }

    /**
     * Returns the visible caption shown alongside this option ({@code /MK/CA}).
     */
    public String getCaption() {
        return getCharacteristics().getCaption();
    }

    /**
     * Sets the visible caption shown alongside this option ({@code /MK/CA}).
     *
     * @param caption the caption text; null clears the entry
     */
    public void setCaption(String caption) {
        getCharacteristics().setCaption(caption);
    }

    /**
     * Returns the border colour ({@code /MK/BC}) used for this option.
     */
    public org.aspose.pdf.Color getColor() {
        return getCharacteristics().getBorder();
    }

    /**
     * Sets the border colour ({@code /MK/BC}) used for this option's check
     * glyph and outline.
     *
     * @param color the colour; null clears the entry
     */
    public void setColor(org.aspose.pdf.Color color) {
        getCharacteristics().setBorder(color);
    }

    /**
     * Returns the {@code /DA} default-appearance string for this option, or
     * null if not set.
     *
     * @return the DA string, or null
     */
    public String getDefaultAppearance() {
        COSBase v = dict.get("DA");
        if (v instanceof org.aspose.pdf.engine.cos.COSString) {
            return ((org.aspose.pdf.engine.cos.COSString) v).getString();
        }
        return null;
    }

    /**
     * Sets the {@code /DA} default-appearance string raw. Pass {@code null}
     * to clear.
     *
     * @param da the DA string, or null
     */
    public void setDefaultAppearance(String da) {
        if (da == null) {
            dict.remove(COSName.of("DA"));
        } else {
            dict.set(COSName.of("DA"), new org.aspose.pdf.engine.cos.COSString(da));
        }
    }

    /**
     * Convenience overload — accepts a typed {@link org.aspose.pdf.annotations.DefaultAppearance}
     * and serialises it via its {@code toString()}.
     *
     * @param da the typed default appearance, or null
     */
    public void setDefaultAppearance(org.aspose.pdf.annotations.DefaultAppearance da) {
        if (da == null) {
            dict.remove(COSName.of("DA"));
        } else {
            setDefaultAppearance(da.toString());
        }
    }

    /**
     * Returns the typed appearance view over this option's {@code /AP}
     * sub-dictionary. Mirrors {@link Field#getAppearance()} for radio options
     * (which do not extend {@link Field}).
     *
     * @return the appearance dictionary (never null; /AP created lazily)
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
     * Rebuilds the {@code /AP/N} appearance streams for this option from its
     * current {@code /Rect}, {@code style} and {@link #getOptionName()}.
     *
     * <p>Closes F-10 for radio-option widgets. Idempotent; no-op when the
     * widget has no rectangle yet.</p>
     */
    public void regenerateAppearance() {
        Rectangle r = getRect();
        if (r == null) return;
        String onState = getOptionName();
        if (onState == null) onState = "On";
        COSStream onStream = FieldAppearanceBuilder.buildRadioAppearance(r, true, style);
        COSStream offStream = FieldAppearanceBuilder.buildRadioAppearance(r, false, style);
        FieldAppearanceBuilder.installAppearance(dict, onStream, onState, offStream);
    }
}

