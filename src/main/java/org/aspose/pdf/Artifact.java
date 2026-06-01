package org.aspose.pdf;

import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSString;

import org.aspose.pdf.text.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Represents a PDF artifact — content that is not part of the authored page content
 * but is produced as a side effect of pagination or layout (ISO 32000-1:2008, §14.8.2.2).
 * <p>
 * Artifacts include headers, footers, page numbers, watermarks, background images,
 * cut marks, and other decorative elements. They are marked in content streams using
 * {@code BMC}/{@code BDC}...{@code EMC} operator sequences with the {@code /Artifact} tag.
 * </p>
 * <p>
 * A {@code BDC} operator may include a properties dictionary specifying the artifact
 * {@code /Type} (Pagination, Layout, Page, Background), {@code /Subtype}
 * (Header, Footer, Watermark), and {@code /BBox}.
 * </p>
 */
public class Artifact {

    private static final Logger LOG = Logger.getLogger(Artifact.class.getName());

    /**
     * Defines the type of an artifact (ISO 32000-1:2008, §14.8.2.2.1, Table 330).
     */
    public enum ArtifactType {
        /** Artifacts arising from pagination — headers, footers, page numbers. */
        Pagination,
        /** Artifacts arising from layout — rules, borders, coloring. */
        Layout,
        /** Artifacts associated with the physical page — cut marks, color bars. */
        Page,
        /** Background images or coloring behind page content. */
        Background
    }

    /**
     * Defines the subtype of a pagination artifact (ISO 32000-1:2008, §14.8.2.2.1).
     */
    public enum ArtifactSubtype {
        /** A page header artifact. */
        Header,
        /** A page footer artifact. */
        Footer,
        /** A watermark artifact. */
        Watermark,
        /** A background artifact. */
        Background,
        /** No specific subtype. */
        None
    }

    private ArtifactType type;
    private ArtifactSubtype subtype;
    private Rectangle rectangle;
    private String text;
    private List<Operator> contents;
    private boolean isBackground;
    private Image image;
    private double opacity = 1.0;
    private double rotation;
    private double topMargin;
    private double bottomMargin;
    private double leftMargin;
    private double rightMargin;
    private Position position;

    /**
     * Creates an artifact with the specified type and subtype.
     *
     * @param type    the artifact type
     * @param subtype the artifact subtype
     */
    public Artifact(ArtifactType type, ArtifactSubtype subtype) {
        this.type = type;
        this.subtype = subtype;
        this.contents = new ArrayList<>();
        LOG.fine(() -> "Artifact created: type=" + type + ", subtype=" + subtype);
    }

    /**
     * Creates an artifact by parsing a properties dictionary from a BDC operator.
     * <p>
     * The dictionary may contain:
     * <ul>
     *   <li>{@code /Type} — the artifact type name (Pagination, Layout, Page, Background)</li>
     *   <li>{@code /Subtype} — the subtype name (Header, Footer, Watermark)</li>
     *   <li>{@code /BBox} — the bounding box as a four-element array</li>
     * </ul>
     * See ISO 32000-1:2008, §14.8.2.2, Table 330.
     * </p>
     *
     * @param properties the properties dictionary from the BDC operator; may be {@code null}
     */
    public Artifact(COSDictionary properties) {
        this.contents = new ArrayList<>();
        if (properties != null) {
            parseProperties(properties);
        } else {
            this.type = null;
            this.subtype = ArtifactSubtype.None;
        }
    }

    /**
     * Creates an artifact with no type or subtype.
     */
    public Artifact() {
        this.contents = new ArrayList<>();
        this.subtype = ArtifactSubtype.None;
    }

    /**
     * Returns the artifact type.
     *
     * @return the type, or {@code null} if not specified
     */
    public ArtifactType getArtifactType() {
        return type;
    }

    /**
     * Sets the artifact type.
     *
     * @param type the artifact type
     */
    public void setArtifactType(ArtifactType type) {
        this.type = type;
    }

    /**
     * Returns the artifact type (alias for {@link #getArtifactType()}).
     *
     * @return the type, or {@code null} if not specified
     */
    public ArtifactType getType() {
        return type;
    }

    /**
     * Sets the artifact type (alias for {@link #setArtifactType(ArtifactType)}).
     *
     * @param type the artifact type
     */
    public void setType(ArtifactType type) {
        this.type = type;
    }

    /**
     * Returns the artifact subtype.
     *
     * @return the subtype
     */
    public ArtifactSubtype getSubtype() {
        return subtype;
    }

    /**
     * Sets the artifact subtype.
     *
     * @param subtype the artifact subtype
     */
    public void setSubtype(ArtifactSubtype subtype) {
        this.subtype = subtype;
    }

