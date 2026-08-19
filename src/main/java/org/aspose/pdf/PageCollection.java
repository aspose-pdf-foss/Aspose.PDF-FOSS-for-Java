package org.aspose.pdf;

import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectKey;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.parser.PDFParser;
import org.aspose.pdf.text.TextAbsorber;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Represents the collection of pages in a PDF document (ISO 32000-1:2008, §7.7.3.2).
 * <p>
 * Wraps the /Pages root dictionary and flattens the page tree into a linear list.
 * Intermediate /Pages nodes are recursed, and /Page leaf nodes become {@link Page} objects.
 * Uses 1-based indexing: {@code get(1)} returns the first page.
 * </p>
 */
public class PageCollection implements Iterable<Page> {

    private static final Logger LOG = Logger.getLogger(PageCollection.class.getName());

    private final PdfDictionary pagesDict;
    private final PDFParser parser;
    private Document owningDocument;
    // volatile so threads that read flatPages outside the synchronized block see
    // a fully-populated list (or null) — never the half-built intermediate state
    // that the legacy lazy-init pattern exposed during concurrent first access.
    private volatile List<Page> flatPages;
    private final java.util.Map<PdfDictionary, Page> pageCache = new java.util.IdentityHashMap<>();
    /** One importer per foreign source document — keeps shared resources deduplicated across successive page imports. */
    private final java.util.Map<Document, DocumentPageImporter> importers = new java.util.IdentityHashMap<>();
    private boolean treeRepairAttempted;
    private boolean treeRepaired;

    /**
     * Creates a PageCollection wrapping the /Pages root dictionary.
     *
     * @param pagesDict the /Pages dictionary (must have /Type /Pages)
     * @param parser    the PDF parser for resolving indirect references, may be null
     * @throws IllegalArgumentException if pagesDict is null
     */
    public PageCollection(PdfDictionary pagesDict, PDFParser parser) {
        if (pagesDict == null) {
            throw new IllegalArgumentException("Pages dictionary must not be null");
        }
        this.pagesDict = pagesDict;
        this.parser = parser;
        LOG.fine(() -> "PageCollection created");
    }

    /** Sets the document that owns this collection (used for cross-doc detection in add/insert). */
    void setOwningDocument(Document owningDocument) {
        this.owningDocument = owningDocument;
    }

    /**
     * The page-tree root dictionary this collection is backed by. May be a
     * synthetic in-memory root (no object key) when the source catalog's
     * {@code /Pages} was broken and the tree was recovered by scanning.
     */
    PdfDictionary getPagesDictionary() {
        return pagesDict;
    }

    /**
     * Re-runs the flatten pass and refreshes {@code page.setNumber(...)} so the
     * Page's stored 1-based index reflects the current document order.
     * Called from {@link Page#getNumber()} so that callers always see an
     * up-to-date page number after insert/delete/clear mutations.
     * Package-private — internal hook only.
     */
    void refreshNumber(Page page) {
        ensureFlattened();
        // ensureFlattened sets number on every flatPage during build; nothing
        // more to do here unless a page has been detached and re-attached
        // without going through add/insert/delete (which is not supported).
    }

    /**
     * Returns the page at the given 1-based index.
     *
     * @param index the 1-based page index
     * @return the Page at the given index
     * @throws IndexOutOfBoundsException if index is out of range [1, size()]
     */
    public Page get(int index) {
        ensureFlattened();
        if (index < 1 || index > flatPages.size()) {
            throw new IndexOutOfBoundsException(
                    "Page index " + index + " out of range [1, " + flatPages.size() + "]");
        }
        return flatPages.get(index - 1);
    }

    /**
     * Returns the number of pages.
     *
     * @return the page count
     */
    public int getCount() {
        ensureFlattened();
        return flatPages.size();
    }

    /**
     * Returns the number of pages (alias for {@link #getCount()}).
     *
     * @return the page count
     */
    public int size() {
        return getCount();
    }

    /**
     * Returns an iterator over all pages.
     *
     * @return the page iterator
     */
    @Override
    public Iterator<Page> iterator() {
        ensureFlattened();
        return flatPages.iterator();
    }

