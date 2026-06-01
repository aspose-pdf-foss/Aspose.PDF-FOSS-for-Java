package org.aspose.pdf;

import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSObjectReference;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Document-level action triggers (ISO 32000-1:2008, §12.6.4.1, p.417).
 *
 * <p>Exposes the document's {@code /OpenAction} (run on open) and the entries
 * of its {@code /AA} (additional-actions) dictionary:</p>
 * <ul>
 *   <li>{@code WC} — will close ({@link #getBeforeClosing}/{@link #setBeforeClosing})</li>
 *   <li>{@code WS} — will save ({@link #getBeforeSaving}/{@link #setBeforeSaving})</li>
 *   <li>{@code DS} — did save ({@link #getAfterSaving}/{@link #setAfterSaving})</li>
 *   <li>{@code WP} — will print ({@link #getBeforePrinting}/{@link #setBeforePrinting})</li>
 *   <li>{@code DP} — did print ({@link #getAfterPrinting}/{@link #setAfterPrinting})</li>
 * </ul>
 *
 * <p>Setting an action to {@code null} removes the corresponding entry. Reading
 * an entry whose value is a destination (not an action dictionary) returns
 * {@code null} for {@link #getOpenAction()}; see {@link Document#setOpenAction}
 * to bypass the action wrapping.</p>
 */
public class DocumentActions {

    private static final Logger LOG = Logger.getLogger(DocumentActions.class.getName());

    private static final COSName OPEN_ACTION = COSName.of("OpenAction");
    private static final COSName AA = COSName.of("AA");
    private static final COSName WC = COSName.of("WC");
    private static final COSName WS = COSName.of("WS");
    private static final COSName DS = COSName.of("DS");
    private static final COSName WP = COSName.of("WP");
    private static final COSName DP = COSName.of("DP");

    private final COSDictionary catalog;
    private final Document document;

    /**
     * Wraps the given catalog dictionary as a document-actions view.
     *
     * @param catalog  the catalog COSDictionary (must not be null)
     * @param document the owning document, used as factory context for action
     *                 dereferencing (may be null)
     * @throws IllegalArgumentException if {@code catalog} is null
     */
    public DocumentActions(COSDictionary catalog, Document document) {
        if (catalog == null) {
            throw new IllegalArgumentException("Catalog must not be null");
        }
        this.catalog = catalog;
        this.document = document;
    }

    /**
     * Returns the action that runs when the document is opened
     * ({@code /OpenAction}), or {@code null} if absent or the entry is a
     * destination array instead of an action.
     *
     * @return the open action, or null
     */
    public PdfAction getOpenAction() {
        COSBase value = resolve(catalog.get(OPEN_ACTION));
        if (value instanceof COSDictionary) {
            try {
                return PdfAction.fromDictionary((COSDictionary) value, document);
            } catch (IOException e) {
                LOG.warning(() -> "Failed to parse /OpenAction: " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * Sets the {@code /OpenAction} entry. Passing {@code null} removes it.
     *
     * @param action the open action, or null to clear
     */
    public void setOpenAction(PdfAction action) {
        if (action == null) {
            catalog.remove(OPEN_ACTION);
            return;
        }
        catalog.set(OPEN_ACTION, action.getCOSDictionary());
    }

    /** @return the will-close action ({@code /AA/WC}), or null. */
    public PdfAction getBeforeClosing()  { return getAA(WC); }
    /** @return the will-save action ({@code /AA/WS}), or null. */
    public PdfAction getBeforeSaving()   { return getAA(WS); }
    /** @return the did-save action ({@code /AA/DS}), or null. */
    public PdfAction getAfterSaving()    { return getAA(DS); }
    /** @return the will-print action ({@code /AA/WP}), or null. */
    public PdfAction getBeforePrinting() { return getAA(WP); }
    /** @return the did-print action ({@code /AA/DP}), or null. */
    public PdfAction getAfterPrinting()  { return getAA(DP); }

    /** Sets the will-close ({@code /AA/WC}) action; null removes the entry. */
    public void setBeforeClosing(PdfAction action)  { setAA(WC, action); }
    /** Sets the will-save ({@code /AA/WS}) action; null removes the entry. */
    public void setBeforeSaving(PdfAction action)   { setAA(WS, action); }
    /** Sets the did-save ({@code /AA/DS}) action; null removes the entry. */
    public void setAfterSaving(PdfAction action)    { setAA(DS, action); }
    /** Sets the will-print ({@code /AA/WP}) action; null removes the entry. */
    public void setBeforePrinting(PdfAction action) { setAA(WP, action); }
    /** Sets the did-print ({@code /AA/DP}) action; null removes the entry. */
    public void setAfterPrinting(PdfAction action)  { setAA(DP, action); }

    private PdfAction getAA(COSName key) {
        COSBase aaValue = resolve(catalog.get(AA));
        if (!(aaValue instanceof COSDictionary)) return null;
        COSBase entry = resolve(((COSDictionary) aaValue).get(key));
        if (entry instanceof COSDictionary) {
            try {
                return PdfAction.fromDictionary((COSDictionary) entry, document);
            } catch (IOException e) {
                LOG.warning(() -> "Failed to parse /AA/" + key.getName() + ": " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    private void setAA(COSName key, PdfAction action) {
        COSDictionary aa = (COSDictionary) resolve(catalog.get(AA));
        if (action == null) {
            if (aa != null) {
                aa.remove(key);
                if (aa.size() == 0) catalog.remove(AA);
            }
            return;
        }
        if (aa == null) {
            aa = new COSDictionary();
            catalog.set(AA, aa);
        }
        aa.set(key, action.getCOSDictionary());
    }

    private static COSBase resolve(COSBase value) {
        if (value instanceof COSObjectReference) {
            try {
                return ((COSObjectReference) value).dereference();
            } catch (IOException e) {
                return null;
            }
        }
        return value;
    }
}
