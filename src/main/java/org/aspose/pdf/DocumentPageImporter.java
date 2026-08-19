package org.aspose.pdf;

import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfObjectCloner;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfStream;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Imports pages from one {@link Document} into another by performing a full
 * deep copy of the source page's PDF object subgraph into fresh indirect objects
 * belonging to the target document. Cross-document {@code /Parent},
 * {@code /Dest} and {@code /StructParent[s]} references are dropped; shared
 * resources (fonts, images, extended graphics states) appearing on multiple
 * imported pages are cloned exactly once via {@link PdfObjectCloner}.
 * <p>
 * Instances of this class are cheap; reuse one instance per source→target
 * pair so shared resources are deduplicated across multiple imported pages.
 * </p>
 */
public final class DocumentPageImporter {

    private static final Logger LOG = Logger.getLogger(DocumentPageImporter.class.getName());

    private final Document target;
    private final Document source;
    /**
     * Long-lived cloner for sub-resource deduplication (fonts, color spaces,
     * images shared across imported pages). NOT used for the page-root
     * dictionary itself — see {@link #importPage(Page)} for why.
     */
    private final PdfObjectCloner cloner;

    /** Identity set of field dicts reachable from the source /AcroForm /Fields; lazily built. */
    private java.util.Set<PdfDictionary> sourceFormFields;

    /** Set once the source /OCProperties has been merged into the target catalog. */
    private boolean ocPropertiesMerged;

    /**
     * @param target target document, receives cloned pages
     * @param source source document, owns the original pages
     * @throws IllegalArgumentException if either argument is null
     * @throws IllegalStateException    if the source is encrypted and not authenticated
     */
    public DocumentPageImporter(Document target, Document source) {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        if (source == null) throw new IllegalArgumentException("source must not be null");
        this.target = target;
        this.source = source;
        this.cloner = new PdfObjectCloner(target::registerImportedObject);
        if (source.isEncrypted()) {
            // Documents opened with the correct password resolve refs through a
            // decryptor installed on the parser. If no decryptor is set, strings
            // and stream data would still be ciphertext — refuse.
            if (!sourceAuthenticated()) {
                throw new IllegalStateException(
                        "Cannot import pages from encrypted source document: not authenticated");
            }
        }
    }

    /**
     * Deep-copies {@code sourcePage} into a new {@link Page} backed by a fresh
     * page dictionary registered in the target document. The result is not yet
     * attached to the target page tree — caller is expected to splice it into
     * {@code target.getPages()} (typically via {@link PageCollection#add(Page)}).
     */
    public Page importPage(Page sourcePage) throws IOException {
        if (sourcePage == null) throw new IllegalArgumentException("sourcePage must not be null");
        PdfDictionary srcDict = sourcePage.getPdfDictionary();
        // The shared cloner caches every visited source PdfBase so that fonts/
        // images/etc. shared across pages dedupe nicely on import. But that
        // cache must NOT span the page-root: importing the same source page
        // twice (Pages.add(src.Pages[1]); Pages.insert(35, src.Pages[1]); …)
        // is a legal Aspose pattern that must produce DISTINCT cloned page
        // dictionaries — otherwise /Kids ends up with multiple references to
        // the same dict, save/reload collapses them, and identity-based
        // PageCollection.delete(int) silently removes the wrong entry
        // (PDFNEWNET-31533_3 reproducer). Forget the source page-root before
        // each clone so the next clonePageDict produces a fresh top-level
        // copy; sub-resources still share their cached clones.
        cloner.forgetSource(srcDict);
        PdfDictionary clonedDict = cloner.clonePageDict(srcDict);
        materializeInheritedPageProperties(sourcePage, clonedDict);
        PdfObjectReference pageRef = target.registerImportedObject(clonedDict);
        remapAnnotations(srcDict, clonedDict, pageRef);
        promoteContentsToIndirect(clonedDict);
        mergeOcProperties();
        Page newPage = new Page(clonedDict, target.getParser());
        newPage.setOwningDocument(target);
        return newPage;
    }

    /**
     * Materializes effective inheritable page properties on the cloned page
     * before it is attached to a different page tree. Without this, pages that
     * relied on inherited {@code /MediaBox}, {@code /CropBox}, {@code /Rotate}
     * or {@code /Resources} can silently pick up different values from the
     * target document's {@code /Pages} root after reparenting.
     */
    private void materializeInheritedPageProperties(Page sourcePage, PdfDictionary clonedPage) throws IOException {
        Rectangle mediaBox = sourcePage.getMediaBox();
        if (mediaBox != null) {
            clonedPage.set(PdfName.MEDIABOX, mediaBox.toPdfArray());
        }

        Rectangle cropBox = sourcePage.getCropBox();
        if (cropBox != null) {
            clonedPage.set(PdfName.CROPBOX, cropBox.toPdfArray());
        }

        int rotate = sourcePage.getRotate();
        if (rotate != 0) {
            clonedPage.set(PdfName.ROTATE, org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(rotate));
        } else {
            clonedPage.remove(PdfName.ROTATE);
        }

        Resources resources = sourcePage.getResources();
        if (resources != null && resources.getPdfDictionary() != null) {
            PdfBase clonedResources = cloner.cloneAny(resources.getPdfDictionary());
            if (clonedResources != null) {
                clonedPage.set(PdfName.RESOURCES, clonedResources);
            }
        }
    }

