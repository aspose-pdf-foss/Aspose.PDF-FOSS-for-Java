package org.aspose.pdf;

import org.aspose.pdf.annotations.*;
import org.aspose.pdf.engine.pdfobjects.*;
import org.aspose.pdf.forms.Field;
import org.aspose.pdf.forms.Form;

import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.logging.Logger;

/**
 * Imports annotations and form field data from XFDF (XML Forms Data Format) into a PDF document,
 * per XFDF Specification Version 3.0 (August 2009).
 * <p>
 * Uses {@code javax.xml.parsers.DocumentBuilder} for robust XML parsing.
 * Supports all annotation types, attributes, child elements, and form field values.
 * </p>
 *
 * @see XfdfExporter
 */
public final class XfdfImporter {

    private static final Logger LOG = Logger.getLogger(XfdfImporter.class.getName());

    private static final Map<String, String> ELEMENT_TO_SUBTYPE = new HashMap<>();

    static {
        ELEMENT_TO_SUBTYPE.put("text", "Text");
        ELEMENT_TO_SUBTYPE.put("highlight", "Highlight");
        ELEMENT_TO_SUBTYPE.put("underline", "Underline");
        ELEMENT_TO_SUBTYPE.put("strikeout", "StrikeOut");
        ELEMENT_TO_SUBTYPE.put("squiggly", "Squiggly");
        ELEMENT_TO_SUBTYPE.put("freetext", "FreeText");
        ELEMENT_TO_SUBTYPE.put("line", "Line");
        ELEMENT_TO_SUBTYPE.put("circle", "Circle");
        ELEMENT_TO_SUBTYPE.put("square", "Square");
        ELEMENT_TO_SUBTYPE.put("polygon", "Polygon");
        ELEMENT_TO_SUBTYPE.put("polyline", "PolyLine");
        // NOTE: <link> is intentionally NOT imported. Aspose's XFDF import skips
        // link annotations (they are not markup annotations); importing them adds
        // an extra /Link the reference outputs don't have (PDFNET_48661).
        ELEMENT_TO_SUBTYPE.put("stamp", "Stamp");
        ELEMENT_TO_SUBTYPE.put("caret", "Caret");
        ELEMENT_TO_SUBTYPE.put("ink", "Ink");
        ELEMENT_TO_SUBTYPE.put("popup", "Popup");
        ELEMENT_TO_SUBTYPE.put("fileattachment", "FileAttachment");
        ELEMENT_TO_SUBTYPE.put("redact", "Redact");
    }

    /** Flag name to bit position mapping per XFDF spec. */
    private static final Map<String, Integer> FLAG_BITS = new HashMap<>();

    static {
        FLAG_BITS.put("invisible", 0x01);
        FLAG_BITS.put("hidden", 0x02);
        FLAG_BITS.put("print", 0x04);
        FLAG_BITS.put("nozoom", 0x08);
        FLAG_BITS.put("norotate", 0x10);
        FLAG_BITS.put("noview", 0x20);
        FLAG_BITS.put("readonly", 0x40);
        FLAG_BITS.put("locked", 0x80);
        FLAG_BITS.put("togglenoview", 0x100);
        FLAG_BITS.put("lockedcontents", 0x200);
    }

    private XfdfImporter() {
        // utility class
    }

