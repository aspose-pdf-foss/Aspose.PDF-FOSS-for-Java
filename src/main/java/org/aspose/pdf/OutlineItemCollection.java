package org.aspose.pdf;

import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfString;
import org.aspose.pdf.engine.parser.PDFParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Represents a single bookmark (outline item) in the document outline tree
 * (ISO 32000-1:2008, §12.3.3, Table 153).
 * <p>
 * Each item has a title, optional visual properties (bold, italic, color),
 * a destination or action, and can contain child items forming a hierarchy.
 * </p>
 */
public class OutlineItemCollection implements Iterable<OutlineItemCollection> {

    private static final Logger LOG = Logger.getLogger(OutlineItemCollection.class.getName());

    private final PdfDictionary dict;
    private final OutlineCollection rootOutlines;
    private final PDFParser parser;
    private List<OutlineItemCollection> children;

    /**
     * Creates a new empty bookmark.
     *
     * @param outlines the root outline collection
     */
    public OutlineItemCollection(OutlineCollection outlines) {
        this.rootOutlines = outlines;
        this.parser = outlines != null ? outlines.getParser() : null;
        this.dict = new PdfDictionary();
    }

    /**
     * Wraps an existing outline item dictionary.
     */
    OutlineItemCollection(PdfDictionary dict, OutlineCollection root, PDFParser parser) {
        this.dict = dict != null ? dict : new PdfDictionary();
        this.rootOutlines = root;
        this.parser = parser;
    }

    // ── Title ──

    /**
     * Returns the bookmark title.
     *
     * @return the title string
     */
    public String getTitle() {
        PdfBase t = resolve(dict.get("Title"));
        if (t instanceof PdfString) return ((PdfString) t).getString();
        return "";
    }

    /**
     * Sets the bookmark title.
     *
     * @param title the title
     */
    public void setTitle(String title) {
        dict.set(PdfName.of("Title"),
                title != null ? new PdfString(title) : new PdfString(new byte[0]));
    }

    // ── Visual properties (§12.3.3, Table 153: /F, /C) ──

    /**
     * Returns whether the title is displayed bold.
     *
     * @return true if bold
     */
    public boolean getBold() {
        return (dict.getInt("F", 0) & 2) != 0;
    }

    /**
     * Sets bold display.
     *
     * @param bold true for bold
     */
    public void setBold(boolean bold) {
        int flags = dict.getInt("F", 0);
        dict.set(PdfName.of("F"), PdfInteger.valueOf(bold ? (flags | 2) : (flags & ~2)));
    }

    /**
     * Returns whether the title is displayed italic.
     *
     * @return true if italic
     */
    public boolean getItalic() {
        return (dict.getInt("F", 0) & 1) != 0;
    }

    /**
     * Sets italic display.
     *
     * @param italic true for italic
     */
    public void setItalic(boolean italic) {
        int flags = dict.getInt("F", 0);
        dict.set(PdfName.of("F"), PdfInteger.valueOf(italic ? (flags | 1) : (flags & ~1)));
    }

    /**
     * Returns the text color for this bookmark.
     *
     * @return the color (defaults to black)
     */
    public Color getColor() {
        PdfBase c = dict.get("C");
        if (c instanceof PdfArray && ((PdfArray) c).size() == 3) {
            PdfArray arr = (PdfArray) c;
            return Color.fromRgb(arr.getFloat(0, 0), arr.getFloat(1, 0), arr.getFloat(2, 0));
        }
        return Color.BLACK;
    }

    /**
     * Sets the text color for this bookmark.
     *
     * @param color the color
     */
    public void setColor(Color color) {
        if (color == null) {
            dict.remove(PdfName.of("C"));
            return;
        }
        PdfArray c = new PdfArray();
        c.add(new PdfFloat(color.getR()));
        c.add(new PdfFloat(color.getG()));
        c.add(new PdfFloat(color.getB()));
        dict.set(PdfName.of("C"), c);
    }

    // ── Destination ──

