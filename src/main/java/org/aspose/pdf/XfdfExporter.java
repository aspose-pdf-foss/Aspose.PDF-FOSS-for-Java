package org.aspose.pdf;

import org.aspose.pdf.annotations.*;
import org.aspose.pdf.forms.Field;
import org.aspose.pdf.forms.Form;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.logging.Logger;

/**
 * Exports annotations and form field data from a PDF document to XFDF
 * (XML Forms Data Format) per XFDF Specification Version 3.0 (August 2009).
 * <p>
 * Produces XML with structure: {@code <xfdf><f/><fields/><annots/></xfdf>}.
 * All annotation attributes, child elements, and form field values are exported.
 * </p>
 *
 * @see XfdfImporter
 */
public final class XfdfExporter {

    private static final Logger LOG = Logger.getLogger(XfdfExporter.class.getName());

    private static final String XFDF_NS = "http://ns.adobe.com/xfdf/";

    private static final Map<String, String> SUBTYPE_TO_ELEMENT = new HashMap<>();

    static {
        SUBTYPE_TO_ELEMENT.put("Text", "text");
        SUBTYPE_TO_ELEMENT.put("Highlight", "highlight");
        SUBTYPE_TO_ELEMENT.put("Underline", "underline");
        SUBTYPE_TO_ELEMENT.put("StrikeOut", "strikeout");
        SUBTYPE_TO_ELEMENT.put("Squiggly", "squiggly");
        SUBTYPE_TO_ELEMENT.put("FreeText", "freetext");
        SUBTYPE_TO_ELEMENT.put("Line", "line");
        SUBTYPE_TO_ELEMENT.put("Circle", "circle");
        SUBTYPE_TO_ELEMENT.put("Square", "square");
        SUBTYPE_TO_ELEMENT.put("Polygon", "polygon");
        SUBTYPE_TO_ELEMENT.put("PolyLine", "polyline");
        SUBTYPE_TO_ELEMENT.put("Link", "link");
        SUBTYPE_TO_ELEMENT.put("Stamp", "stamp");
        SUBTYPE_TO_ELEMENT.put("Caret", "caret");
        SUBTYPE_TO_ELEMENT.put("Ink", "ink");
        SUBTYPE_TO_ELEMENT.put("Popup", "popup");
        SUBTYPE_TO_ELEMENT.put("FileAttachment", "fileattachment");
        SUBTYPE_TO_ELEMENT.put("Redact", "redact");
    }

    /** Flag bit positions and their XFDF string names per spec §3.3. */
    private static final String[] FLAG_NAMES = {
        "invisible", "hidden", "print", "nozoom", "norotate",
        "noview", "readonly", "locked", "togglenoview", "lockedcontents"
    };

    private XfdfExporter() {
        // utility class
    }

