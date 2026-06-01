package org.aspose.pdf.facades;

import org.aspose.pdf.*;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.annotations.AnnotationCollection;
import org.aspose.pdf.annotations.AnnotationType;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides methods for managing annotations in a PDF document:
 * flattening, deleting, and counting annotations.
 */
public class PdfAnnotationEditor {

    private static final Logger LOG = Logger.getLogger(PdfAnnotationEditor.class.getName());

    private Document document;

    /**
     * Creates a new {@code PdfAnnotationEditor} instance.
     */
    public PdfAnnotationEditor() {
    }

    /**
     * Creates a new {@code PdfAnnotationEditor} bound to the specified document.
     *
     * @param document the document to bind
     */
    public PdfAnnotationEditor(Document document) {
        this.document = document;
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
     * Flattens all annotations in the document: each annotation that carries a
     * normal appearance stream ({@code /AP /N}) has that appearance burned into
     * the page content stream — wrapped in a {@code q <CTM> cm ... Q} block
     * whose CTM scales the appearance {@code /BBox} onto the annotation
     * {@code /Rect} (ISO 32000-1:2008 §12.5.5) — and is then removed from the
     * page {@code /Annots} array.
     *
     * <p>Previously this only deleted annotations without burning their
     * appearance (Sprint 21 / BUG-F4 fix: delegate to
     * {@link Page#flattenAnnotations()}).</p>
     *
     * @return {@code true} on success
     */
    public boolean flattenAnnotations() {
        try {
            PageCollection pages = document.getPages();
            for (int i = 1; i <= pages.getCount(); i++) {
                pages.get(i).flattenAnnotations();
            }
            LOG.fine("Flattened all annotations in the document");
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to flatten annotations", e);
            return false;
        }
    }

    /**
     * Deletes all annotations from all pages in the document.
     *
     * @return {@code true} on success
     */
    public boolean deleteAnnotations() {
        try {
            PageCollection pages = document.getPages();
            for (int i = 1; i <= pages.getCount(); i++) {
                Page page = pages.get(i);
                AnnotationCollection annots = page.getAnnotations();
                for (int j = annots.getCount(); j >= 1; j--) {
                    annots.delete(j);
                }
            }
            LOG.fine("Deleted all annotations from the document");
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to delete annotations", e);
            return false;
        }
    }

    /**
     * Deletes annotations of the specified type from all pages.
     *
     * @param annotationType the annotation subtype name (e.g. "Text", "Highlight", "Link")
     * @return {@code true} on success
     */
    public boolean deleteAnnotations(String annotationType) {
        try {
            PageCollection pages = document.getPages();
            for (int i = 1; i <= pages.getCount(); i++) {
                Page page = pages.get(i);
                AnnotationCollection annots = page.getAnnotations();
                // Delete matching annotations in reverse order
                for (int j = annots.getCount(); j >= 1; j--) {
                    Annotation annot = annots.get(j);
                    if (annotationType.equals(annot.getSubtype())) {
                        annots.delete(j);
                    }
                }
            }
            LOG.fine("Deleted annotations of type '" + annotationType + "'");
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to delete annotations of type: " + annotationType, e);
            return false;
        }
    }

    /**
     * Returns the number of annotations on the specified page.
     *
     * @param pageNumber 1-based page number
     * @return the annotation count, or 0 on error
     */
    public int getAnnotationCount(int pageNumber) {
        try {
            Page page = document.getPages().get(pageNumber);
            return page.getAnnotations().getCount();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to get annotation count for page " + pageNumber, e);
            return 0;
        }
    }

    /**
     * Renames the author (the annotation's /T entry) on every markup
     * annotation in the inclusive page range [startPage, endPage] whose
     * current author equals {@code oldAuthor}. Mirrors C#
     * {@code PdfAnnotationEditor.ModifyAnnotationsAuthor(int, int, string, string)}.
     */
    public void modifyAnnotationsAuthor(int startPage, int endPage,
                                         String oldAuthor, String newAuthor) {
        if (document == null) return;
        try {
            int total = document.getPages().getCount();
            int from = Math.max(1, startPage);
            int to = Math.min(total, endPage);
            for (int p = from; p <= to; p++) {
                Page page = document.getPages().get(p);
                for (org.aspose.pdf.annotations.Annotation a : page.getAnnotations()) {
                    if (a instanceof org.aspose.pdf.annotations.MarkupAnnotation) {
                        org.aspose.pdf.annotations.MarkupAnnotation m =
                                (org.aspose.pdf.annotations.MarkupAnnotation) a;
                        if (oldAuthor == null || oldAuthor.equals(m.getTitle())) {
                            m.setTitle(newAuthor);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "modifyAnnotationsAuthor failed", e);
        }
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
     * Exports annotations to XFDF format, optionally filtering by page range and type.
     *
     * @param stream     output stream for XFDF data
     * @param startPage  1-based start page (inclusive)
     * @param endPage    1-based end page (inclusive)
     * @param annotTypes array of annotation types to export (null = all)
     * @throws IOException if writing fails
     */
    public void exportAnnotationsXfdf(OutputStream stream, int startPage, int endPage,
                                       AnnotationType[] annotTypes) throws IOException {
        if (document == null) throw new IllegalStateException("No document bound");
        XfdfExporter.ExportFilter filter = new XfdfExporter.ExportFilter();
        filter.startPage = startPage;
        filter.endPage = endPage;
        if (annotTypes != null) {
            String[] types = new String[annotTypes.length];
            for (int i = 0; i < annotTypes.length; i++) {
                types[i] = annotTypes[i].getSubtype();
            }
            filter.annotationTypes = types;
        }
        XfdfExporter.export(document, stream, filter);
    }

    /**
     * Imports annotations from an XFDF input stream.
     *
     * @param stream     XFDF input stream
     * @param annotTypes annotation types to import (null = all)
     * @throws IOException if reading fails
     */
    public void importAnnotationFromXfdf(InputStream stream, AnnotationType[] annotTypes) throws IOException {
        if (document == null) throw new IllegalStateException("No document bound");
        Set<String> allowed = null;
        if (annotTypes != null) {
            allowed = new HashSet<>();
            for (AnnotationType at : annotTypes) {
                allowed.add(at.getSubtype());
            }
        }
        XfdfImporter.importXfdf(document, stream, allowed);
    }

    /**
     * Imports annotations from an XFDF input stream (all types).
     *
     * @param stream XFDF input stream
     * @throws IOException if reading fails
     */
    public void importAnnotationFromXfdf(InputStream stream) throws IOException {
        importAnnotationFromXfdf(stream, null);
    }

    /**
     * Imports annotations from an XFDF file (all types).
     *
     * @param filePath path to the XFDF file
     * @throws IOException if reading fails
     */
    public void importAnnotationFromXfdf(String filePath) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath)) {
            importAnnotationFromXfdf(fis, null);
        }
    }

    /**
     * Imports annotations from an XFDF file (all types).
     * This is an alias for {@link #importAnnotationFromXfdf(String)}.
     *
     * @param filePath path to the XFDF file
     * @throws IOException if reading fails
     */
    public void importAnnotationsFromXfdf(String filePath) throws IOException {
        importAnnotationFromXfdf(filePath);
    }

    /**
     * Imports annotations from an XFDF input stream (all types).
     * This is an alias for {@link #importAnnotationFromXfdf(InputStream)}.
     *
     * @param stream XFDF input stream
     * @throws IOException if reading fails
     */
    public void importAnnotationsFromXfdf(InputStream stream) throws IOException {
        importAnnotationFromXfdf(stream);
    }

    /**
     * Imports annotations from an XFDF input stream with type filtering.
     * This is an alias for {@link #importAnnotationFromXfdf(InputStream, AnnotationType[])}.
     *
     * @param stream     XFDF input stream
     * @param annotTypes annotation types to import (null = all)
     * @throws IOException if reading fails
     */
    public void importAnnotationsFromXfdf(InputStream stream, AnnotationType[] annotTypes) throws IOException {
        importAnnotationFromXfdf(stream, annotTypes);
    }

    /**
     * Returns the bound document.
     *
     * @return the document, or null if not bound
     */
    public Document getDocument() {
        return document;
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
}