    /**
     * Returns the destination for this bookmark.
     *
     * @return the destination, or null
     * @throws IOException if parsing fails
     */
    public ExplicitDestination getDestination() throws IOException {
        PdfBase d = resolve(dict.get("Dest"));
        if (d instanceof PdfArray) {
            Document doc = rootOutlines != null ? rootOutlines.getDocument() : null;
            return ExplicitDestination.fromPdfArray((PdfArray) d, doc);
        }
        // Named destination (string or name)
        if (d instanceof PdfString || d instanceof PdfName) {
            String name = (d instanceof PdfString)
                    ? ((PdfString) d).getString()
                    : ((PdfName) d).getName();
            Document doc = rootOutlines != null ? rootOutlines.getDocument() : null;
            if (doc != null) {
                NamedDestinations nd = doc.getNamedDestinations();
                if (nd != null) return nd.get(name);
            }
        }
        return null;
    }

    /**
     * Sets the destination from a GoToAction.
     *
     * @param action the go-to action containing the destination
     */
    public void setDestination(GoToAction action) {
        if (action != null && action.getDestination() != null) {
            setDestination(action.getDestination());
        }
    }

    /**
     * Sets the destination (removes action if set).
     *
     * @param dest the destination
     */
    public void setDestination(ExplicitDestination dest) {
        if (dest != null) {
            dict.set(PdfName.of("Dest"), dest.toPdfArray());
        } else {
            dict.remove(PdfName.of("Dest"));
        }
        dict.remove(PdfName.of("A"));
    }

    /**
     * Sets the destination from any {@link IAppointment} — accepts both
     * {@link ExplicitDestination} (serialized as a {@code /Dest} array) and
     * {@link NamedDestination} (serialized as a {@code /Dest} byte string).
     *
     * @param dest the destination (may be null to clear)
     */
    public void setDestination(IAppointment dest) {
        if (dest instanceof ExplicitDestination) {
            setDestination((ExplicitDestination) dest);
        } else if (dest instanceof NamedDestination) {
            dict.set(PdfName.of("Dest"), ((NamedDestination) dest).toCos());
            dict.remove(PdfName.of("A"));
        } else if (dest == null) {
            dict.remove(PdfName.of("Dest"));
            dict.remove(PdfName.of("A"));
        }
    }

    // ── Page display convenience ──

    /**
     * Returns the nesting depth of this outline item.
     * Top-level items (direct children of the outline root) have level 1,
     * their children have level 2, etc.
     *
     * @return the nesting level (1-based)
     */
    public int getLevel() {
        int level = 1;
        // Malformed outlines can contain /Parent cycles (an item whose parent
        // chain never reaches the /Outlines root) — track visited dicts so a
        // damaged file cannot hang the walk (PDFNET_58236).
        java.util.Set<PdfBase> visited = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        visited.add(dict);
        PdfBase parent = resolve(dict.get("Parent"));
        while (parent instanceof PdfDictionary && visited.add(parent)) {
            PdfDictionary parentDict = (PdfDictionary) parent;
            // If the parent has /Type /Outlines it is the root → stop
            PdfBase type = parentDict.get("Type");
            if (type instanceof PdfName && "Outlines".equals(((PdfName) type).getName())) {
                break;
            }
            // Check if parent is the root outlines dict (no Title = root)
            PdfBase titleCheck = parentDict.get("Title");
            if (titleCheck == null) {
                break;
            }
            level++;
            parent = resolve(parentDict.get("Parent"));
        }
        return level;
    }

    /**
     * Sets the page display destination for this bookmark.
     * This is a convenience alias for {@link #setDestination(ExplicitDestination)}.
     *
     * @param dest the explicit destination
     */
    public void setPageDisplay(ExplicitDestination dest) {
        setDestination(dest);
    }

    /**
     * Returns the page display destination for this bookmark.
     * This is a convenience alias for {@link #getDestination()}.
     *
     * @return the explicit destination, or null
     * @throws IOException if parsing fails
     */
    public ExplicitDestination getPageDisplay() throws IOException {
        return getDestination();
    }