    /**
     * Imports annotations and form fields from an XFDF file into the document.
     *
     * @param document the target document
     * @param filePath the path to the XFDF file
     * @throws IOException              if reading fails
     * @throws IllegalArgumentException if document or filePath is null
     */
    public static void importXfdf(Document document, String filePath) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("File path must not be null");
        }
        try (FileInputStream fis = new FileInputStream(filePath)) {
            importXfdf(document, fis);
        }
    }

    /**
     * Imports annotations and form fields from an XFDF input stream into the document.
     *
     * @param document the target document
     * @param input    the input stream containing XFDF XML
     * @throws IOException              if reading fails
     * @throws IllegalArgumentException if document or input is null
     */
    public static void importXfdf(Document document, InputStream input) throws IOException {
        importXfdf(document, input, null);
    }

    /**
     * Imports annotations from an XFDF input stream, optionally filtering by type.
     *
     * @param document the target document
     * @param input    the input stream containing XFDF XML
     * @param allowedTypes annotation subtypes to import (null = all)
     * @throws IOException if reading or parsing fails
     */
    public static void importXfdf(Document document, InputStream input, Set<String> allowedTypes) throws IOException {
        if (document == null) {
            throw new IllegalArgumentException("Document must not be null");
        }
        if (input == null) {
            throw new IllegalArgumentException("Input stream must not be null");
        }

        try {
            // XFDF is untrusted input: parse with the shared XXE-hardened builder
            // (DOCTYPE disallowed, external entities/XInclude disabled).
            DocumentBuilder builder = org.aspose.pdf.engine.xml.SecureXml.newBuilder(false);
            org.w3c.dom.Document xmlDoc = builder.parse(input);

            // Import form fields
            NodeList fieldsNodes = xmlDoc.getElementsByTagName("fields");
            if (fieldsNodes.getLength() > 0) {
                importFields(document, fieldsNodes.item(0));
            }

            // Import annotations
            NodeList annotsNodes = xmlDoc.getElementsByTagName("annots");
            if (annotsNodes.getLength() > 0) {
                importAnnotations(document, annotsNodes.item(0), allowedTypes);
            }

            LOG.fine("XFDF import completed");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse XFDF: " + e.getMessage(), e);
        }
    }

    /**
     * Imports form field values from the {@code <fields>} section.
     */
    private static void importFields(Document doc, Node fieldsNode) {
        try {
            Form form = doc.getForm();
            if (form == null) return;
            importFieldChildren(doc, fieldsNode, "");
        } catch (Exception e) {
            LOG.fine(() -> "Could not import form fields: " + e.getMessage());
        }
    }

    /**
     * Recursively imports field values from nested {@code <field>} elements.
     */
    private static void importFieldChildren(Document doc, Node parent, String parentName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE || !"field".equals(child.getNodeName())) {
                continue;
            }
            Element fieldElem = (Element) child;
            String name = fieldElem.getAttribute("name");
            if (name == null || name.isEmpty()) continue;

            String fullName = parentName.isEmpty() ? name : parentName + "." + name;

            // Check for <value> child
            String value = getChildText(fieldElem, "value");
            if (value != null) {
                try {
                    Field field = doc.getForm().get(fullName);
                    if (field != null) {
                        field.setValue(value);
                    }
                } catch (Exception e) {
                    LOG.fine(() -> "Could not set field '" + fullName + "': " + e.getMessage());
                }
            }

            // Recurse for nested fields
            importFieldChildren(doc, child, fullName);
        }
    }

    /**
     * Imports annotations from the {@code <annots>} section.
     */
    private static void importAnnotations(Document doc, Node annotsNode, Set<String> allowedTypes) throws IOException {
        PageCollection pages = doc.getPages();
        NodeList children = annotsNode.getChildNodes();

        // An XFDF /name uniquely identifies an annotation; an export may contain
        // several entries with the same name representing successive revisions of
        // that one annotation. Aspose imports a single annotation per unique name,
        // keeping the LAST (final) revision. Deduplicate accordingly, preserving
        // first-occurrence order; unnamed entries are always imported.
        java.util.List<Element> ordered = new java.util.ArrayList<>();
        java.util.Map<String, Integer> namedSlot = new java.util.HashMap<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String elementName = child.getNodeName().toLowerCase();
            String subtype = ELEMENT_TO_SUBTYPE.get(elementName);
            if (subtype == null) continue;
            if (allowedTypes != null && !allowedTypes.contains(subtype)) continue;

            Element elem = (Element) child;
            String name = elem.getAttribute("name");
            if (name == null || name.isEmpty()) {
                ordered.add(elem);
            } else if (namedSlot.containsKey(name)) {
                ordered.set(namedSlot.get(name), elem); // replace earlier revision in place
            } else {
                namedSlot.put(name, ordered.size());
                ordered.add(elem);
            }
        }

        // Two passes are needed to restore reply chains: an /IRT (in-reply-to)
        // reference names a sibling annotation by its /NM, which may be imported
        // after the replying annotation. Record each imported annotation by name
        // and defer the link resolution until every annotation exists.
        java.util.Map<String, Annotation> byName = new java.util.HashMap<>();
        java.util.List<Object[]> pendingIrt = new java.util.ArrayList<>();
        for (Element elem : ordered) {
            String elementName = elem.getNodeName().toLowerCase();
            String subtype = ELEMENT_TO_SUBTYPE.get(elementName);
            try {
                Annotation annot = importAnnotation(elem, subtype, pages);
                if (annot == null) continue;
                String name = elem.getAttribute("name");
                if (name != null && !name.isEmpty()) {
                    byName.put(name, annot);
                }
                String irt = elem.getAttribute("inreplyto");
                if (irt != null && !irt.isEmpty()) {
                    pendingIrt.add(new Object[]{annot, irt});
                }
            } catch (Exception e) {
                LOG.fine(() -> "Failed to import annotation '" + elementName + "': " + e.getMessage());
            }
        }

        for (Object[] link : pendingIrt) {
            Annotation replier = (Annotation) link[0];
            Annotation target = byName.get((String) link[1]);
            if (target != null && replier instanceof MarkupAnnotation) {
                ((MarkupAnnotation) replier).setInReplyTo(target);
            }
        }
    }

    /**
     * Imports a single annotation element with all its attributes and child elements.
     */
    private static Annotation importAnnotation(Element elem, String subtype, PageCollection pages) {
        // Page index (0-based in XFDF)
        int pageIndex = getIntAttr(elem, "page", 0);
        int pageNum = pageIndex + 1;
        if (pageNum < 1 || pageNum > pages.getCount()) {
            LOG.fine(() -> "Page " + pageNum + " out of range, skipping annotation");
            return null;
        }

        Page page = pages.get(pageNum);

        // Parse rect
        Rectangle rect = parseRect(elem.getAttribute("rect"));
        if (rect == null) {
            rect = new Rectangle(0, 0, 100, 100);
        }

        // Create the typed annotation
        Annotation annot = createAnnotation(subtype, page, rect);
        if (annot == null) return null;

        // Common attributes
        String colorStr = elem.getAttribute("color");
        if (!colorStr.isEmpty()) {
            Color color = parseHexColor(colorStr);
            if (color != null) annot.setColor(color);
        }

        String dateStr = elem.getAttribute("date");
        if (!dateStr.isEmpty()) {
            annot.setModified(dateStr);
        }

        String nameStr = elem.getAttribute("name");
        if (!nameStr.isEmpty()) {
            annot.setName(nameStr);
        }

        String flagsStr = elem.getAttribute("flags");
        if (!flagsStr.isEmpty()) {
            annot.setFlags(parseFlagsString(flagsStr));
        }

        // Contents: prefer <contents> child element, fall back to attribute
        String contents = getChildText(elem, "contents");
        if (contents != null) {
            annot.setContents(contents);
        }

        // Markup annotation attributes
        if (annot instanceof MarkupAnnotation) {
            MarkupAnnotation markup = (MarkupAnnotation) annot;

            String title = elem.getAttribute("title");
            if (!title.isEmpty()) markup.setTitle(title);

            String creationDate = elem.getAttribute("creationdate");
            if (!creationDate.isEmpty()) markup.setCreationDate(creationDate);

            String opacityStr = elem.getAttribute("opacity");
            if (!opacityStr.isEmpty()) {
                try {
                    markup.setOpacity(Double.parseDouble(opacityStr));
                } catch (NumberFormatException ignored) { }
            }

            String subject = elem.getAttribute("subject");
            if (!subject.isEmpty()) markup.setSubject(subject);

            String replyType = elem.getAttribute("replyType");
            if (!replyType.isEmpty()) {
                markup.setReplyType("reply".equalsIgnoreCase(replyType) ? "R" : "Group");
            }

            // Rich text child element
            String richText = getChildText(elem, "contents-richtext");
            if (richText != null) {
                markup.setRichText(richText);
            }
        }

        // Redaction specifics: overlay text
        if (annot instanceof RedactionAnnotation) {
            String overlay = elem.getAttribute("overlaytext");
            if (!overlay.isEmpty()) {
                ((RedactionAnnotation) annot).setOverlayText(overlay);
            }
        }

        // Text annotation specifics
        if (annot instanceof TextAnnotation) {
            TextAnnotation text = (TextAnnotation) annot;

            String icon = elem.getAttribute("icon");
            if (!icon.isEmpty()) text.setIcon(icon);

            String state = elem.getAttribute("state");
            if (!state.isEmpty()) text.setState(state);

            String stateModel = elem.getAttribute("statemodel");
            if (!stateModel.isEmpty()) text.setStateModel(stateModel);
        }

        // Stamp annotation specifics: icon (/Name entry). Mirrors the exporter,
        // which stores the stamp name in the "icon" attribute (BUG-XFDF-STAMP-ICON).
        if (annot instanceof StampAnnotation) {
            String icon = elem.getAttribute("icon");
            if (!icon.isEmpty()) ((StampAnnotation) annot).setIcon(icon);
        }

        // QuadPoints for text markup annotations
        String coords = elem.getAttribute("coords");
        if (!coords.isEmpty()) {
            double[] qp = parseNumberList(coords, ',');
            if (qp != null) {
                if (annot instanceof HighlightAnnotation) ((HighlightAnnotation) annot).setQuadPoints(qp);
                else if (annot instanceof UnderlineAnnotation) ((UnderlineAnnotation) annot).setQuadPoints(qp);
                else if (annot instanceof StrikeOutAnnotation) ((StrikeOutAnnotation) annot).setQuadPoints(qp);
                else if (annot instanceof SquigglyAnnotation) ((SquigglyAnnotation) annot).setQuadPoints(qp);
            }
        }

        // Line annotation: start, end
        if (annot instanceof LineAnnotation) {
            String start = elem.getAttribute("start");
            String end = elem.getAttribute("end");
            if (!start.isEmpty() && !end.isEmpty()) {
                double[] s = parseNumberList(start, ',');
                double[] e = parseNumberList(end, ',');
                if (s != null && s.length >= 2 && e != null && e.length >= 2) {
                    PdfArray l = new PdfArray();
                    l.add(new PdfFloat(s[0]));
                    l.add(new PdfFloat(s[1]));
                    l.add(new PdfFloat(e[0]));
                    l.add(new PdfFloat(e[1]));
                    annot.getPdfDictionary().set(PdfName.of("L"), l);
                }
            }
        }

        // Ink annotation: <inklist><gesture>
        if (annot instanceof InkAnnotation) {
            NodeList inklistNodes = elem.getElementsByTagName("inklist");
            if (inklistNodes.getLength() > 0) {
                Element inklistElem = (Element) inklistNodes.item(0);
                NodeList gestures = inklistElem.getElementsByTagName("gesture");
                PdfArray outerArray = new PdfArray();
                for (int g = 0; g < gestures.getLength(); g++) {
                    String gestureText = gestures.item(g).getTextContent();
                    if (gestureText == null || gestureText.isEmpty()) continue;
                    String[] points = gestureText.split(";");
                    PdfArray innerArray = new PdfArray();
                    for (String point : points) {
                        String[] xy = point.split(",");
                        for (String coord : xy) {
                            try {
                                innerArray.add(new PdfFloat(Double.parseDouble(coord.trim())));
                            } catch (NumberFormatException ignored) { }
                        }
                    }
                    outerArray.add(innerArray);
                }
                annot.getPdfDictionary().set(PdfName.of("InkList"), outerArray);
            }
        }

        // Polygon/Polyline: <vertices>
        if (annot instanceof PolygonAnnotation || annot instanceof PolylineAnnotation) {
            String verticesText = getChildText(elem, "vertices");
            if (verticesText != null) {
                double[] verts = parseNumberList(verticesText, ',');
                if (verts != null) {
                    PdfArray arr = new PdfArray();
                    for (double v : verts) arr.add(new PdfFloat(v));
                    annot.getPdfDictionary().set(PdfName.of("Vertices"), arr);
                }
            }
        }

        // Add to page
        page.getAnnotations().add(annot);

        // Pair the markup annotation with a Popup, the way Adobe/Aspose do on XFDF
        // import (ISO 32000-1 §12.5.6.14): every markup annotation except FreeText
        // (which displays its text directly) gets a /Popup added right after it in
        // /Annots. Placement follows the Acrobat convention — a 204x114 box in the
        // margin to the right of the page, top-aligned with the parent.
        if (annot instanceof MarkupAnnotation && !(annot instanceof FreeTextAnnotation)) {
            try {
                // Prefer the exported <popup> child (rect/flags/open) when present;
                // otherwise fall back to the Acrobat-convention placeholder box.
                Element popupElem = getDirectChild(elem, "popup");
                Rectangle popupRect = null;
                if (popupElem != null) {
                    popupRect = parseRect(popupElem.getAttribute("rect"));
                }
                if (popupRect == null) {
                    double pageW = page.getRect().getURX();
                    popupRect = new Rectangle(
                            pageW, rect.getURY() - 114, pageW + 204, rect.getURY());
                }
                PopupAnnotation popup = new PopupAnnotation(page, popupRect);
                popup.getPdfDictionary().set(PdfName.of("Parent"), annot.getPdfDictionary());
                if (popupElem != null) {
                    String pflags = popupElem.getAttribute("flags");
                    if (!pflags.isEmpty()) popup.setFlags(parseFlagsString(pflags));
                    String popen = popupElem.getAttribute("open");
                    if (!popen.isEmpty()) popup.setOpen("yes".equalsIgnoreCase(popen));
                }
                ((MarkupAnnotation) annot).setPopup(popup);
                page.getAnnotations().add(popup);
            } catch (RuntimeException e) {
                LOG.fine(() -> "Could not create popup for imported annotation: " + e.getMessage());
            }
        }

        return annot;
    }

    /**
     * Creates a typed annotation based on the subtype.
     */
    private static Annotation createAnnotation(String subtype, Page page, Rectangle rect) {
        switch (subtype) {
            case "Text": return new TextAnnotation(page, rect);
            case "Highlight": return new HighlightAnnotation(page, rect);
            case "Underline": return new UnderlineAnnotation(page, rect);
            case "StrikeOut": return new StrikeOutAnnotation(page, rect);
            case "Squiggly": return new SquigglyAnnotation(page, rect);
            case "FreeText": return new FreeTextAnnotation(page, rect);
            case "Line": return new LineAnnotation(page, rect);
            case "Circle": return new CircleAnnotation(page, rect);
            case "Square": return new SquareAnnotation(page, rect);
            case "Polygon": return new PolygonAnnotation(page, rect);
            case "PolyLine": return new PolylineAnnotation(page, rect);
            case "Link": return new LinkAnnotation(page, rect);
            case "Stamp": return new StampAnnotation(page, rect);
            case "Caret": return new CaretAnnotation(page, rect);
            case "Ink": return new InkAnnotation(page, rect);
            case "Popup": return new PopupAnnotation(page, rect);
            case "FileAttachment": return new FileAttachmentAnnotation(page, rect);
            case "Redact": return new RedactionAnnotation(page, rect);
            default:
                LOG.fine(() -> "Unknown annotation subtype: " + subtype);
                return null;
        }
    }

    /**
     * Parses XFDF flags string ("print,nozoom,norotate") to integer bitmask.
     */
    public static int parseFlagsString(String flagsStr) {
        // Try parsing as integer first (backwards compatibility)
        try {
            return Integer.parseInt(flagsStr.trim());
        } catch (NumberFormatException ignored) { }

        // Parse comma-separated flag names
        int flags = 0;
        String[] parts = flagsStr.split(",");
        for (String part : parts) {
            String name = part.trim().toLowerCase();
            Integer bit = FLAG_BITS.get(name);
            if (bit != null) {
                flags |= bit;
            }
        }
        return flags;
    }

    /**
     * Parses a rect string "llx,lly,urx,ury" into a Rectangle.
     */
    private static Rectangle parseRect(String rectStr) {
        if (rectStr == null || rectStr.isEmpty()) return null;
        String[] parts = rectStr.split(",");
        if (parts.length != 4) return null;
        try {
            return new Rectangle(
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim())
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a hex color string like "#RRGGBB" into a Color.
     */
    public static Color parseHexColor(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() == 6) {
            try {
                int r = Integer.parseInt(hex.substring(0, 2), 16);
                int g = Integer.parseInt(hex.substring(2, 4), 16);
                int b = Integer.parseInt(hex.substring(4, 6), 16);
                return Color.fromRgb(r / 255.0, g / 255.0, b / 255.0);
            } catch (NumberFormatException e) {
                return null;
            }
        } else if (hex.length() == 12) {
            // Extended format #RRRRGGGGBBBB
            try {
                int r = Integer.parseInt(hex.substring(0, 4), 16);
                int g = Integer.parseInt(hex.substring(4, 8), 16);
                int b = Integer.parseInt(hex.substring(8, 12), 16);
                return Color.fromRgb(r / 65535.0, g / 65535.0, b / 65535.0);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns the first direct child element with the given tag name, or null.
     * Unlike {@code getElementsByTagName}, this does not descend into nested
     * elements (so a parent's {@code <popup>} is not confused with a deeper one).
     */
    private static Element getDirectChild(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equalsIgnoreCase(child.getNodeName())) {
                return (Element) child;
            }
        }
        return null;
    }

    /**
     * Gets the text content of the first child element with the given tag name.
     */
    private static String getChildText(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        if (children.getLength() > 0) {
            String text = children.item(0).getTextContent();
            return (text != null && !text.isEmpty()) ? text : null;
        }
        return null;
    }

    /**
     * Gets an integer attribute value, returning the default if not present or invalid.
     */
    private static int getIntAttr(Element elem, String name, int defaultValue) {
        String val = elem.getAttribute(name);
        if (val == null || val.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Parses a delimited string of numbers into a double array.
     */
    private static double[] parseNumberList(String s, char delimiter) {
        if (s == null || s.isEmpty()) return null;
        String[] parts = s.split(String.valueOf(delimiter));
        double[] result = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return result;
    }

    /**
     * Unescapes XML entities in a string.
     *
     * @param s the string containing XML entities
     * @return the unescaped string
     */
    public static String unescapeXml(String s) {
        if (s == null) return null;
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }
}
