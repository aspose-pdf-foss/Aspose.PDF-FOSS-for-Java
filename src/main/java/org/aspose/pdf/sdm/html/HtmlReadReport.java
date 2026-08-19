package org.aspose.pdf.sdm.html;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Diagnostics collected while reading HTML into SDM (IR Stage 4). Nothing is
 * silently dropped: unsupported CSS properties, unknown elements degraded to
 * {@link org.aspose.pdf.sdm.Opaque}, and external (non-embedded) image
 * references are all recorded here for honest reporting.
 */
public final class HtmlReadReport {

    private final Set<String> unsupportedCss = new LinkedHashSet<>();
    private final Set<String> unsupportedElements = new LinkedHashSet<>();
    private final Set<String> externalImages = new LinkedHashSet<>();
    private final Set<String> externalStylesheets = new LinkedHashSet<>();

    void addUnsupportedCss(String property) {
        unsupportedCss.add(property);
    }

    void addUnsupportedElement(String tag) {
        unsupportedElements.add(tag);
    }

    void addExternalImage(String src) {
        externalImages.add(src);
    }

    void addExternalStylesheet(String href) {
        externalStylesheets.add(href);
    }

    /** @return CSS properties encountered but not in the supported subset. */
    public Set<String> getUnsupportedCss() {
        return unsupportedCss;
    }

    /** @return element tags that were degraded to Opaque placeholders. */
    public Set<String> getUnsupportedElements() {
        return unsupportedElements;
    }

    /** @return external/relative image sources that were referenced but not embedded. */
    public Set<String> getExternalImages() {
        return externalImages;
    }

    /** @return external stylesheet hrefs that were referenced but could not be loaded. */
    public Set<String> getExternalStylesheets() {
        return externalStylesheets;
    }

    /** @return true when the read encountered no diagnostics worth reporting. */
    public boolean isClean() {
        return unsupportedCss.isEmpty() && unsupportedElements.isEmpty()
                && externalImages.isEmpty() && externalStylesheets.isEmpty();
    }

    @Override
    public String toString() {
        return "HtmlReadReport{unsupportedCss=" + unsupportedCss
                + ", unsupportedElements=" + unsupportedElements
                + ", externalImages=" + externalImages
                + ", externalStylesheets=" + externalStylesheets + "}";
    }
}