    /**
     * Sets the zoom factor for an XYZ destination.
     * If the current destination is already XYZ, updates only the zoom.
     * Otherwise creates a new XYZ destination targeting the same page with the given zoom.
     *
     * @param zoom the zoom factor (1.0 = 100%)
     * @throws IOException if parsing the current destination fails
     */
    public void setPageDisplay_Zoom(double zoom) throws IOException {
        ExplicitDestination current = getDestination();
        if (current instanceof XYZExplicitDestination) {
            XYZExplicitDestination xyz = (XYZExplicitDestination) current;
            setDestination(new XYZExplicitDestination(
                    xyz.getPage(), xyz.getLeft(), xyz.getTop(), zoom));
        } else if (current != null) {
            setDestination(new XYZExplicitDestination(
                    current.getPage(), Double.NaN, Double.NaN, zoom));
        } else {
            // No destination yet — create one with just a zoom
            setDestination(new XYZExplicitDestination(
                    (Page) null, Double.NaN, Double.NaN, zoom));
        }
    }

    /**
     * Returns the zoom factor of the current XYZ destination, or 0 if not an XYZ destination.
     *
     * @return the zoom factor, or 0
     * @throws IOException if parsing fails
     */
    public double getPageDisplay_Zoom() throws IOException {
        ExplicitDestination dest = getDestination();
        if (dest instanceof XYZExplicitDestination) {
            return ((XYZExplicitDestination) dest).getZoom();
        }
        return 0;
    }

    // ── Sibling navigation (First, Last, Next, Prev) ──

    /**
     * Returns the first child outline item, or null if there are no children.
     *
     * @return the first child, or null
     */
    public OutlineItemCollection getFirst() {
        ensureChildren();
        return children.isEmpty() ? null : children.get(0);
    }

    /**
     * Returns the last child outline item, or null if there are no children.
     *
     * @return the last child, or null
     */
    public OutlineItemCollection getLast() {
        ensureChildren();
        return children.isEmpty() ? null : children.get(children.size() - 1);
    }

    /**
     * Returns the next sibling outline item in the linked list, or null.
     *
     * @return the next sibling, or null
     */
    public OutlineItemCollection getNext() {
        PdfBase next = resolve(dict.get("Next"));
        if (next instanceof PdfDictionary) {
            return new OutlineItemCollection((PdfDictionary) next, rootOutlines, parser);
        }
        return null;
    }

    /**
     * Returns the previous sibling outline item in the linked list, or null.
     *
     * @return the previous sibling, or null
     */
    public OutlineItemCollection getPrev() {
        PdfBase prev = resolve(dict.get("Prev"));
        if (prev instanceof PdfDictionary) {
            return new OutlineItemCollection((PdfDictionary) prev, rootOutlines, parser);
        }
        return null;
    }

    // ── Action ──

    /**
     * Returns the action for this bookmark.
     *
     * @return the action, or null
     * @throws IOException if parsing fails
     */
    public PdfAction getAction() throws IOException {
        PdfBase a = resolve(dict.get("A"));
        if (a instanceof PdfDictionary) {
            Document doc = rootOutlines != null ? rootOutlines.getDocument() : null;
            return PdfAction.fromDictionary((PdfDictionary) a, doc);
        }
        return null;
    }

    /**
     * Sets the action (removes destination if set).
     *
     * @param action the action
     */
    public void setAction(PdfAction action) {
        if (action != null) {
            dict.set(PdfName.of("A"), action.getPdfDictionary());
        } else {
            dict.remove(PdfName.of("A"));
        }
        dict.remove(PdfName.of("Dest"));
    }

    // ── Children ──

    /**
     * Adds a child bookmark.
     *
     * @param child the child item
     */
    public void add(OutlineItemCollection child) {
        ensureChildren();
        children.add(child);
        rebuildChildLinks();
    }

    /**
     * Returns the child at the given 1-based index.
     *
     * @param index the 1-based index
     * @return the child item
     */
    public OutlineItemCollection get(int index) {
        ensureChildren();
        if (index < 1 || index > children.size()) {
            throw new IndexOutOfBoundsException("Index " + index + " out of [1," + children.size() + "]");
        }
        return children.get(index - 1);
    }

    /**
     * Returns the number of direct children.
     *
     * @return the count
     */
    public int getCount() {
        ensureChildren();
        return children.size();
    }