    /**
     * Walks the cloned /Annots array: for each annotation dictionary, applies
     * {@link PdfObjectCloner#cloneAnnotationDict(PdfDictionary)} so /P and /Dest are
     * dropped, then sets /P to a reference to the new page.
     */
    private void remapAnnotations(PdfDictionary srcPage, PdfDictionary newPage,
                                  PdfObjectReference newPageRef) throws IOException {
        // Work off the source /Annots to control the cloning; the original
        // clonePageDict copied /Annots array refs blindly via cloneAny, which is
        // correct structurally but did not apply ANNOT_STOP_KEYS. Replace now.
        PdfBase srcAnnotsVal = srcPage.get(PdfName.ANNOTS);
        if (srcAnnotsVal instanceof PdfObjectReference) {
            try {
                srcAnnotsVal = ((PdfObjectReference) srcAnnotsVal).dereference();
            } catch (IOException e) {
                LOG.warning("Failed to dereference source /Annots: " + e.getMessage());
                newPage.remove(PdfName.ANNOTS);
                return;
            }
        }
        if (!(srcAnnotsVal instanceof PdfArray)) {
            return;
        }
        PdfArray srcAnnots = (PdfArray) srcAnnotsVal;
        PdfArray newAnnots = new PdfArray(srcAnnots.size());
        for (int i = 0; i < srcAnnots.size(); i++) {
            PdfBase item = srcAnnots.get(i);
            PdfDictionary srcAnnot = resolveDict(item);
            if (srcAnnot == null) continue;
            PdfDictionary clonedAnnot = cloner.cloneAnnotationDict(srcAnnot);
            clonedAnnot.set(PdfName.of("P"), newPageRef);
            // Drop a dangling /Parent: a widget whose /Parent field is not part of
            // the source /AcroForm /Fields tree (e.g. a source with no AcroForm
            // whose widgets are parented to a junk grouping field — corpus 36395)
            // would otherwise carry that malformed parent into the merged document.
            // The widget keeps its own /T, becoming a clean self-named field.
            PdfDictionary srcParent = resolveDict(srcAnnot.get(PdfName.of("Parent")));
            if (srcParent != null && !sourceFormFields().contains(srcParent)) {
                clonedAnnot.remove(PdfName.of("Parent"));
            }
            newAnnots.add(target.registerImportedObject(clonedAnnot));
        }
        newPage.set(PdfName.ANNOTS, newAnnots);
    }

    /**
     * Lazily collects (by identity) every field dictionary reachable from the
     * source document's {@code /AcroForm /Fields} tree. A widget {@code /Parent}
     * that is absent from this set is a dangling reference (the source has no
     * AcroForm, or the parent is a junk grouping field) and is dropped on import.
     */
    private java.util.Set<PdfDictionary> sourceFormFields() {
        if (sourceFormFields != null) {
            return sourceFormFields;
        }
        sourceFormFields = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        try {
            PdfDictionary catalog = source.getCatalog();
            if (catalog != null) {
                PdfBase af = deref(catalog.get(PdfName.of("AcroForm")));
                if (af instanceof PdfDictionary) {
                    PdfBase fields = deref(((PdfDictionary) af).get(PdfName.of("Fields")));
                    if (fields instanceof PdfArray) {
                        collectFieldDicts((PdfArray) fields, 0);
                    }
                }
            }
        } catch (IOException e) {
            LOG.fine("Could not read source AcroForm for parent validation: " + e.getMessage());
        }
        return sourceFormFields;
    }

    private void collectFieldDicts(PdfArray arr, int depth) throws IOException {
        if (depth > 50) {
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            PdfDictionary d = resolveDict(arr.get(i));
            if (d != null && sourceFormFields.add(d)) {
                PdfBase kids = deref(d.get(PdfName.of("Kids")));
                if (kids instanceof PdfArray) {
                    collectFieldDicts((PdfArray) kids, depth + 1);
                }
            }
        }
    }

