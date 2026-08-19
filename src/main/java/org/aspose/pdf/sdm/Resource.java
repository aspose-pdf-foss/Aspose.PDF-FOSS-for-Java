package org.aspose.pdf.sdm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry of the {@link ResourceTable}: typed payload bytes plus metadata
 * (IR spec §1.9).
 */
public final class Resource {

    /** Kind of resource payload. */
    public enum Kind {
        /** Raster image. */ IMAGE,
        /** Font program. */ FONT,
        /** Anything else. */ OTHER
    }

    private final Kind kind;
    private final byte[] bytes;
    private final String mime;
    private final Map<String, Object> meta = new LinkedHashMap<>();

    /**
     * Creates a resource.
     *
     * @param kind  the payload kind
     * @param bytes the payload bytes (kept by reference; treat as immutable)
     * @param mime  the MIME type, e.g. "image/jpeg" (may be null)
     */
    public Resource(Kind kind, byte[] bytes, String mime) {
        this.kind = kind;
        this.bytes = bytes;
        this.mime = mime;
    }

    /**
     * Returns the payload kind.
     *
     * @return the kind
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Returns the payload bytes.
     *
     * @return the bytes
     */
    public byte[] getBytes() {
        return bytes;
    }

    /**
     * Returns the MIME type.
     *
     * @return the MIME type, or null
     */
    public String getMime() {
        return mime;
    }

    /**
     * Returns the mutable metadata map (width/height, colorspace, ...).
     *
     * @return the metadata
     */
    public Map<String, Object> getMeta() {
        return meta;
    }
}
