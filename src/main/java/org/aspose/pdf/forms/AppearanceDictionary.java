package org.aspose.pdf.forms;

import org.aspose.pdf.XForm;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSStream;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Typed view over a form field's {@code /AP} appearance dictionary
 * (ISO 32000-1:2008 §12.5.5).
 *
 * <p>Per spec, {@code /AP} has up to three sub-entries:</p>
 * <ul>
 *   <li>{@code /N} — Normal appearance (required when /AP is present)</li>
 *   <li>{@code /R} — Rollover appearance (optional)</li>
 *   <li>{@code /D} — Down appearance (optional)</li>
 * </ul>
 *
 * <p>Each sub-entry is either a single Form XObject stream (for single-state
 * widgets like text fields) or a sub-dictionary keyed by appearance-state
 * name (for multi-state widgets like checkboxes whose /N has keys
 * {@code "Off"} and {@code "Yes"}, or radio groups whose /N has keys for
 * each option name).</p>
 *
 * <p>This is a live view: mutations to the underlying dictionary are visible
 * on the next access — there is no internal cache of streams or names.</p>
 */
public class AppearanceDictionary {

    private final COSDictionary apDict;

    /**
     * Wraps the given /AP dictionary. The caller is responsible for ensuring
     * {@code apDict} is the actual /AP sub-dictionary of a field, not the
     * field's own dictionary.
     *
     * @param apDict the /AP dictionary (must not be null)
     * @throws IllegalArgumentException if {@code apDict} is null
     */
    public AppearanceDictionary(COSDictionary apDict) {
        if (apDict == null) {
            throw new IllegalArgumentException("apDict must not be null");
        }
        this.apDict = apDict;
    }

    /**
     * Returns the set of state names available under {@code /N} (normal
     * appearance) for multi-state widgets. For single-state widgets (where
     * /AP/N is a stream rather than a dictionary) returns an empty set.
     *
     * @return the state names in insertion order (immutable view)
     */
    public Set<String> getStateNames() {
        COSBase n = apDict.get(COSName.N);
        // COSStream extends COSDictionary, so check stream first to exclude it.
        if (n instanceof COSDictionary && !(n instanceof COSStream)) {
            Set<String> out = new LinkedHashSet<>();
            for (COSName key : ((COSDictionary) n).keySet()) {
                out.add(key.getName());
            }
            return out;
        }
        return Collections.emptySet();
    }

    /**
     * Returns the normal appearance stream for the given state, or {@code null}
     * if the state name is not present or if the field is single-state.
     *
     * @param stateName the appearance state name (e.g. {@code "Off"},
     *                  {@code "Yes"}, or a radio option name)
     * @return the XForm wrapping the appearance stream, or null
     */
    public XForm get(String stateName) {
        if (stateName == null) return null;
        COSBase n = apDict.get(COSName.N);
        if (n instanceof COSDictionary && !(n instanceof COSStream)) {
            COSBase entry = ((COSDictionary) n).get(COSName.of(stateName));
            if (entry instanceof COSStream) {
                return new XForm((COSStream) entry, stateName, null);
            }
        }
        return null;
    }

    /**
     * Returns the single normal appearance stream for a single-state widget
     * (text fields, buttons, etc.), or {@code null} if the field is
     * multi-state or has no /N entry.
     *
     * @return the XForm wrapping /AP/N (when it is a stream), or null
     */
    public XForm getNormal() {
        COSBase n = apDict.get(COSName.N);
        if (n instanceof COSStream) {
            return new XForm((COSStream) n, "N", null);
        }
        return null;
    }

    /**
     * Returns {@code true} when {@code /AP/N} is a dictionary (multi-state),
     * {@code false} when it is a stream (single-state) or absent.
     *
     * @return whether this appearance is multi-state
     */
    public boolean isMultiState() {
        COSBase n = apDict.get(COSName.N);
        return n instanceof COSDictionary && !(n instanceof COSStream);
    }

    /**
     * Returns the underlying /AP COS dictionary for engine-side use.
     *
     * @return the wrapped dictionary
     */
    public COSDictionary getCOSDictionary() {
        return apDict;
    }
}