    /**
     * Accepts a text absorber to extract text from all pages in this collection.
     *
     * @param absorber the text absorber
     * @throws IOException if text extraction fails
     */
    public void accept(TextAbsorber absorber) throws IOException {
        if (absorber == null) {
            throw new IllegalArgumentException("Absorber must not be null");
        }
        for (Page page : this) {
            absorber.visit(page);
        }
    }

    /**
     * Accepts an image placement absorber to find images on all pages.
     *
     * @param absorber the image placement absorber
     * @throws IOException if processing fails
     */
    public void accept(ImagePlacementAbsorber absorber) throws IOException {
        if (absorber == null) {
            throw new IllegalArgumentException("Absorber must not be null");
        }
        for (Page page : this) {
            absorber.visit(page);
        }
    }

    /**
     * Creates a new page with standard A4 page bounds [0 0 595 842], adds it to the
     * end of this collection, and returns it.
     *
     * @return the newly created Page
     */
    public Page add() {
        PdfDictionary pageDict = new PdfDictionary();
        pageDict.set(PdfName.TYPE, PdfName.PAGE);
        pageDict.set(PdfName.MEDIABOX, new Rectangle(0, 0, 595, 842).toPdfArray());
        pageDict.set(PdfName.PARENT, pagesDict);
        Page page = new Page(pageDict, parser);
        page.setOwningDocument(owningDocument);
        pageCache.put(pageDict, page);
        addToKids(pageDict, getKidsArray().size());
        invalidateCache();
        LOG.fine(() -> "Added new default page, count=" + getCount());
        return page;
    }

    /**
     * Adds an existing page to the end of this collection.
     *
     * @param page the page to add
     * @throws IllegalArgumentException if page is null
     */
    public void add(Page page) {
        if (page == null) {
            throw new IllegalArgumentException("Page must not be null");
        }
        Page attached = importIfForeign(page);
        PdfDictionary pageDict = attached.getPdfDictionary();
        pageDict.set(PdfName.PARENT, pagesDict);
        pageCache.put(pageDict, attached);
        addToKids(pageDict, getKidsArray().size());
        invalidateCache();
        LOG.fine(() -> "Added existing page, count=" + getCount());
    }

    /**
     * If {@code page} belongs to a different document, deep-copies it into the
     * owning document and returns the new Page. Same-document pages are returned
     * as-is — preserving the pre-existing reparent-in-place semantics.
     */
    private Page importIfForeign(Page page) {
        if (owningDocument == null) return page;
        Document foreign = page.getOwningDocument();
        if (foreign == null || foreign == owningDocument) return page;
        try {
            DocumentPageImporter imp = importers.computeIfAbsent(foreign,
                    src -> new DocumentPageImporter(owningDocument, src));
            return imp.importPage(page);
        } catch (IOException e) {
            throw new RuntimeException("Failed to import page from foreign document", e);
        }
    }

    /**
     * Adds all pages from another PageCollection to the end of this collection.
     * Equivalent to Aspose's Pages.Add(doc2.Pages).
     *
     * @param otherPages the page collection to add
     * @throws IllegalArgumentException if otherPages is null
     */
    public void add(PageCollection otherPages) {
        if (otherPages == null) {
            throw new IllegalArgumentException("PageCollection must not be null");
        }
        java.util.Map<PdfDictionary, Integer> importedPages = new java.util.IdentityHashMap<>();
        for (Page page : otherPages) {
            int targetIndexBefore = size();
            add(page);
            importedPages.put(page.getPdfDictionary(), targetIndexBefore + 1);
        }
        if (owningDocument != null && !importedPages.isEmpty()) {
            Document foreignDocument = null;
            for (Page sourcePage : otherPages) {
                foreignDocument = sourcePage.getOwningDocument();
                if (foreignDocument != null) {
                    break;
                }
            }
            if (foreignDocument != null && foreignDocument != owningDocument) {
                try {
                    // Map every widget annotation that sits on an imported source
                    // page to that page's target index. A widget's /P entry is
                    // OPTIONAL (ISO 32000 §12.5.2): a field's page is otherwise only
                    // known from the page's /Annots membership. Without this, fields
                    // whose widgets omit /P could not be re-targeted and were dropped
                    // (39201: only 191 of 1362 fields had /P).
                    java.util.Map<PdfDictionary, Integer> widgetPages =
                            buildWidgetPageMap(importedPages);
                    org.aspose.pdf.forms.Form targetForm = owningDocument.getForm();
                    for (org.aspose.pdf.forms.Field field : foreignDocument.getForm()) {
                        Integer targetPageNumber =
                                resolveImportedFieldPageNumber(field, importedPages, widgetPages);
                        if (targetPageNumber != null && !targetForm.hasField(field.getFullName())) {
                            targetForm.add(field, field.getFullName(), targetPageNumber);
                        }
                    }
                    // After the merge, drop dangling widget /Parent links across the
                    // whole document. A widget whose /Parent field is not part of the
                    // /AcroForm /Fields tree (e.g. the base document's own widgets that
                    // were parented to a junk grouping field with no AcroForm — corpus
                    // 36395) keeps its own /T and becomes a clean self-named field
                    // instead of carrying the malformed parent name into the output.
                    dropDanglingWidgetParents(owningDocument);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to import form fields from foreign document", e);
                }
            }
        }
    }

