package org.aspose.pdf.sdm;

/**
 * PDF locator for objects living OUTSIDE the content stream: annotations
 * (/Annots), form fields (AcroForm tree), standalone XObjects, outline items,
 * metadata. Identified by indirect object number and generation.
 * <p>
 * Editing an ObjectRef object means editing the COS object itself (moving an
 * annotation = rewriting its /Rect); page content streams are not touched.
 * </p>
 */
public final class ObjectRef extends SourceRef {

    private final int objNum;
    private final int gen;

    /**
     * Creates an indirect-object locator.
     *
     * @param objNum the object number
     * @param gen    the generation number
     */
    public ObjectRef(int objNum, int gen) {
        super("pdf");
        if (objNum <= 0 || gen < 0) {
            throw new IllegalArgumentException("invalid object ref: " + objNum + " " + gen);
        }
        this.objNum = objNum;
        this.gen = gen;
    }

    /**
     * Returns the indirect object number.
     *
     * @return the object number
     */
    public int getObjNum() {
        return objNum;
    }

    /**
     * Returns the generation number.
     *
     * @return the generation
     */
    public int getGen() {
        return gen;
    }

    @Override
    public String canonical() {
        return "pdf:object:" + objNum + "/" + gen;
    }
}