    /**
     * Returns the bounding box rectangle of this artifact.
     *
     * @return the bounding box, or {@code null} if not specified
     */
    public Rectangle getRectangle() {
        return rectangle;
    }

    /**
     * Sets the bounding box rectangle.
     *
     * @param rectangle the bounding box
     */
    public void setRectangle(Rectangle rectangle) {
        this.rectangle = rectangle;
    }

    /**
     * Returns the text content of this artifact.
     * <p>
     * If the text has not been explicitly set, it is extracted from the contained
     * content stream operators by scanning for text-showing operators (Tj, TJ).
     * </p>
     *
     * @return the text content, or an empty string if no text is found
     */
    public String getText() {
        if (text != null) {
            return text;
        }
        return extractTextFromOperators();
    }

    /**
     * Sets the text content of this artifact.
     *
     * @param text the text
     */
    public void setText(String text) {
        this.text = text;
    }

    /**
     * Returns the content stream operators that make up this artifact.
     *
     * @return an unmodifiable list of operators
     */
    public List<Operator> getContents() {
        return Collections.unmodifiableList(contents);
    }

    /**
     * Synthesises a self-contained {@code /Artifact BMC ... EMC} (or
     * {@code BDC ... EMC}) sequence as raw content-stream bytes, registering
     * any supporting resources (fonts, ExtGState) on the page's
     * {@code /Resources}.
     *
     * <p>Returns {@code null} by default; concrete subclasses (e.g.
     * {@link BackgroundArtifact}, {@link WatermarkArtifact}) override this
     * to render their high-level properties. When non-null is returned and
     * the artifact's {@link #getContents()} is empty, {@link Page} writes
     * the bytes directly into the content stream instead of emitting an
     * empty marked-content pair.</p>
     *
     * @param page the page on which the artifact will appear
     * @return raw content-stream bytes (including BMC/EMC wrapper), or
     *         {@code null} if no synthesis is provided
     */
    byte[] synthesizeContentBytes(Page page) {
        return null;
    }

    /**
     * Sets the content stream operators for this artifact.
     *
     * @param operators the operators
     */
    public void setContents(List<Operator> operators) {
        this.contents = operators != null ? new ArrayList<>(operators) : new ArrayList<>();
    }

    /**
     * Returns whether this artifact is a background element.
     *
     * @return {@code true} if the artifact is a background element
     */
    public boolean isBackground() {
        return isBackground;
    }

    /**
     * Sets whether this artifact is a background element.
     *
     * @param background {@code true} to mark as background
     */
    public void setBackground(boolean background) {
        this.isBackground = background;
    }

    /**
     * Returns the image associated with this artifact, if any.
     *
     * @return the image, or {@code null}
     */
    public Image getImage() {
        return image;
    }

    /**
     * Sets the image associated with this artifact.
     *
     * @param image the image
     */
    public void setImage(Image image) {
        this.image = image;
    }

    /**
     * Returns the opacity of this artifact (0.0 = fully transparent, 1.0 = fully opaque).
     *
     * @return the opacity value
     */
    public double getOpacity() {
        return opacity;
    }

    /**
     * Sets the opacity of this artifact.
     *
     * @param opacity the opacity value (0.0 to 1.0)
     */
    public void setOpacity(double opacity) {
        this.opacity = opacity;
    }

    /**
     * Returns the rotation angle in degrees.
     *
     * @return the rotation angle
     */
    public double getRotation() {
        return rotation;
    }

    /**
     * Sets the rotation angle in degrees.
     *
     * @param rotation the rotation angle
     */
    public void setRotation(double rotation) {
        this.rotation = rotation;
    }

    /**
     * Returns the top margin.
     *
     * @return the top margin in points
     */
    public double getTopMargin() {
        return topMargin;
    }

    /**
     * Sets the top margin.
     *
     * @param topMargin the top margin in points
     */
    public void setTopMargin(double topMargin) {
        this.topMargin = topMargin;
    }

    /**
     * Returns the bottom margin.
     *
     * @return the bottom margin in points
     */
    public double getBottomMargin() {
        return bottomMargin;
    }

    /**
     * Sets the bottom margin.
     *
     * @param bottomMargin the bottom margin in points
     */
    public void setBottomMargin(double bottomMargin) {
        this.bottomMargin = bottomMargin;
    }

    /**
     * Returns the left margin.
     *
     * @return the left margin in points
     */
    public double getLeftMargin() {
        return leftMargin;
    }

    /**
     * Sets the left margin.
     *
     * @param leftMargin the left margin in points
     */
    public void setLeftMargin(double leftMargin) {
        this.leftMargin = leftMargin;
    }

