package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.cos.*;

import java.util.logging.Logger;

/**
 * Signature field (/FT /Sig) (ISO 32000-1:2008, §12.7.4.5).
 * <p>
 * Represents a digital signature field in an interactive form.
 * Provides access to the signature dictionary (/V) and its entries
 * such as /Reason, /Location, /Name, /M, /ByteRange, and /Contents.
 * </p>
 */
public class SignatureField extends Field {

    private static final Logger LOG = Logger.getLogger(SignatureField.class.getName());

    /**
     * Constructs a signature field from an existing COS dictionary.
     *
     * @param dict     the COS dictionary backing this field
     * @param page     the page this field belongs to (may be null)
     * @param fullName the fully-qualified dotted name
     */
    public SignatureField(COSDictionary dict, Page page, String fullName) {
        super(dict, page, fullName);
    }

    /**
     * Creates a new, unsigned signature field on the given page at the given
     * rectangle. The field has no value ({@code /V}) until a signing operation
     * populates it.
     *
     * @param page the page on which the field's widget annotation appears
     * @param rect the field's bounding rectangle in page coordinates
     * @throws IllegalArgumentException if {@code page} or {@code rect} is null
     */
    public SignatureField(Page page, Rectangle rect) {
        super(new COSDictionary(), page, "");
        if (page == null) throw new IllegalArgumentException("page must not be null");
        if (rect == null) throw new IllegalArgumentException("rect must not be null");
        dict.set(COSName.of("Type"), COSName.of("Annot"));
        dict.set(COSName.of("Subtype"), COSName.of("Widget"));
        dict.set(COSName.of("FT"), COSName.of("Sig"));
        setRectLenient(rect);
    }

    /**
     * Returns whether this signature field has been signed.
     * <p>
     * A field is considered signed if it has a /V entry (signature dictionary).
     * </p>
     *
     * @return true if the field is signed
     */
    public boolean isSigned() {
        return dict.get("V") != null;
    }

    /**
     * Returns the signature dictionary (/V entry).
     *
     * @return the signature dictionary, or null if not signed
     */
    public COSDictionary getSignatureDictionary() {
        COSBase v = dict.get("V");
        if (v instanceof COSObjectReference) {
            try {
                v = ((COSObjectReference) v).dereference();
            } catch (java.io.IOException e) {
                return null;
            }
        }
        return (v instanceof COSDictionary) ? (COSDictionary) v : null;
    }

    /**
     * Sets the signature dictionary (/V entry).
     *
     * @param sigDict the signature dictionary
     */
    public void setSignatureDictionary(COSDictionary sigDict) {
        dict.set(COSName.of("V"), sigDict);
    }

    /**
     * Returns the signing reason from the signature dictionary.
     *
     * @return the reason string, or null
     */
    public String getReason() {
        COSDictionary sig = getSignatureDictionary();
        return sig != null ? getStringFromDict(sig, "Reason") : null;
    }

    /**
     * Returns the signing location from the signature dictionary.
     *
     * @return the location string, or null
     */
    public String getLocation() {
        COSDictionary sig = getSignatureDictionary();
        return sig != null ? getStringFromDict(sig, "Location") : null;
    }

    /**
     * Returns the signer name from the signature dictionary (/Name).
     *
     * @return the signer name, or null
     */
    public String getSignerName() {
        COSDictionary sig = getSignatureDictionary();
        return sig != null ? getStringFromDict(sig, "Name") : null;
    }

    /**
     * Returns the signing date from the signature dictionary (/M).
     *
     * @return the date string in PDF format, or null
     */
    public String getDate() {
        COSDictionary sig = getSignatureDictionary();
        return sig != null ? getStringFromDict(sig, "M") : null;
    }

    /**
     * Returns the byte range array from the signature dictionary.
     *
     * @return int[4] of byte range values, or null
     */
    public int[] getByteRange() {
        COSDictionary sig = getSignatureDictionary();
        if (sig == null) return null;
        COSBase br = sig.get("ByteRange");
        if (!(br instanceof COSArray)) return null;
        COSArray arr = (COSArray) br;
        if (arr.size() != 4) return null;
        return new int[]{arr.getInt(0, 0), arr.getInt(1, 0), arr.getInt(2, 0), arr.getInt(3, 0)};
    }

    /**
     * Returns the PKCS#7 signature bytes from /Contents.
     *
     * @return the raw signature bytes, or null
     */
    public byte[] getSignatureBytes() {
        COSDictionary sig = getSignatureDictionary();
        if (sig == null) return null;
        COSBase contents = sig.get("Contents");
        if (contents instanceof COSString) {
            return ((COSString) contents).getBytes();
        }
        return null;
    }

    /**
     * Returns the signature filter name (/Filter).
     *
     * @return the filter name (e.g. "Adobe.PPKLite"), or null
     */
    public String getFilter() {
        COSDictionary sig = getSignatureDictionary();
        if (sig == null) return null;
        return sig.getNameAsString("Filter");
    }

    /**
     * Returns the signature sub-filter name (/SubFilter).
     *
     * @return the sub-filter name (e.g. "adbe.pkcs7.detached"), or null
     */
    public String getSubFilter() {
        COSDictionary sig = getSignatureDictionary();
        if (sig == null) return null;
        return sig.getNameAsString("SubFilter");
    }

    private String getStringFromDict(COSDictionary d, String key) {
        COSBase val = d.get(key);
        if (val instanceof COSString) return ((COSString) val).getString();
        if (val instanceof COSName) return ((COSName) val).getName();
        return null;
    }
}
