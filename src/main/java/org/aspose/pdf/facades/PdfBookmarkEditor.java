package org.aspose.pdf.facades;

import org.aspose.pdf.Color;
import org.aspose.pdf.Document;
import org.aspose.pdf.ExplicitDestination;
import org.aspose.pdf.GoToAction;
import org.aspose.pdf.OutlineCollection;
import org.aspose.pdf.OutlineItemCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.PageCollection;
import org.aspose.pdf.PdfAction;
import org.aspose.pdf.XYZExplicitDestination;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides methods for creating, extracting, and deleting bookmarks (outlines)
 * in a PDF document.
 */
public class PdfBookmarkEditor implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PdfBookmarkEditor.class.getName());

    private Document document;

    /**
     * Creates a new {@code PdfBookmarkEditor} instance.
     */
    public PdfBookmarkEditor() {
    }

    /** Creates a new editor bound to {@code document}. */
    public PdfBookmarkEditor(Document document) {
        bindPdf(document);
    }

    /** Returns the bound document, or {@code null}. Mirrors C# {@code PdfBookmarkEditor.Document}. */
    public Document getDocument() {
        return document;
    }

    /**
     * Binds a PDF file to this editor.
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
     * Binds an existing {@link Document} to this editor.
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
     * Extracts all bookmarks from the bound document as a {@link Bookmarks} collection.
     * Child bookmarks are extracted recursively.
     *
     * @return the bookmarks collection, or an empty collection if none exist
     */
    /**
     * Exports the document's bookmark tree to an XML file. Mirrors the C#
     * facade method of the same name. The schema is a simple recursive
     * {@code <Bookmark>} hierarchy carrying {@code Title}, {@code PageNumber},
     * {@code Action} and (optionally) nested {@code <Bookmark>} children.
     *
     * @param xmlFile path to the output XML file
     * @throws java.io.IOException on write failure
     */
    public void exportBookmarksToXML(String xmlFile) throws java.io.IOException {
        // Use the TOP-LEVEL tree (each root carries its descendants as nested
        // childItems), not the flattened extractBookmarks() list — writeBookmarkXml
        // already recurses into childItems, so iterating the flattened list would
        // emit every nested node twice (once nested, once as a root). See
        // extractBookmarks()'s flattening note (PDFNET-46150).
        Bookmarks bookmarks = topLevelBookmarkTree();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Bookmarks>\n");
        for (Bookmark bm : bookmarks) {
            writeBookmarkXml(sb, bm, 1);
        }
        sb.append("</Bookmarks>\n");
        java.nio.file.Files.write(java.nio.file.Paths.get(xmlFile),
                sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Imports a bookmark tree previously written by
     * {@link #exportBookmarksToXML(String)} and adds it to the bound document's
     * outline.
     *
     * @param xmlFile path to the XML file
     * @throws java.io.IOException on read or parse failure
     */
    public void importBookmarksWithXML(String xmlFile) throws java.io.IOException {
        if (document == null) {
            LOG.warning("importBookmarksWithXML called with no bound document");
            return;
        }
        org.w3c.dom.Document xml;
        try {
            // bookmark XML is untrusted input: parse with the shared XXE-hardened builder
            // (DOCTYPE disallowed, external entities/XInclude disabled).
            javax.xml.parsers.DocumentBuilder db = org.aspose.pdf.engine.xml.SecureXml.newBuilder(false);
            xml = db.parse(new java.io.File(xmlFile));
        } catch (org.xml.sax.SAXException e) {
            throw new java.io.IOException("Failed to parse bookmark XML: " + xmlFile, e);
        }
        try {
            org.w3c.dom.Element root = xml.getDocumentElement();
            org.aspose.pdf.OutlineCollection outlines = document.getOutlines();
            org.w3c.dom.NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                org.w3c.dom.Node n = children.item(i);
                if (n.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
                if (!"Bookmark".equals(n.getNodeName())) continue;
                addBookmarkFromXml(outlines, null, (org.w3c.dom.Element) n);
            }
        } catch (RuntimeException e) {
            throw new java.io.IOException("Failed to import bookmarks from " + xmlFile, e);
        }
    }

    private static void writeBookmarkXml(StringBuilder sb, Bookmark bm, int depth) {
        String indent = "  ".repeat(depth);
        sb.append(indent).append("<Bookmark>\n");
        appendXmlElement(sb, indent + "  ", "Title", bm.getTitle());
        appendXmlElement(sb, indent + "  ", "PageNumber", Integer.toString(bm.getPageNumber()));
        if (bm.getAction() != null) {
            appendXmlElement(sb, indent + "  ", "Action", bm.getAction());
        }
        java.util.List<Bookmark> kids = bm.getChildItems();
        if (kids != null && !kids.isEmpty()) {
            sb.append(indent).append("  <Children>\n");
            for (Bookmark child : kids) {
                writeBookmarkXml(sb, child, depth + 2);
            }
            sb.append(indent).append("  </Children>\n");
        }
        sb.append(indent).append("</Bookmark>\n");
    }

    private static void appendXmlElement(StringBuilder sb, String indent, String name, String value) {
        sb.append(indent).append("<").append(name).append(">")
                .append(escapeXml(value == null ? "" : value))
                .append("</").append(name).append(">\n");
    }

    private static String escapeXml(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '&': out.append("&amp;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&apos;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private void addBookmarkFromXml(org.aspose.pdf.OutlineCollection outlines,
                                     org.aspose.pdf.OutlineItemCollection parent,
                                     org.w3c.dom.Element bmEl) throws java.io.IOException {
        String title = childText(bmEl, "Title");
        String pageStr = childText(bmEl, "PageNumber");
        int pageNumber = 0;
        if (pageStr != null && !pageStr.isEmpty()) {
            try { pageNumber = Integer.parseInt(pageStr.trim()); } catch (NumberFormatException ignored) {}
        }
        org.aspose.pdf.OutlineItemCollection item =
                new org.aspose.pdf.OutlineItemCollection(outlines);
        item.setTitle(title != null ? title : "");
        if (pageNumber >= 1 && pageNumber <= document.getPages().getCount()) {
            Page targetPage = document.getPages().get(pageNumber);
            item.setDestination(new org.aspose.pdf.XYZExplicitDestination(
                    targetPage, Double.NaN, Double.NaN, 0));
        }
        if (parent != null) {
            parent.add(item);
        } else {
            outlines.add(item);
        }
        org.w3c.dom.Element children = firstChildElement(bmEl, "Children");
        if (children != null) {
            org.w3c.dom.NodeList kids = children.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                org.w3c.dom.Node n = kids.item(i);
                if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                        && "Bookmark".equals(n.getNodeName())) {
                    addBookmarkFromXml(outlines, item, (org.w3c.dom.Element) n);
                }
            }
        }
    }

    private static String childText(org.w3c.dom.Element parent, String tagName) {
        org.w3c.dom.Element child = firstChildElement(parent, tagName);
        return child != null ? child.getTextContent() : null;
    }

    private static org.w3c.dom.Element firstChildElement(org.w3c.dom.Element parent, String tagName) {
        org.w3c.dom.NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Node n = nodes.item(i);
            if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                    && tagName.equals(n.getNodeName())) {
                return (org.w3c.dom.Element) n;
            }
        }
        return null;
    }

    public Bookmarks extractBookmarks() {
        Bookmarks result = new Bookmarks();
        try {
            OutlineCollection outlines = document.getOutlines();
            if (outlines == null) {
                return result;
            }
            // Aspose returns ALL bookmarks flattened in document (depth-first,
            // pre-order) order, not just the top-level items — a nested outline
            // with 10 nodes yields a 10-element collection (PDFNET-46150).
            for (OutlineItemCollection item : outlines) {
                flattenBookmarks(item, result);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to extract bookmarks", e);
        }
        return result;
    }

    /**
     * Builds the bookmark tree keeping only the top-level roots in the result
     * collection; each root carries its descendants via {@link Bookmark#getChildItems()}.
     * This is the nested (non-flattened) view used by the XML export so the
     * recursive writer emits each node exactly once.
     */
    private Bookmarks topLevelBookmarkTree() {
        Bookmarks result = new Bookmarks();
        try {
            OutlineCollection outlines = document.getOutlines();
            if (outlines == null) {
                return result;
            }
            for (OutlineItemCollection item : outlines) {
                result.add(convertOutline(item));
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to read bookmark tree", e);
        }
        return result;
    }

    /**
     * Appends an outline node and all of its descendants to {@code result} in
     * depth-first pre-order (parent before children), matching the flat ordering
     * Aspose's {@code ExtractBookmarks} produces.
     */
    private void flattenBookmarks(OutlineItemCollection item, Bookmarks result) {
        result.add(convertOutline(item));
        for (OutlineItemCollection child : item) {
            flattenBookmarks(child, result);
        }
    }

    /**
     * Creates bookmarks for all pages in the document.
     * Each bookmark is titled "Page N" where N is the 1-based page number.
     */
    public void createBookmarks() {
        try {
            OutlineCollection outlines = document.getOutlines();
            PageCollection pages = document.getPages();
            for (int i = 1; i <= pages.getCount(); i++) {
                Page page = pages.get(i);
                OutlineItemCollection item = new OutlineItemCollection(outlines);
                item.setTitle("Page " + i);
                item.setDestination(new XYZExplicitDestination(page, Double.NaN, Double.NaN, 0));
                outlines.add(item);
            }
            LOG.fine("Created bookmarks for " + pages.getCount() + " pages");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to create bookmarks for all pages", e);
        }
    }

    /**
     * Creates bookmarks for all pages with specified visual properties.
     *
     * @param color  the text color for bookmarks
     * @param bold   whether to display bookmarks in bold
     * @param italic whether to display bookmarks in italic
     */
    public void createBookmarks(Color color, boolean bold, boolean italic) {
        try {
            OutlineCollection outlines = document.getOutlines();
            PageCollection pages = document.getPages();
            for (int i = 1; i <= pages.getCount(); i++) {
                Page page = pages.get(i);
                OutlineItemCollection item = new OutlineItemCollection(outlines);
                item.setTitle("Page " + i);
                item.setDestination(new XYZExplicitDestination(page, Double.NaN, Double.NaN, 0));
                item.setColor(color);
                item.setBold(bold);
                item.setItalic(italic);
                outlines.add(item);
            }
            LOG.fine("Created styled bookmarks for " + pages.getCount() + " pages");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to create styled bookmarks", e);
        }
    }

    /**
     * Extracts bookmarks matching the specified title from the bound document.
     *
     * @param title the title to match
     * @return the matching bookmarks, or an empty collection if none found
     */
    public Bookmarks extractBookmarks(String title) {
        Bookmarks result = new Bookmarks();
        try {
            OutlineCollection outlines = document.getOutlines();
            if (outlines == null) return result;
            for (OutlineItemCollection item : outlines) {
                collectMatchingBookmarks(item, title, result, 1);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to extract bookmarks by title: " + title, e);
        }
        return result;
    }

    /**
     * Recursively collects bookmarks matching the specified title.
     */
    private void collectMatchingBookmarks(OutlineItemCollection item, String title,
                                          Bookmarks result, int level) {
        if (title.equals(item.getTitle())) {
            Bookmark bm = convertOutline(item);
            bm.setLevel(level);
            result.add(bm);
        }
        for (OutlineItemCollection child : item) {
            collectMatchingBookmarks(child, title, result, level + 1);
        }
    }

    /**
     * Creates a bookmark pointing to the specified page and adds it to the document outlines.
     *
     * @param title      the bookmark title
     * @param pageNumber the 1-based page number
     * @return {@code true} on success
     */
    public boolean createBookmarkOfPage(String title, int pageNumber) {
        try {
            OutlineCollection outlines = document.getOutlines();
            Page targetPage = document.getPages().get(pageNumber);
            OutlineItemCollection item = new OutlineItemCollection(outlines);
            item.setTitle(title);
            item.setAction(new GoToAction(targetPage));
            outlines.add(item);
            LOG.fine("Created bookmark '" + title + "' for page " + pageNumber);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to create bookmark for page " + pageNumber, e);
            return false;
        }
    }

    /**
     * Deletes all bookmarks from the document.
     *
     * @return {@code true} on success
     */
    public boolean deleteBookmarks() {
        try {
            OutlineCollection outlines = document.getOutlines();
            outlines.clear();
            LOG.fine("Deleted all bookmarks");
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to delete bookmarks", e);
            return false;
        }
    }

    /**
     * Deletes bookmarks with the specified title.
     *
     * @param title the title of bookmarks to delete
     * @return {@code true} on success
     */
    public boolean deleteBookmarks(String title) {
        try {
            OutlineCollection outlines = document.getOutlines();
            List<Integer> toDelete = new ArrayList<>();
            int index = 0;
            for (OutlineItemCollection item : outlines) {
                index++;
                if (title.equals(item.getTitle())) {
                    toDelete.add(index);
                }
            }
            // Delete in reverse order to preserve indices
            for (int i = toDelete.size() - 1; i >= 0; i--) {
                outlines.delete(toDelete.get(i));
            }
            LOG.fine("Deleted " + toDelete.size() + " bookmarks with title '" + title + "'");
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to delete bookmarks by title: " + title, e);
            return false;
        }
    }

    /**
     * Closes the editor and releases the bound document.
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

    /**
     * Recursively converts an {@link OutlineItemCollection} to a {@link Bookmark}.
     */
    private Bookmark convertOutline(OutlineItemCollection item) {
        Bookmark bm = new Bookmark();
        bm.setTitle(item.getTitle());
        bm.setLevel(item.getLevel());
        try {
            ExplicitDestination dest = item.getDestination();
            if (dest != null) {
                bm.setDestination(dest);
                bm.setPageNumber(dest.getPageNumber());
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not read destination for bookmark: " + item.getTitle(), e);
        }
        try {
            PdfAction action = item.getAction();
            if (action != null) {
                bm.setAction(action.toString());
                // Try to extract page number from GoToAction
                if (action instanceof GoToAction) {
                    ExplicitDestination actionDest = ((GoToAction) action).getDestination();
                    if (actionDest != null) {
                        bm.setPageNumber(actionDest.getPageNumber());
                        if (bm.getDestination() == null) {
                            bm.setDestination(actionDest);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not read action for bookmark: " + item.getTitle(), e);
        }
        // Recurse into children
        for (OutlineItemCollection child : item) {
            bm.getChildItems().add(convertOutline(child));
        }
        return bm;
    }
}