    /**
     * Returns the right margin.
     *
     * @return the right margin in points
     */
    public double getRightMargin() {
        return rightMargin;
    }

    /**
     * Sets the right margin.
     *
     * @param rightMargin the right margin in points
     */
    public void setRightMargin(double rightMargin) {
        this.rightMargin = rightMargin;
    }

    /**
     * Returns the position of this artifact on the page.
     *
     * @return the position, or {@code null} if not set
     */
    public Position getPosition() {
        return position;
    }

    /**
     * Sets the position of this artifact on the page.
     *
     * @param position the position
     */
    public void setPosition(Position position) {
        this.position = position;
    }

    /**
     * Returns a string representation of this artifact for debugging.
     *
     * @return a human-readable string
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Artifact{");
        if (type != null) sb.append("type=").append(type);
        if (subtype != null && subtype != ArtifactSubtype.None) {
            sb.append(", subtype=").append(subtype);
        }
        if (rectangle != null) sb.append(", bbox=").append(rectangle);
        String t = getText();
        if (t != null && !t.isEmpty()) sb.append(", text='").append(t).append("'");
        sb.append(", operators=").append(contents.size());
        sb.append('}');
        return sb.toString();
    }

    // ---- Internal helpers ----

    /**
     * Parses artifact properties from a BDC properties dictionary.
     */
    private void parseProperties(COSDictionary props) {
        // Parse /Type
        COSBase typeVal = props.get("Type");
        if (typeVal instanceof COSName) {
            String typeName = ((COSName) typeVal).getName();
            this.type = parseArtifactType(typeName);
        }

        // Parse /Subtype
        COSBase subtypeVal = props.get("Subtype");
        if (subtypeVal instanceof COSName) {
            String subtypeName = ((COSName) subtypeVal).getName();
            this.subtype = parseArtifactSubtype(subtypeName);
        } else {
            this.subtype = ArtifactSubtype.None;
        }

        // Parse /BBox
        COSBase bboxVal = props.get("BBox");
        if (bboxVal instanceof COSArray) {
            COSArray bboxArr = (COSArray) bboxVal;
            if (bboxArr.size() == 4) {
                this.rectangle = Rectangle.fromCOSArray(bboxArr);
            }
        }

        // Check background type
        this.isBackground = (this.type == ArtifactType.Background);

        LOG.fine(() -> "Parsed artifact properties: type=" + type + ", subtype=" + subtype);
    }

    /**
     * Parses an artifact type from a PDF name string.
     *
     * @param name the type name from the properties dictionary
     * @return the matching ArtifactType, or {@code null} if unrecognized
     */
    static ArtifactType parseArtifactType(String name) {
        if (name == null) return null;
        switch (name) {
            case "Pagination": return ArtifactType.Pagination;
            case "Layout":     return ArtifactType.Layout;
            case "Page":       return ArtifactType.Page;
            case "Background": return ArtifactType.Background;
            default:           return null;
        }
    }

    /**
     * Parses an artifact subtype from a PDF name string.
     *
     * @param name the subtype name from the properties dictionary
     * @return the matching ArtifactSubtype, or {@link ArtifactSubtype#None} if unrecognized
     */
    static ArtifactSubtype parseArtifactSubtype(String name) {
        if (name == null) return ArtifactSubtype.None;
        switch (name) {
            case "Header":     return ArtifactSubtype.Header;
            case "Footer":     return ArtifactSubtype.Footer;
            case "Watermark":  return ArtifactSubtype.Watermark;
            case "Background": return ArtifactSubtype.Background;
            default:           return ArtifactSubtype.None;
        }
    }

    /**
     * Extracts text content from the contained operators by scanning for
     * text-showing operators (Tj and TJ).
     *
     * @return the extracted text, or an empty string
     */
    private String extractTextFromOperators() {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Operator op : contents) {
            String name = op.getName();
            if ("Tj".equals(name)) {
                // Tj has one operand: a string
                if (!op.getOperands().isEmpty()) {
                    COSBase operand = op.getOperands().get(0);
                    if (operand instanceof COSString) {
                        sb.append(((COSString) operand).getString());
                    }
                }
            } else if ("TJ".equals(name)) {
                // TJ has one operand: an array of strings and numbers
                if (!op.getOperands().isEmpty()) {
                    COSBase operand = op.getOperands().get(0);
                    if (operand instanceof COSArray) {
                        COSArray arr = (COSArray) operand;
                        for (int i = 0; i < arr.size(); i++) {
                            COSBase elem = arr.get(i);
                            if (elem instanceof COSString) {
                                sb.append(((COSString) elem).getString());
                            }
                        }
                    }
                }
            }
        }
        return sb.toString();
    }
}