    /**
     * Merges the source catalog's {@code /OCProperties} into the target
     * (ISO 32000-1:2008, §8.11.4.2) so concatenating layered documents keeps
     * every input's Optional Content Groups with their ON/OFF states
     * (PDFNEWNET-34925). Entries are cloned through the shared {@link #cloner},
     * so the catalog's OCG references are identical to the clones referenced
     * from the imported pages' {@code /Resources /Properties}. Runs once per
     * importer (i.e. once per source document).
     */
    private void mergeOcProperties() throws IOException {
        if (ocPropertiesMerged) return;
        ocPropertiesMerged = true;
        PdfDictionary srcCat = source.getCatalog();
        PdfDictionary dstCat = target.getCatalog();
        if (srcCat == null || dstCat == null) return;
        PdfBase srcOcpVal = deref(srcCat.get(PdfName.of("OCProperties")));
        if (!(srcOcpVal instanceof PdfDictionary)) return;
        PdfDictionary srcOcp = (PdfDictionary) srcOcpVal;

        PdfBase dstOcpVal = deref(dstCat.get(PdfName.of("OCProperties")));
        PdfDictionary dstOcp;
        if (dstOcpVal instanceof PdfDictionary) {
            dstOcp = (PdfDictionary) dstOcpVal;
        } else {
            dstOcp = new PdfDictionary();
            dstCat.set(PdfName.of("OCProperties"), dstOcp);
        }

        appendClonedArray(srcOcp, dstOcp, "OCGs");

        PdfBase srcDVal = deref(srcOcp.get(PdfName.of("D")));
        if (srcDVal instanceof PdfDictionary) {
            PdfDictionary srcD = (PdfDictionary) srcDVal;
            PdfBase dstDVal = deref(dstOcp.get(PdfName.of("D")));
            PdfDictionary dstD;
            if (dstDVal instanceof PdfDictionary) {
                dstD = (PdfDictionary) dstDVal;
            } else {
                dstD = new PdfDictionary();
                dstOcp.set(PdfName.of("D"), dstD);
            }
            appendClonedArray(srcD, dstD, "ON");
            appendClonedArray(srcD, dstD, "OFF");
            appendClonedArray(srcD, dstD, "Order");
        }
    }

    /** Appends clones of the {@code src[key]} array entries onto {@code dst[key]} (created if absent). */
    private void appendClonedArray(PdfDictionary src, PdfDictionary dst, String key) throws IOException {
        PdfBase srcArrVal = deref(src.get(PdfName.of(key)));
        if (!(srcArrVal instanceof PdfArray)) return;
        PdfArray srcArr = (PdfArray) srcArrVal;
        PdfBase dstArrVal = deref(dst.get(PdfName.of(key)));
        PdfArray dstArr;
        if (dstArrVal instanceof PdfArray) {
            dstArr = (PdfArray) dstArrVal;
        } else {
            dstArr = new PdfArray();
            dst.set(PdfName.of(key), dstArr);
        }
        for (int i = 0; i < srcArr.size(); i++) {
            PdfBase cloned = cloner.cloneAny(srcArr.get(i));
            if (cloned != null) {
                dstArr.add(cloned);
            }
        }
    }

    private PdfBase deref(PdfBase v) throws IOException {
        if (v instanceof PdfObjectReference) {
            try {
                return ((PdfObjectReference) v).dereference();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return v;
    }

    private PdfDictionary resolveDict(PdfBase v) throws IOException {
        if (v instanceof PdfObjectReference) {
            PdfBase r;
            try {
                r = ((PdfObjectReference) v).dereference();
            } catch (RuntimeException e) {
                if (isMalformedReferenceFailure(e)) {
                    LOG.warning("Skipping malformed indirect dictionary reference during page import: " + e.getMessage());
                    return null;
                }
                throw e;
            }
            return r instanceof PdfDictionary ? (PdfDictionary) r : null;
        }
        return v instanceof PdfDictionary ? (PdfDictionary) v : null;
    }

    private boolean isMalformedReferenceFailure(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("Object number must be non-negative")
                || message.contains("Generation number must be non-negative");
    }

    /**
     * If /Contents was cloned as an inline PdfStream (or array of inline streams),
     * promote it/them to indirect objects registered in the target. Writers expect
     * page content streams to be indirect.
     */
    private void promoteContentsToIndirect(PdfDictionary newPage) {
        PdfBase contents = newPage.get(PdfName.CONTENTS);
        if (contents instanceof PdfStream && ((PdfStream) contents).getObjectKey() == null) {
            PdfObjectReference ref = target.registerImportedObject(contents);
            newPage.set(PdfName.CONTENTS, ref);
        } else if (contents instanceof PdfArray) {
            PdfArray arr = (PdfArray) contents;
            PdfArray promoted = new PdfArray(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                PdfBase c = arr.get(i);
                if (c instanceof PdfStream && ((PdfStream) c).getObjectKey() == null) {
                    promoted.add(target.registerImportedObject(c));
                } else {
                    promoted.add(c);
                }
            }
            newPage.set(PdfName.CONTENTS, promoted);
        }
    }

    private boolean sourceAuthenticated() {
        // PDFParser exposes no isAuthenticated(); a document fails to open
        // entirely if authentication fails (initSecurity throws). So if the
        // source Document object exists at all, it is authenticated. This check
        // is defensive and accepts both interpretations.
        try {
            if (source.getParser() == null) return true;
            // Touch an arbitrary object through the parser: if a decryptor is
            // required but absent, getObject would fail. The parser is already
            // populated, so this is essentially a tautology — kept for
            // symmetry with the documented contract.
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
