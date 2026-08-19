package org.aspose.pdf.sdm.html;

/**
 * Options for {@link HtmlSdmReader} (IR Stage 4, PART 1). Kept intentionally
 * small — page setup and font policy belong to the SDM&rarr;PDF layout stage, not
 * to the HTML&rarr;SDM structural read.
 */
public final class HtmlReadOptions {

    private String baseUri;
    private boolean collectUnsupportedCss = true;
    private boolean allowNetwork = false;

    /** @return the base URI used to resolve relative resource references, or null. */
    public String getBaseUri() {
        return baseUri;
    }

    /**
     * Sets the base URI for resolving relative {@code img src} references.
     *
     * @param baseUri the base URI
     */
    public void setBaseUri(String baseUri) {
        this.baseUri = baseUri;
    }

    /** @return whether unsupported CSS properties are recorded on the read report. */
    public boolean isCollectUnsupportedCss() {
        return collectUnsupportedCss;
    }

    /**
     * Sets whether unsupported CSS properties are recorded (default true).
     *
     * @param collectUnsupportedCss the flag
     */
    public void setCollectUnsupportedCss(boolean collectUnsupportedCss) {
        this.collectUnsupportedCss = collectUnsupportedCss;
    }

    /** @return whether {@code http:}/{@code https:} resources may be fetched (default false). */
    public boolean isAllowNetwork() {
        return allowNetwork;
    }

    /**
     * Sets whether external {@code http:}/{@code https:} resources ({@code <img>},
     * {@code <link rel=stylesheet>}) may be fetched over the network. Default
     * {@code false} — the reader stays offline and only resolves {@code data:}
     * URIs and local/{@code file:} paths.
     *
     * @param allowNetwork {@code true} to permit network fetches
     */
    public void setAllowNetwork(boolean allowNetwork) {
        this.allowNetwork = allowNetwork;
    }
}