    /**
     * Exports all annotations and form fields from the document to an XFDF file.
     *
     * @param document the document whose data to export
     * @param filePath the output file path
     * @throws IOException              if writing fails
     * @throws IllegalArgumentException if document or filePath is null
     */
    public static void export(Document document, String filePath) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("File path must not be null");
        }
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            export(document, fos);
        }
    }

    /**
     * Exports all annotations and form fields from the document to an XFDF output stream.
     *
     * @param document the document whose data to export
     * @param output   the output stream to write XFDF XML to
     * @throws IOException              if writing fails
     * @throws IllegalArgumentException if document or output is null
     */
    public static void export(Document document, OutputStream output) throws IOException {
        export(document, output, null);
    }

    /**
     * Exports annotations from the document to an XFDF output stream,
     * optionally filtering by page range and annotation type.
     *
     * @param document the document whose data to export
     * @param output   the output stream to write XFDF XML to
     * @param filter   optional export filter (null = export all)
     * @throws IOException if writing fails
     */
    public static void export(Document document, OutputStream output, ExportFilter filter) throws IOException {
        if (document == null) {
            throw new IllegalArgumentException("Document must not be null");
        }
        if (output == null) {
            throw new IllegalArgumentException("Output stream must not be null");
        }

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            org.w3c.dom.Document xmlDoc = db.newDocument();
            xmlDoc.setXmlStandalone(true);

            // Root <xfdf>
            org.w3c.dom.Element xfdf = xmlDoc.createElement("xfdf");
            xfdf.setAttribute("xmlns", XFDF_NS);
            xfdf.setAttribute("xml:space", "preserve");
            xmlDoc.appendChild(xfdf);

            // <f href="..."/> — source PDF reference (omitted if filename unknown)

            // <fields> section
            exportFields(document, xmlDoc, xfdf);

            // <annots> section
            org.w3c.dom.Element annots = xmlDoc.createElement("annots");
            PageCollection pages = document.getPages();
            int startPage = (filter != null) ? filter.startPage : 1;
            int endPage = (filter != null && filter.endPage > 0) ? filter.endPage : pages.getCount();
            Set<String> allowedTypes = (filter != null && filter.annotationTypes != null)
                    ? new HashSet<>(Arrays.asList(filter.annotationTypes)) : null;

            for (int p = startPage; p <= Math.min(endPage, pages.getCount()); p++) {
                Page page = pages.get(p);
                AnnotationCollection pageAnnots = page.getAnnotations();
                int pageIndex = p - 1; // XFDF uses 0-based page indices

                for (Annotation annot : pageAnnots) {
                    String subtype = annot.getSubtype();
                    if ("Popup".equals(subtype)) {
                        // a popup is serialized as a <popup> CHILD of its markup parent
                        // (see writeAnnotation), never as a standalone XFDF element
                        continue;
                    }
                    if (allowedTypes != null && !allowedTypes.contains(subtype)) {
                        continue;
                    }
                    writeAnnotation(xmlDoc, annots, annot, pageIndex);
                }
            }
            xfdf.appendChild(annots);

            // Write XML to output
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.transform(new DOMSource(xmlDoc), new StreamResult(output));

            LOG.fine("XFDF export completed");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to generate XFDF: " + e.getMessage(), e);
        }
    }

    /**
     * Exports form field values to the {@code <fields>} section.
     */
    private static void exportFields(Document document, org.w3c.dom.Document xmlDoc,
                                     org.w3c.dom.Element xfdf) {
        try {
            Form form = document.getForm();
            if (form == null || form.getCount() == 0) return;

            org.w3c.dom.Element fieldsElem = xmlDoc.createElement("fields");
            Field[] fields = form.getFields();
            for (Field field : fields) {
                String name = field.getFullName();
                String value = field.getValue();
                if (name == null || name.isEmpty()) continue;

                // Handle hierarchical field names (dot-separated)
                String[] parts = name.split("\\.");
                org.w3c.dom.Element parent = fieldsElem;
                for (int i = 0; i < parts.length; i++) {
                    if (i < parts.length - 1) {
                        // Intermediate: create nested <field name="...">
                        org.w3c.dom.Element nested = xmlDoc.createElement("field");
                        nested.setAttribute("name", parts[i]);
                        parent.appendChild(nested);
                        parent = nested;
                    } else {
                        // Leaf: create <field name="..."><value>...</value></field>
                        org.w3c.dom.Element fieldElem = xmlDoc.createElement("field");
                        fieldElem.setAttribute("name", parts[i]);
                        if (value != null) {
                            org.w3c.dom.Element valueElem = xmlDoc.createElement("value");
                            valueElem.setTextContent(value);
                            fieldElem.appendChild(valueElem);
                        }
                        parent.appendChild(fieldElem);
                    }
                }
            }
            if (fieldsElem.hasChildNodes()) {
                xfdf.appendChild(fieldsElem);
            }
        } catch (Exception e) {
            LOG.fine(() -> "No form fields to export: " + e.getMessage());
        }
    }

    /**
     * Writes a single annotation as an XFDF element with all attributes and child elements.
     */
    private static void writeAnnotation(org.w3c.dom.Document xmlDoc, org.w3c.dom.Element annots,
                                        Annotation annot, int pageIndex) {
        String subtype = annot.getSubtype();
        String element = SUBTYPE_TO_ELEMENT.get(subtype);
        if (element == null) {
            element = subtype != null ? subtype.toLowerCase() : "unknown";
        }

        org.w3c.dom.Element elem = xmlDoc.createElement(element);

        // Common attributes (all annotation types)
        elem.setAttribute("page", String.valueOf(pageIndex));

        Rectangle rect = annot.getRect();
        if (rect != null) {
            elem.setAttribute("rect", formatDouble(rect.getLLX()) + ","
                    + formatDouble(rect.getLLY()) + ","
                    + formatDouble(rect.getURX()) + ","
                    + formatDouble(rect.getURY()));
        }

        Color color = annot.getColor();
        if (color != null) {
            elem.setAttribute("color", colorToHex(color));
        }

        String modified = annot.getModified();
        if (modified != null && !modified.isEmpty()) {
            elem.setAttribute("date", modified);
        }

        int flags = annot.getFlags();
        if (flags != 0) {
            elem.setAttribute("flags", flagsToString(flags));
        }

        String name = annot.getName();
        if (name != null && !name.isEmpty()) {
            elem.setAttribute("name", name);
        }

        // Markup annotation attributes
        if (annot instanceof MarkupAnnotation) {
            MarkupAnnotation markup = (MarkupAnnotation) annot;

            String title = markup.getTitle();
            if (title != null && !title.isEmpty()) {
                elem.setAttribute("title", title);
            }

            String creationDate = markup.getCreationDate();
            if (creationDate != null && !creationDate.isEmpty()) {
                elem.setAttribute("creationdate", creationDate);
            }

            double opacity = markup.getOpacity();
            if (opacity < 1.0) {
                elem.setAttribute("opacity", formatDouble(opacity));
            }

            String subject = markup.getSubject();
            if (subject != null && !subject.isEmpty()) {
                elem.setAttribute("subject", subject);
            }

            // In-reply-to: export the NM of the referenced annotation
            Annotation irt = markup.getInReplyTo();
            if (irt != null) {
                String irtName = irt.getName();
                if (irtName != null && !irtName.isEmpty()) {
                    elem.setAttribute("inreplyto", irtName);
                }
            }

            String replyType = markup.getReplyType();
            if (replyType != null && !replyType.isEmpty()) {
                elem.setAttribute("replyType", replyType.equals("R") ? "reply" : "group");
            }

            // Rich text as child element
            String richText = markup.getRichText();
            if (richText != null && !richText.isEmpty()) {
                org.w3c.dom.Element rtElem = xmlDoc.createElement("contents-richtext");
                // Rich text is XHTML; store as text content (will be escaped by DOM).
                // Re-serialise through a DOM first so the emitted markup is canonical
                // (e.g. the stray "attr >" space in a source /RC collapses to "attr>"),
                // matching how Acrobat/Aspose normalise richtext on XFDF round-trip.
                rtElem.setTextContent(normalizeRichText(richText));
                elem.appendChild(rtElem);
            }

            // Redaction overlay text (round-trips via the redact element's attribute)
            if (markup instanceof RedactionAnnotation) {
                String overlay = ((RedactionAnnotation) markup).getOverlayText();
                if (overlay != null && !overlay.isEmpty()) {
                    elem.setAttribute("overlaytext", overlay);
                }
            }

            // Popup child element
            PopupAnnotation popup = markup.getPopup();
            if (popup != null) {
                org.w3c.dom.Element popupElem = xmlDoc.createElement("popup");
                popupElem.setAttribute("page", String.valueOf(pageIndex));
                Rectangle popupRect = popup.getRect();
                if (popupRect != null) {
                    popupElem.setAttribute("rect", formatDouble(popupRect.getLLX()) + ","
                            + formatDouble(popupRect.getLLY()) + ","
                            + formatDouble(popupRect.getURX()) + ","
                            + formatDouble(popupRect.getURY()));
                }
                popupElem.setAttribute("open", popup.getOpen() ? "yes" : "no");
                int popupFlags = popup.getFlags();
                if (popupFlags != 0) {
                    popupElem.setAttribute("flags", flagsToString(popupFlags));
                }
                elem.appendChild(popupElem);
            }
        }

        // Type-specific attributes
        if (annot instanceof TextAnnotation) {
            TextAnnotation text = (TextAnnotation) annot;
            String icon = text.getIcon();
            if (icon != null && !"Note".equals(icon)) {
                elem.setAttribute("icon", icon);
            }
            String state = text.getState();
            if (state != null && !state.isEmpty()) {
                elem.setAttribute("state", state);
            }
            String stateModel = text.getStateModel();
            if (stateModel != null && !stateModel.isEmpty()) {
                elem.setAttribute("statemodel", stateModel);
            }
        }

        // Stamp icon (/Name entry) — XFDF carries it in the same "icon" attribute
        // used for text-note icons (XFDF 3.0 §6.5). Without this the re-imported
        // stamp falls back to the default "Draft" (BUG-XFDF-STAMP-ICON).
        if (annot instanceof StampAnnotation) {
            String icon = ((StampAnnotation) annot).getIcon();
            if (icon != null && !icon.isEmpty()) {
                elem.setAttribute("icon", icon);
            }
        }

        // QuadPoints for text markup annotations
        if (annot instanceof HighlightAnnotation) {
            writeQuadPoints(elem, ((HighlightAnnotation) annot).getQuadPoints());
        } else if (annot instanceof UnderlineAnnotation) {
            writeQuadPoints(elem, ((UnderlineAnnotation) annot).getQuadPoints());
        } else if (annot instanceof StrikeOutAnnotation) {
            writeQuadPoints(elem, ((StrikeOutAnnotation) annot).getQuadPoints());
        } else if (annot instanceof SquigglyAnnotation) {
            writeQuadPoints(elem, ((SquigglyAnnotation) annot).getQuadPoints());
        }

        // Line annotation: start, end
        if (annot instanceof LineAnnotation) {
            double[] line = ((LineAnnotation) annot).getLine();
            if (line != null && line.length >= 4) {
                elem.setAttribute("start", formatDouble(line[0]) + "," + formatDouble(line[1]));
                elem.setAttribute("end", formatDouble(line[2]) + "," + formatDouble(line[3]));
            }
        }

        // Ink annotation: inklist child element
        if (annot instanceof InkAnnotation) {
            List<double[]> inkList = ((InkAnnotation) annot).getInkList();
            if (inkList != null && !inkList.isEmpty()) {
                org.w3c.dom.Element inklistElem = xmlDoc.createElement("inklist");
                for (double[] stroke : inkList) {
                    org.w3c.dom.Element gesture = xmlDoc.createElement("gesture");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < stroke.length; i += 2) {
                        if (i > 0) sb.append(';');
                        sb.append(formatDouble(stroke[i]));
                        if (i + 1 < stroke.length) {
                            sb.append(',').append(formatDouble(stroke[i + 1]));
                        }
                    }
                    gesture.setTextContent(sb.toString());
                    inklistElem.appendChild(gesture);
                }
                elem.appendChild(inklistElem);
            }
        }

        // Polygon/Polyline: vertices child element
        if (annot instanceof PolygonAnnotation) {
            writeVertices(xmlDoc, elem, ((PolygonAnnotation) annot).getVertices());
        } else if (annot instanceof PolylineAnnotation) {
            writeVertices(xmlDoc, elem, ((PolylineAnnotation) annot).getVertices());
        }

        // Contents as child element
        String contents = annot.getContents();
        if (contents != null && !contents.isEmpty()) {
            org.w3c.dom.Element contentsElem = xmlDoc.createElement("contents");
            contentsElem.setTextContent(contents);
            elem.appendChild(contentsElem);
        }

        annots.appendChild(elem);
    }

    /**
     * Writes quad points as the "coords" attribute.
     */
    private static void writeQuadPoints(org.w3c.dom.Element elem, double[] quadPoints) {
        if (quadPoints == null || quadPoints.length == 0) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < quadPoints.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(formatDouble(quadPoints[i]));
        }
        elem.setAttribute("coords", sb.toString());
    }

    /**
     * Writes vertices as a {@code <vertices>} child element.
     */
    private static void writeVertices(org.w3c.dom.Document xmlDoc, org.w3c.dom.Element elem, double[] vertices) {
        if (vertices == null || vertices.length == 0) return;
        org.w3c.dom.Element vertElem = xmlDoc.createElement("vertices");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vertices.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(formatDouble(vertices[i]));
        }
        vertElem.setTextContent(sb.toString());
        elem.appendChild(vertElem);
    }

    /**
     * Converts annotation flags bitmask to comma-separated string names per XFDF spec.
     *
     * @param flags the flags bitmask
     * @return comma-separated flag names (e.g. "print,nozoom,norotate")
     */
    public static String flagsToString(int flags) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < FLAG_NAMES.length; i++) {
            if ((flags & (1 << i)) != 0) {
                names.add(FLAG_NAMES[i]);
            }
        }
        return String.join(",", names);
    }

    /**
     * Converts a Color to a hex string like "#RRGGBB".
     */
    static String colorToHex(Color color) {
        int r = clamp((int) Math.round(color.getR() * 255));
        int g = clamp((int) Math.round(color.getG() * 255));
        int b = clamp((int) Math.round(color.getB() * 255));
        return String.format("#%02X%02X%02X", r, g, b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /**
     * Formats a double value, removing unnecessary trailing zeros.
     */
    static String formatDouble(double v) {
        if (v == (long) v) {
            return Long.toString((long) v);
        }
        return String.valueOf(v);
    }

    /**
     * Canonicalises a richtext (XHTML) string by parsing it into a DOM and
     * re-serialising the root element. This collapses source-specific formatting
     * artefacts — most notably a stray space before a tag's closing {@code >}
     * (e.g. {@code <body ... spec="2.0.2" >} becomes {@code <body ... spec="2.0.2">}) —
     * so that an XFDF round-trip yields the same normalised markup Acrobat/Aspose
     * produce. On any parse/serialise error the original string is returned
     * unchanged (richtext is best-effort, never a hard failure).
     *
     * @param richText the raw richtext string (may include an XML declaration)
     * @return the canonicalised richtext, or the input unchanged on error
     */
    static String normalizeRichText(String richText) {
        try {
            DocumentBuilder builder = org.aspose.pdf.engine.xml.SecureXml.newBuilder(false);
            org.w3c.dom.Document parsed = builder.parse(
                    new org.xml.sax.InputSource(new java.io.StringReader(richText)));
            org.w3c.dom.Element root = parsed.getDocumentElement();
            if (root == null) return richText;

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            java.io.StringWriter sw = new java.io.StringWriter();
            transformer.transform(new DOMSource(root), new StreamResult(sw));
            String body = sw.toString();

            // Preserve the leading declaration form the source used (Acrobat emits
            // the encodingless "<?xml version="1.0"?>"); default to that.
            String decl = "<?xml version=\"1.0\"?>";
            String trimmed = richText.trim();
            if (trimmed.startsWith("<?xml")) {
                int end = trimmed.indexOf("?>");
                if (end > 0) decl = trimmed.substring(0, end + 2);
            }
            return decl + body;
        } catch (Exception e) {
            LOG.fine(() -> "Could not normalise richtext, keeping verbatim: " + e.getMessage());
            return richText;
        }
    }

    /**
     * Escapes special XML characters in a string for safe use in XML attributes or text.
     *
     * @param s the string to escape
     * @return the escaped string
     */
    public static String escapeXml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }

    /**
     * Filter for controlling which annotations are exported.
     */
    public static class ExportFilter {
        /** 1-based start page (inclusive). */
        public int startPage = 1;
        /** 1-based end page (inclusive). 0 = all pages. */
        public int endPage = 0;
        /** Annotation subtypes to include (null = all). */
        public String[] annotationTypes;
    }
}