    /**
     * Inserts a new blank page (A4) at the given 1-based index and returns it.
     *
     * @param index the 1-based index at which to insert the page
     * @return the newly created Page
     * @throws IndexOutOfBoundsException if index is out of range [1, size()+1]
     */
    public Page insert(int index) {
        ensureFlattened();
        int currentSize = flatPages.size();
        if (index < 1 || index > currentSize + 1) {
            throw new IndexOutOfBoundsException(
                    "Insert index " + index + " out of range [1, " + (currentSize + 1) + "]");
        }
        PdfDictionary pageDict = new PdfDictionary();
        pageDict.set(PdfName.TYPE, PdfName.PAGE);
        pageDict.set(PdfName.MEDIABOX, new Rectangle(0, 0, 595, 842).toPdfArray());
        Page page = new Page(pageDict, parser);
        page.setOwningDocument(owningDocument);
        insertIntoTree(index, pageDict);
        invalidateCache();
        // Cache the newly-built Page wrapper so any subsequent get(N) returns
        // the same instance and any paragraphs / pageInfo mutations the caller
        // makes through the returned reference survive into save (PDFNEWNET-47713).
        pageCache.put(pageDict, page);
        return page;
    }

    /**
     * Inserts a page at the given 1-based index.
     *
     * @param index the 1-based index at which to insert the page
     * @param page  the page to insert
     * @throws IllegalArgumentException  if page is null
     * @throws IndexOutOfBoundsException if index is out of range [1, size()+1]
     */
    public void insert(int index, Page page) {
        if (page == null) {
            throw new IllegalArgumentException("Page must not be null");
        }
        ensureFlattened();
        int currentSize = flatPages.size();
        if (index < 1 || index > currentSize + 1) {
            throw new IndexOutOfBoundsException(
                    "Insert index " + index + " out of range [1, " + (currentSize + 1) + "]");
        }
        Page attached = importIfForeign(page);
        PdfDictionary pageDict = attached.getPdfDictionary();
        pageCache.put(pageDict, attached);
        insertIntoTree(index, pageDict);
        invalidateCache();
        LOG.fine(() -> "Inserted page at index " + index + ", count=" + getCount());
    }

    /**
     * Removes all pages from the document. After this call, {@link #getCount()}
     * returns 0; new pages can be added via {@link #add()} / {@link #add(Page)}
     * exactly as for an empty document.
     */
    public void clear() {
        ensureFlattened();
        // Reset the root /Kids and /Count entries on the pagesDict directly —
        // delete()-in-a-loop would walk inheritance chains for each call and
        // duplicate work for documents with nested page tree nodes.
        PdfArray kids = new PdfArray();
        pagesDict.set(PdfName.KIDS, kids);
        updateCount(0);
        invalidateCache();
        LOG.fine("Cleared all pages");
    }

    /**
     * Removes the pages at the given 1-based indexes (Aspose.PDF
     * {@code PageCollection.Delete(int[])}). Indexes may come in any order —
     * they are deleted highest-first so earlier removals do not shift the
     * remaining targets.
     *
     * @param pages the 1-based page indexes to remove; null/empty is a no-op
     * @throws IndexOutOfBoundsException if any index is out of range
     */
    public void delete(int[] pages) {
        if (pages == null || pages.length == 0) {
            return;
        }
        int[] sorted = pages.clone();
        java.util.Arrays.sort(sorted);
        for (int i = sorted.length - 1; i >= 0; i--) {
            delete(sorted[i]);
        }
    }

