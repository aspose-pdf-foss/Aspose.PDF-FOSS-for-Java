package org.aspose.pdf;

import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSObjectReference;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Abstract base for all PDF actions (ISO 32000-1:2008, §12.6, p.414).
 * <p>
 * Actions specify what happens when a bookmark, link, or other trigger is activated.
 * Concrete subclasses: {@link GoToAction}, {@link GoToRemoteAction},
 * {@link UriAction}, {@link NamedAction}.
 * </p>
 */
public abstract class PdfAction {

    private static final Logger LOG = Logger.getLogger(PdfAction.class.getName());

    /** The underlying action dictionary. */
    protected COSDictionary actionDict;

    /**
     * Returns the underlying action dictionary.
     *
     * @return the COS dictionary
     */
    public COSDictionary getCOSDictionary() { return actionDict; }

    /**
     * Returns the action type (/S entry).
     *
     * @return the action type name (e.g., "GoTo", "URI", "Named")
     */
    public String getType() {
        return actionDict != null ? actionDict.getNameAsString("S") : null;
    }

    /**
     * Returns the next action (/Next), if any.
     *
     * @return the next action, or null
     * @throws IOException if parsing fails
     */
    public PdfAction getNext() throws IOException {
        if (actionDict == null) return null;
        COSBase next = resolve(actionDict.get("Next"));
        if (next instanceof COSDictionary) {
            return fromDictionary((COSDictionary) next, null);
        }
        return null;
    }

    /**
     * Factory: creates the appropriate PdfAction subclass from a dictionary.
     *
     * @param dict the action dictionary
     * @param doc  the document for resolving references (may be null)
     * @return the parsed action, or a GenericAction for unknown types
     * @throws IOException if parsing fails
     */
    public static PdfAction fromDictionary(COSDictionary dict, Document doc) throws IOException {
        if (dict == null) return null;
        String type = dict.getNameAsString("S");
        if (type == null) return new GenericAction(dict);
        switch (type) {
            case "GoTo":        return new GoToAction(dict, doc);
            case "GoToR":       return new GoToRemoteAction(dict);
            case "GoToE":       return new GoToEmbeddedAction(dict);
            // GoToURIAction is the Aspose-named subclass of UriAction; returning
            // it here keeps both `instanceof UriAction` and the typed cast in
            // ports like `(GoToURIAction) link.getAction()` working.
            case "URI":         return new GoToURIAction(dict);
            case "Named":       return new NamedAction(dict);
            case "Launch":      return new LaunchAction(dict);
            case "Hide":        return new HideAction(dict);
            case "JavaScript":  return new JavaScriptAction(dict);
            case "SubmitForm":  return new SubmitFormAction(dict);
            case "ResetForm":   return new ResetFormAction(dict);
            case "ImportData":  return new ImportDataAction(dict);
            case "SetOCGState": return new SetOCGStateAction(dict);
            case "Rendition":   return new RenditionAction(dict);
            case "Trans":       return new TransitionAction(dict);
            default:            return new GenericAction(dict);
        }
    }

    /**
     * Resolves indirect references.
     */
    protected static COSBase resolve(COSBase val) {
        if (val instanceof COSObjectReference) {
            try { return ((COSObjectReference) val).dereference(); }
            catch (IOException e) { return null; }
        }
        return val;
    }
}
