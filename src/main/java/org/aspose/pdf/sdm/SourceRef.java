package org.aspose.pdf.sdm;

/**
 * Provenance of a model node: where in the source document the node came from.
 * <p>
 * A SourceRef records origin, not identity: node ids ({@link SdmIds}) are minted
 * FROM the canonical form of a SourceRef at first construction, but after that
 * the id is a value living with the node — edits never recompute it.
 * </p>
 * <p>
 * For PDF the locator is a union by object nature: {@link ContentRange} for
 * content-stream objects (text, vectors, images) and {@link ObjectRef} for
 * objects living outside the content stream (annotations, form fields).
 * </p>
 */
public abstract class SourceRef {

    private final String format;

    /**
     * Creates a source reference for the given source format.
     *
     * @param format the source document format, e.g. "pdf", "html", "docx"
     */
    protected SourceRef(String format) {
        if (format == null || format.isEmpty()) {
            throw new IllegalArgumentException("format must not be null or empty");
        }
        this.format = format;
    }

    /**
     * Returns the source document format ("pdf", "html", ...).
     *
     * @return the format tag
     */
    public String getFormat() {
        return format;
    }

    /**
     * Returns the canonical string form of this reference, used as the uuidV5
     * name when minting node ids. The format is FROZEN (tested by determinism
     * tests) — changing it changes every id in every document:
     * <ul>
     *   <li>{@code pdf:content:{pageObjNum}:{opStart}-{opEnd}}</li>
     *   <li>{@code pdf:object:{objNum}/{gen}}</li>
     * </ul>
     *
     * @return the canonical form
     */
    public abstract String canonical();

    @Override
    public String toString() {
        return canonical();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return canonical().equals(((SourceRef) o).canonical());
    }

    @Override
    public int hashCode() {
        return canonical().hashCode();
    }
}
