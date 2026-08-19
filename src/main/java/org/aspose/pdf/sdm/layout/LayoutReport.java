package org.aspose.pdf.sdm.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostics from an {@link SdmPdfLayout} render (IR Stage 4, PART 2). Nothing
 * is silently degraded: every font substitution (requested family &rarr; used
 * family), every {@link org.aspose.pdf.sdm.Opaque} placeholder box, and any
 * layout edge case (overflowing unbreakable token, missing image resource) is
 * recorded here.
 */
public final class LayoutReport {

    private final Map<String, String> fontSubstitutions = new LinkedHashMap<>();
    private final List<String> opaquePlaceholders = new ArrayList<>();
    private final List<String> missingResources = new ArrayList<>();
    private final List<String> overflowTokens = new ArrayList<>();
    private int pageCount;

    void recordFontSubstitution(String requested, String used) {
        if (requested != null && !requested.equals(used)) {
            fontSubstitutions.put(requested, used);
        }
    }

    void recordOpaque(String description) {
        opaquePlaceholders.add(description);
    }

    void recordMissingResource(String id) {
        missingResources.add(id);
    }

    void recordOverflowToken(String token) {
        overflowTokens.add(token);
    }

    void setPageCount(int pageCount) {
        this.pageCount = pageCount;
    }

    /** @return requested-family &rarr; substituted-family map. */
    public Map<String, String> getFontSubstitutions() {
        return fontSubstitutions;
    }

    /** @return placeholder boxes emitted for Opaque nodes. */
    public List<String> getOpaquePlaceholders() {
        return opaquePlaceholders;
    }

    /** @return image resource ids that could not be resolved. */
    public List<String> getMissingResources() {
        return missingResources;
    }

    /** @return unbreakable tokens that were allowed to exceed the content width. */
    public List<String> getOverflowTokens() {
        return overflowTokens;
    }

    /** @return the number of pages produced. */
    public int getPageCount() {
        return pageCount;
    }

    @Override
    public String toString() {
        return "LayoutReport{pages=" + pageCount
                + ", fontSubstitutions=" + fontSubstitutions
                + ", opaquePlaceholders=" + opaquePlaceholders
                + ", missingResources=" + missingResources
                + ", overflowTokens=" + overflowTokens + "}";
    }
}