    /**
     * Removes the page at the given 1-based index.
     *
     * @param index the 1-based index of the page to remove
     * @throws IndexOutOfBoundsException if index is out of range [1, size()]
     */
    public void delete(int index) {
        ensureFlattened();
        if (index < 1 || index > flatPages.size()) {
            throw new IndexOutOfBoundsException(
                    "Delete index " + index + " out of range [1, " + flatPages.size() + "]");
        }
        Page page = flatPages.get(index - 1);
        PdfDictionary pageDict = page.getPdfDictionary();
        PdfDictionary parent = (PdfDictionary) resolveRef(pageDict.get(PdfName.PARENT));
        if (parent == null || !removeFromParent(parent, pageDict)) {
            PdfArray kids = getKidsArray();
            int fallbackIndex = Math.min(index - 1, kids.size() - 1);
            if (fallbackIndex < 0) {
                throw new IndexOutOfBoundsException(
                        "Delete index " + index + " out of range [1, " + flatPages.size() + "]");
            }
            kids.remove(fallbackIndex);
            updateCount(kids.size());
        }
        invalidateCache();
        LOG.fine(() -> "Deleted page at index " + index + ", count=" + getCount());
    }

    private boolean removeFromParent(PdfDictionary parent, PdfDictionary child) {
        PdfBase kidsValue = resolveRef(parent.get(PdfName.KIDS));
        if (!(kidsValue instanceof PdfArray)) {
            return false;
        }
        PdfArray kids = (PdfArray) kidsValue;
        for (int i = 0; i < kids.size(); i++) {
            PdfBase candidate = resolveRef(kids.get(i));
            if (candidate == child) {
                kids.remove(i);
                decrementCountsUpward(parent);
                pruneEmptyPagesNode(parent);
                return true;
            }
        }
        return false;
    }

    private void decrementCountsUpward(PdfDictionary node) {
        PdfDictionary current = node;
        while (current != null) {
            int currentCount = current.getInt("Count", 0);
            current.set(PdfName.COUNT, PdfInteger.valueOf(Math.max(0, currentCount - 1)));
            PdfBase parent = resolveRef(current.get(PdfName.PARENT));
            current = parent instanceof PdfDictionary ? (PdfDictionary) parent : null;
        }
    }

    private void incrementCountsUpward(PdfDictionary node) {
        PdfDictionary current = node;
        while (current != null) {
            int currentCount = current.getInt("Count", 0);
            current.set(PdfName.COUNT, PdfInteger.valueOf(currentCount + 1));
            PdfBase parent = resolveRef(current.get(PdfName.PARENT));
            current = parent instanceof PdfDictionary ? (PdfDictionary) parent : null;
        }
    }

    private void pruneEmptyPagesNode(PdfDictionary node) {
        PdfDictionary current = node;
        while (current != null && current != pagesDict) {
            PdfBase kidsValue = resolveRef(current.get(PdfName.KIDS));
            if (!(kidsValue instanceof PdfArray) || ((PdfArray) kidsValue).size() > 0) {
                return;
            }
            PdfDictionary parent = (PdfDictionary) resolveRef(current.get(PdfName.PARENT));
            if (parent == null) {
                return;
            }
            PdfBase parentKidsValue = resolveRef(parent.get(PdfName.KIDS));
            if (parentKidsValue instanceof PdfArray) {
                PdfArray parentKids = (PdfArray) parentKidsValue;
                for (int i = 0; i < parentKids.size(); i++) {
                    if (resolveRef(parentKids.get(i)) == current) {
                        parentKids.remove(i);
                        break;
                    }
                }
            }
            current = parent;
        }
    }