    /**
     * Returns the total visible count (children of open items, recursively).
     *
     * @return the visible count
     */
    public int getVisibleCount() {
        if (!isOpen()) return 0;
        int count = getCount();
        for (OutlineItemCollection child : this) {
            count += child.getVisibleCount();
        }
        return count;
    }

    /**
     * Returns whether this item is open (children visible).
     *
     * @return true if open
     */
    public boolean isOpen() {
        int c = dict.getInt("Count", 0);
        return c > 0;
    }

    /**
     * Sets whether this item is open.
     *
     * @param open true to open
     */
    public void setOpen(boolean open) {
        int c = Math.max(1, Math.abs(dict.getInt("Count", 0)));
        dict.set(PdfName.of("Count"), PdfInteger.valueOf(open ? c : -c));
    }

    /**
     * Removes a child by 1-based index.
     *
     * @param index the 1-based index
     */
    public void delete(int index) {
        ensureChildren();
        children.remove(index - 1);
        rebuildChildLinks();
    }

    /**
     * Removes all child outline items from this bookmark.
     */
    public void delete() {
        ensureChildren();
        children.clear();
        rebuildChildLinks();
    }

    /**
     * Deletes the first child item with the specified title (recursive).
     *
     * @param title the title to search for
     */
    public void delete(String title) {
        ensureChildren();
        for (int i = 0; i < children.size(); i++) {
            if (title.equals(children.get(i).getTitle())) {
                children.remove(i);
                rebuildChildLinks();
                return;
            }
            // Search grandchildren recursively
            children.get(i).delete(title);
        }
    }

    /**
     * Checks whether this item or its descendants contain the specified item.
     *
     * @param item the item to search for
     * @return true if found
     */
    public boolean contains(OutlineItemCollection item) {
        ensureChildren();
        for (OutlineItemCollection child : children) {
            if (child == item) return true;
            if (child.contains(item)) return true;
        }
        return false;
    }

    @Override
    public Iterator<OutlineItemCollection> iterator() {
        ensureChildren();
        return children.iterator();
    }

    /**
     * Returns the underlying PDF dictionary.
     *
     * @return the dictionary
     */
    public PdfDictionary getPdfDictionary() { return dict; }

    // ── Internal ──

    private void ensureChildren() {
        if (children != null) return;
        children = new ArrayList<>();
        PdfBase first = resolve(dict.get("First"));
        PdfBase current = first;
        int guard = 10000;
        while (current instanceof PdfDictionary && guard-- > 0) {
            PdfDictionary childDict = (PdfDictionary) current;
            children.add(new OutlineItemCollection(childDict, rootOutlines, parser));
            current = resolve(childDict.get("Next"));
            if (current == first) break;
        }
    }

    private void rebuildChildLinks() {
        if (children.isEmpty()) {
            dict.remove(PdfName.of("First"));
            dict.remove(PdfName.of("Last"));
            dict.set(PdfName.of("Count"), PdfInteger.valueOf(0));
            return;
        }
        for (int i = 0; i < children.size(); i++) {
            PdfDictionary cd = children.get(i).dict;
            cd.set(PdfName.of("Parent"), this.dict);
            if (i == 0) dict.set(PdfName.of("First"), cd);
            if (i == children.size() - 1) dict.set(PdfName.of("Last"), cd);
            if (i > 0) cd.set(PdfName.of("Prev"), children.get(i - 1).dict);
            else cd.remove(PdfName.of("Prev"));
            if (i < children.size() - 1) cd.set(PdfName.of("Next"), children.get(i + 1).dict);
            else cd.remove(PdfName.of("Next"));
        }
        dict.set(PdfName.of("Count"), PdfInteger.valueOf(children.size()));
    }

    private PdfBase resolve(PdfBase val) {
        if (val instanceof PdfObjectReference) {
            try { return ((PdfObjectReference) val).dereference(); }
            catch (Exception e) { return null; }
        }
        return val;
    }

    private Document getDocument() {
        return rootOutlines != null ? rootOutlines.getDocument() : null;
    }
}
