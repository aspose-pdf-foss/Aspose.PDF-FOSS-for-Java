package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;

/**
 * Hyperlink span (IR spec §1.4).
 *
 * <p>A link is either <em>external</em> — {@link #getHref()} is a URI — or
 * <em>internal</em> — {@link #getInternalAnchor()} names a bookmark anchor
 * elsewhere in the same document (a PDF {@code GoTo} action / {@code /Dest}).
 * At most one of the two is set; an internal link carries a {@code null} href
 * so writers can dispatch on which is present.</p>
 */
public final class LinkInline extends SdmInline {

    private final String href;
    private final String internalAnchor;
    private final List<SdmInline> children = new ArrayList<>();

    /**
     * Creates an external link span.
     *
     * @param href the link target URI
     */
    public LinkInline(String href) {
        super(SdmNodeType.LINK_INLINE);
        this.href = href;
        this.internalAnchor = null;
    }

    private LinkInline(String href, String internalAnchor) {
        super(SdmNodeType.LINK_INLINE);
        this.href = href;
        this.internalAnchor = internalAnchor;
    }

    /**
     * Creates an internal link span pointing at a bookmark anchor in the same
     * document (rendered as a Word {@code HYPERLINK \l} field / an HTML
     * {@code href="#anchor"}).
     *
     * @param anchor the target bookmark anchor name (must be non-null)
     * @return the internal link span
     */
    public static LinkInline internal(String anchor) {
        return new LinkInline(null, anchor);
    }

    /**
     * Returns the external link target.
     *
     * @return the href, or {@code null} for an internal link
     */
    public String getHref() {
        return href;
    }

    /**
     * Returns the internal bookmark anchor this link points at.
     *
     * @return the anchor name, or {@code null} for an external link
     */
    public String getInternalAnchor() {
        return internalAnchor;
    }

    /**
     * Returns the mutable inline children.
     *
     * @return the children
     */
    public List<SdmInline> getChildren() {
        return children;
    }
}