    private Integer resolveImportedFieldPageNumber(org.aspose.pdf.forms.Field field,
                                                   java.util.Map<PdfDictionary, Integer> importedPages,
                                                   java.util.Map<PdfDictionary, Integer> widgetPages) {
        Page fieldPage = field.getPage();
        if (fieldPage != null) {
            Integer directMatch = importedPages.get(fieldPage.getPdfDictionary());
            if (directMatch != null) {
                return directMatch;
            }
        }
        PdfDictionary fieldDict = field.getPdfDictionary();
        PdfBase pageRef = resolveRef(fieldDict.get(PdfName.of("P")));
        if (pageRef instanceof PdfDictionary) {
            Integer match = importedPages.get((PdfDictionary) pageRef);
            if (match != null) {
                return match;
            }
        }
        // Resolve by /Annots membership: the field dict itself (a merged
        // field+widget) or any of its widget kids may be an annotation on an
        // imported page even when no /P entry is present.
        Integer byWidget = widgetPages.get(fieldDict);
        if (byWidget != null) {
            return byWidget;
        }
        PdfBase kidsRef = resolveRef(fieldDict.get(PdfName.of("Kids")));
        if (kidsRef instanceof PdfArray) {
            PdfArray kids = (PdfArray) kidsRef;
            for (int i = 0; i < kids.size(); i++) {
                PdfBase kidRef = resolveRef(kids.get(i));
                if (kidRef instanceof PdfDictionary) {
                    PdfDictionary kidDict = (PdfDictionary) kidRef;
                    Integer kidByWidget = widgetPages.get(kidDict);
                    if (kidByWidget != null) {
                        return kidByWidget;
                    }
                    PdfBase kidPageRef = resolveRef(kidDict.get(PdfName.of("P")));
                    if (kidPageRef instanceof PdfDictionary) {
                        Integer match = importedPages.get((PdfDictionary) kidPageRef);
                        if (match != null) {
                            return match;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Drops widget {@code /Parent} links that point to a field outside the
     * document's {@code /AcroForm /Fields} tree. Such a parent is dangling (a
     * source with no AcroForm, or a junk grouping field) and would otherwise
     * surface a malformed field name. Only widgets that carry their own {@code /T}
     * are detached, so their name is preserved; widgets without {@code /T} (e.g.
     * radio kids, whose parent is a real form field anyway) are left untouched.
     */
    private void dropDanglingWidgetParents(Document doc) {
        if (doc == null) {
            return;
        }
        try {
            java.util.Set<PdfDictionary> validFields =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            PdfDictionary catalog = doc.getCatalog();
            if (catalog != null) {
                PdfBase af = resolveRef(catalog.get(PdfName.of("AcroForm")));
                if (af instanceof PdfDictionary) {
                    PdfBase fields = resolveRef(((PdfDictionary) af).get(PdfName.of("Fields")));
                    if (fields instanceof PdfArray) {
                        collectFieldDicts((PdfArray) fields, validFields, 0);
                    }
                }
            }
            PageCollection pages = doc.getPages();
            for (int i = 1; i <= pages.getCount(); i++) {
                PdfBase annots = resolveRef(pages.get(i).getPdfDictionary().get(PdfName.ANNOTS));
                if (!(annots instanceof PdfArray)) {
                    continue;
                }
                PdfArray arr = (PdfArray) annots;
                for (int j = 0; j < arr.size(); j++) {
                    PdfBase ad = resolveRef(arr.get(j));
                    if (!(ad instanceof PdfDictionary)) {
                        continue;
                    }
                    PdfDictionary annot = (PdfDictionary) ad;
                    if (annot.get(PdfName.of("T")) == null) {
                        continue;
                    }
                    PdfBase parent = resolveRef(annot.get(PdfName.of("Parent")));
                    if (parent instanceof PdfDictionary && !validFields.contains(parent)) {
                        annot.remove(PdfName.of("Parent"));
                    }
                }
            }
        } catch (Exception e) {
            // best-effort normalization; never fail the merge over it
        }
    }

    private void collectFieldDicts(PdfArray arr, java.util.Set<PdfDictionary> out, int depth) {
        if (depth > 50) {
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            PdfBase v = resolveRef(arr.get(i));
            if (v instanceof PdfDictionary && out.add((PdfDictionary) v)) {
                PdfBase kids = resolveRef(((PdfDictionary) v).get(PdfName.of("Kids")));
                if (kids instanceof PdfArray) {
                    collectFieldDicts((PdfArray) kids, out, depth + 1);
                }
            }
        }
    }

    /**
     * Builds an identity map from every widget-annotation dictionary that sits on
     * an imported source page to that page's 1-based target index, by scanning
     * each source page's {@code /Annots}. Used to resolve a form field's target
     * page when its widget annotations carry no {@code /P} entry.
     */
    private java.util.Map<PdfDictionary, Integer> buildWidgetPageMap(
            java.util.Map<PdfDictionary, Integer> importedPages) {
        java.util.Map<PdfDictionary, Integer> map = new java.util.IdentityHashMap<>();
        for (java.util.Map.Entry<PdfDictionary, Integer> entry : importedPages.entrySet()) {
            PdfBase annots = resolveRef(entry.getKey().get(PdfName.of("Annots")));
            if (!(annots instanceof PdfArray)) {
                continue;
            }
            PdfArray arr = (PdfArray) annots;
            for (int i = 0; i < arr.size(); i++) {
                PdfBase annot = resolveRef(arr.get(i));
                if (annot instanceof PdfDictionary) {
                    map.put((PdfDictionary) annot, entry.getValue());
                }
            }
        }
        return map;
    }

    /**
     * Returns the /Kids array, creating it if absent.
     */
    private PdfArray getKidsArray() {
        PdfBase kidsValue = pagesDict.get(PdfName.KIDS);
        kidsValue = resolveRef(kidsValue);
        if (kidsValue instanceof PdfArray) {
            return (PdfArray) kidsValue;
        }
        PdfArray kids = new PdfArray();
        pagesDict.set(PdfName.KIDS, kids);
        return kids;
    }

    /**
     * Adds a page dictionary to /Kids at the specified 0-based position and updates /Count.
     */
    private void addToKids(PdfDictionary pageDict, int position) {
        PdfArray kids = getKidsArray();
        kids.add(position, pageDict);
        updateCount(kids.size());
    }

    private void insertIntoTree(int index, PdfDictionary pageDict) {
        if (flatPages == null) {
            ensureFlattened();
        }
        if (flatPages.isEmpty() || index == flatPages.size() + 1) {
            pageDict.set(PdfName.PARENT, pagesDict);
            addToKids(pageDict, getKidsArray().size());
            return;
        }

        Page targetPage = flatPages.get(index - 1);
        PdfDictionary targetPageDict = targetPage.getPdfDictionary();
        PdfDictionary parent = (PdfDictionary) resolveRef(targetPageDict.get(PdfName.PARENT));
        if (parent == null) {
            pageDict.set(PdfName.PARENT, pagesDict);
            addToKids(pageDict, getKidsArray().size());
            return;
        }
        PdfBase kidsValue = resolveRef(parent.get(PdfName.KIDS));
        if (!(kidsValue instanceof PdfArray)) {
            pageDict.set(PdfName.PARENT, pagesDict);
            addToKids(pageDict, getKidsArray().size());
            return;
        }
        PdfArray kids = (PdfArray) kidsValue;
        int childPosition = -1;
        for (int i = 0; i < kids.size(); i++) {
            if (resolveRef(kids.get(i)) == targetPageDict) {
                childPosition = i;
                break;
            }
        }
        if (childPosition < 0) {
            pageDict.set(PdfName.PARENT, pagesDict);
            addToKids(pageDict, getKidsArray().size());
            return;
        }
        pageDict.set(PdfName.PARENT, parent);
        kids.add(childPosition, pageDict);
        incrementCountsUpward(parent);
    }

    /**
     * Updates the /Count entry in the pages dictionary.
     */
    private void updateCount(int count) {
        pagesDict.set(PdfName.COUNT, PdfInteger.valueOf(count));
    }

    /**
     * Invalidates the cached flat page list so it will be rebuilt on next access.
     */
    private synchronized void invalidateCache() {
        flatPages = null;
    }

    /**
     * Lazily flattens the page tree on first access. Thread-safe: readers can
     * call this concurrently; the first thread builds the list, the rest block
     * on the monitor and then return immediately once the volatile
     * {@link #flatPages} is published. We deliberately do <em>not</em> use a
     * lock-free fast-path read here, because {@link #flattenNode} writes into
     * {@code flatPages} as it goes — a reader could otherwise observe a
     * partially-built list. The synchronized block both serializes builders
     * and guarantees the happens-before edge readers need.
     */
    private synchronized void ensureFlattened() {
        if (flatPages != null) {
            return;
        }
        flatPages = new ArrayList<>();
        flattenNode(pagesDict, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        if (parser != null && !treeRepairAttempted && getDeclaredPageCount() > flatPages.size()) {
            try {
                repairBrokenTreeIfNeeded();
            } catch (IOException e) {
                LOG.warning("Failed to repair malformed page tree during flatten: " + e.getMessage());
            }
        }
        // Assign 1-based page numbers
        for (int i = 0; i < flatPages.size(); i++) {
            flatPages.get(i).setNumber(i + 1);
        }
        LOG.fine(() -> "Page tree flattened: " + flatPages.size() + " pages");
    }

    /**
     * Repairs a malformed page tree when /Count declares more pages than are
     * reachable through /Kids. Recovery rebuilds the root /Kids array from
     * page-like objects in object-number order so save/reopen roundtrips remain
     * stable for damaged inputs.
     *
     * @throws IOException if parser-backed objects cannot be read
     */
    void repairBrokenTreeIfNeeded() throws IOException {
        if (parser == null || treeRepairAttempted) {
            return;
        }
        treeRepairAttempted = true;
        ensureFlattened();

        int declaredCount = getDeclaredPageCount();
        if (declaredCount <= flatPages.size()) {
            return;
        }

        List<PdfDictionary> currentPages = new ArrayList<>();
        java.util.Set<PdfDictionary> seenPages = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        // The root /Pages dict must never appear as one of its own kids — guard
        // against malformed PDFs that mislabel the root with /Type /Page.
        seenPages.add(pagesDict);
        for (Page page : flatPages) {
            PdfDictionary pageDict = page.getPdfDictionary();
            if (pageDict != null && seenPages.add(pageDict)) {
                currentPages.add(pageDict);
            }
        }

        TreeMap<Integer, PdfDictionary> recoveredPageObjects = new TreeMap<>();
        for (PdfObjectKey key : parser.getAllObjectKeys()) {
            PdfBase obj = parser.getObject(key);
            if (!(obj instanceof PdfDictionary)) {
                continue;
            }
            PdfDictionary dict = (PdfDictionary) obj;
            if (!looksLikePageCandidate(dict)) {
                continue;
            }
            recoveredPageObjects.putIfAbsent(key.getObjectNumber(), dict);
        }

        if (currentPages.size() + recoveredPageObjects.size() <= flatPages.size()) {
            LOG.warning(() -> "Page tree declares " + declaredCount
                    + " pages but only " + flatPages.size()
                    + " page-like objects were recoverable");
            return;
        }

        List<PdfDictionary> repairedPages = new ArrayList<>();
        for (PdfDictionary pageDict : currentPages) {
            repairedPages.add(pageDict);
            if (repairedPages.size() >= declaredCount) {
                break;
            }
        }
        for (Map.Entry<Integer, PdfDictionary> entry : recoveredPageObjects.entrySet()) {
            PdfDictionary pageDict = entry.getValue();
            if (!seenPages.add(pageDict)) {
                continue;
            }
            repairedPages.add(pageDict);
            if (repairedPages.size() >= declaredCount) {
                break;
            }
        }
        if (repairedPages.size() <= flatPages.size()) {
            return;
        }

        PdfArray repairedKids = new PdfArray();
        for (PdfDictionary pageDict : repairedPages) {
            pageDict.set(PdfName.PARENT, pagesDict);
            repairedKids.add(pageDict);
        }
        pagesDict.set(PdfName.TYPE, PdfName.PAGES);
        pagesDict.set(PdfName.KIDS, repairedKids);
        updateCount(repairedPages.size());
        treeRepaired = true;
        invalidateCache();
        ensureFlattened();
        LOG.warning(() -> "Recovered malformed page tree: declared=" + declaredCount
                + ", rebuilt root kids with " + repairedPages.size() + " pages");
    }

    boolean wasTreeRepaired() {
        return treeRepaired;
    }

    /**
     * Recursively flattens a page tree node.
     * /Type /Pages nodes have their /Kids recursed.
     * /Type /Page nodes are added as leaf pages.
     * <p>The {@code ancestors} set is treated as a traversal stack — a node is
     * added before descending and removed on the way out — so cycles
     * (node referencing one of its own ancestors) are caught while the same
     * dictionary appearing twice at different paths is still flattened twice.</p>
     *
     * @param node the page tree node dictionary
     * @param ancestors identity set of /Pages ancestors currently on the stack
     */
    private void flattenNode(PdfDictionary node, java.util.Set<PdfDictionary> ancestors) {
        if (ancestors.contains(node)) {
            LOG.warning(() -> "Cyclic /Pages tree detected; skipping repeated node");
            return;
        }
        String type = node.getType();
        // A leaf /Page wins over a /Pages container — some malformed PDFs leave
        // a stale /Kids on what is really a page leaf, and recursing into it
        // would lose the page (or trigger a cycle further down). The /Type
        // /Page tag and the page-content signature are both authoritative.
        if ("Page".equals(type) || (!"Pages".equals(type) && looksLikePageCandidate(node))) {
            flatPages.add(pageCache.computeIfAbsent(node, n -> {
                Page p = new Page(n, parser);
                p.setOwningDocument(owningDocument);
                return p;
            }));
            return;
        }
        boolean looksLikePagesNode = "Pages".equals(type)
                || (type == null && node.get(PdfName.KIDS) != null);
        if (looksLikePagesNode) {
            PdfBase kidsValue = node.get(PdfName.KIDS);
            kidsValue = resolveRef(kidsValue);
            if (!(kidsValue instanceof PdfArray)) {
                if (looksLikePageCandidate(node)) {
                    flatPages.add(pageCache.computeIfAbsent(node, n -> {
                        Page p = new Page(n, parser);
                        p.setOwningDocument(owningDocument);
                        return p;
                    }));
                    LOG.warning(() -> "Treating malformed /Pages node without /Kids as a leaf page");
                    return;
                }
                LOG.warning(() -> "/Pages node missing /Kids array");
                return;
            }
            ancestors.add(node);
            try {
                PdfArray kids = (PdfArray) kidsValue;
                for (int i = 0; i < kids.size(); i++) {
                    PdfBase child = kids.get(i);
                    child = resolveRef(child);
                    if (child instanceof PdfDictionary) {
                        flattenNode((PdfDictionary) child, ancestors);
                    } else {
                        LOG.warning(() -> "Unexpected non-dictionary child in /Kids");
                    }
                }
            } finally {
                ancestors.remove(node);
            }
        } else if ("Page".equals(type)) {
            flatPages.add(pageCache.computeIfAbsent(node, n -> {
                Page p = new Page(n, parser);
                p.setOwningDocument(owningDocument);
                return p;
            }));
        } else {
            // Treat as a page if it looks like one (has /MediaBox but no /Type)
            if (node.containsKey("MediaBox") || node.containsKey("Parent")) {
                flatPages.add(pageCache.computeIfAbsent(node, n -> {
                Page p = new Page(n, parser);
                p.setOwningDocument(owningDocument);
                return p;
            }));
            } else {
                LOG.fine(() -> "Skipping unknown node type: " + type);
            }
        }
    }

    private int getDeclaredPageCount() {
        PdfBase count = resolveRef(pagesDict.get(PdfName.COUNT));
        if (count instanceof PdfInteger) {
            return ((PdfInteger) count).intValue();
        }
        return -1;
    }

    private boolean looksLikePageCandidate(PdfDictionary node) {
        if ("Page".equals(node.getType())) {
            return true;
        }
        if (node.get(PdfName.MEDIABOX) != null
                && node.get(PdfName.CONTENTS) != null
                && node.get(PdfName.RESOURCES) != null) {
            return true;
        }
        // Some malformed PDFs incorrectly mark a leaf page node as /Pages but
        // still store page-like content directly on the node with inherited
        // MediaBox. Accept those nodes so page-tree recovery can proceed.
        return node.get(PdfName.PARENT) != null
                && node.get(PdfName.CONTENTS) != null
                && node.get(PdfName.RESOURCES) != null;
    }

    /**
     * Resolves an indirect object reference.
     *
     * @param value the PDF value
     * @return the resolved value, or null
     */
    private PdfBase resolveRef(PdfBase value) {
        if (value == null) {
            return null;
        }
        if (value instanceof PdfObjectReference) {
            try {
                return ((PdfObjectReference) value).dereference();
            } catch (IOException e) {
                LOG.warning(() -> "Failed to dereference: " + e.getMessage());
                return null;
            }
        }
        return value;
    }
}
