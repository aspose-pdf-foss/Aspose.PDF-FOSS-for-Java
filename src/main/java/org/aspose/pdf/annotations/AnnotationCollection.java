package org.aspose.pdf.annotations;

import org.aspose.pdf.Page;
import org.aspose.pdf.engine.cos.*;
import org.aspose.pdf.engine.parser.PDFParser;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Collection of annotations on a page (ISO 32000-1:2008, §12.5).
 * Wraps the /Annots array. Uses 1-based indexing.
 */
public class AnnotationCollection implements Iterable<Annotation> {
    private static final Logger LOG = Logger.getLogger(AnnotationCollection.class.getName());
    private final COSArray annotsArray;
    private final Page page;
    private final PDFParser parser;
    private List<Annotation> annotations;

    /**
     * Constructs an annotation collection wrapping the given /Annots COSArray.
     *
     * @param annotsArray the COS array of annotation dictionaries (or references); if null, an empty array is used
     * @param page        the page these annotations belong to
     * @param parser      the PDF parser for resolving indirect references
     */
    public AnnotationCollection(COSArray annotsArray, Page page, PDFParser parser) {
        this.annotsArray = annotsArray != null ? annotsArray : new COSArray();
        this.page = page;
        this.parser = parser;
    }

    /**
     * Returns the annotation at the specified 1-based index.
     *
     * @param index the 1-based index
     * @return the annotation at the given index
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public Annotation get(int index) {
        ensureLoaded();
        if (index < 1 || index > annotations.size())
            throw new IndexOutOfBoundsException("Index " + index + " out of [1," + annotations.size() + "]");
        return annotations.get(index - 1);
    }

    /**
     * Returns the number of annotations in this collection.
     *
     * @return the annotation count
     */
    public int getCount() { ensureLoaded(); return annotations.size(); }

    /**
     * Returns the number of annotations in this collection.
     *
     * @return the annotation count
     */
    public int size() { return getCount(); }

    /**
     * Adds an annotation to this collection and the underlying COS array.
     *
     * @param annotation the annotation to add
     */
    public void add(Annotation annotation) {
        // Do NOT force ensureLoaded() here: each Page.getAnnotations() hands back a
        // fresh, unloaded wrapper, so loading the whole /Annots array on every add()
        // makes a sequence of N adds cost O(N^2) (e.g. HTML→PDF with thousands of
        // <a href> links — see PDFNET_40534). Appending to the COS array is enough;
        // a later ensureLoaded() rebuilds the list from the array, new entry included.
        if (annotations != null) {
            annotations.add(annotation);
        }
        annotsArray.add(annotation.getCOSDictionary());
    }

    /**
     * Removes the annotation at the specified 1-based index.
     *
     * @param index the 1-based index of the annotation to remove
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public void delete(int index) {
        ensureLoaded();
        if (index < 1 || index > annotations.size())
            throw new IndexOutOfBoundsException("Index " + index + " out of [1," + annotations.size() + "]");
        Annotation removed = annotations.remove(index - 1);
        // Remove matching dict from COSArray
        for (int i = 0; i < annotsArray.size(); i++) {
            COSBase item = resolveRef(annotsArray.get(i));
            if (item == removed.getCOSDictionary()) { annotsArray.remove(i); break; }
        }
    }

    /**
     * Removes the specified annotation from this collection.
     *
     * @param annotation the annotation to remove
     */
    public void delete(Annotation annotation) {
        ensureLoaded();
        annotations.remove(annotation);
        for (int i = 0; i < annotsArray.size(); i++) {
            COSBase item = resolveRef(annotsArray.get(i));
            if (item == annotation.getCOSDictionary()) { annotsArray.remove(i); break; }
        }
    }

    /**
     * Returns an iterator over the annotations in this collection.
     *
     * @return an iterator
     */
    @Override
    public Iterator<Annotation> iterator() { ensureLoaded(); return annotations.iterator(); }

    private void ensureLoaded() {
        if (annotations != null) return;
        annotations = new ArrayList<>();
        for (int i = 0; i < annotsArray.size(); i++) {
            COSBase item = resolveRef(annotsArray.get(i));
            if (item instanceof COSDictionary) {
                annotations.add(Annotation.fromDictionary((COSDictionary) item, page));
            }
        }
    }

    private COSBase resolveRef(COSBase val) {
        if (val instanceof COSObjectReference) {
            try { return ((COSObjectReference) val).dereference(); }
            catch (Exception e) { return null; }
        }
        return val;
    }
}
