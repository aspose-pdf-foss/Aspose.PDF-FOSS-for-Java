package org.aspose.pdf.facades;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.cos.*;
import org.aspose.pdf.forms.Field;
import org.aspose.pdf.forms.Form;
import org.aspose.pdf.forms.Signature;
import org.aspose.pdf.forms.SignatureField;
import org.aspose.pdf.engine.security.pkcs7.PKCS7SignedData;
import org.aspose.pdf.engine.security.signature.PdfSigner;
import org.aspose.pdf.security.ValidationOptions;
import org.aspose.pdf.security.ValidationResult;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Facade for working with PDF digital signatures.
 * <p>
 * Provides methods to enumerate signature fields, verify signatures,
 * and extract signature metadata from a PDF document.
 * </p>
 */
public class PdfFileSignature implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PdfFileSignature.class.getName());

    private Document document;
    private boolean ownsDocument;
    /** Signed PDF bytes produced by PdfSigner; emitted by save() instead of document.save(). */
    private byte[] signedOutput;

    /**
     * Creates a new empty {@code PdfFileSignature} instance.
     * Call {@link #bindPdf(Document)}, {@link #bindPdf(String)}, or
     * {@link #bindPdf(InputStream)} before using other methods.
     */
    public PdfFileSignature() {
    }

    /**
     * Creates a {@code PdfFileSignature} bound to the specified document.
     *
     * @param document the PDF document
     */
    public PdfFileSignature(Document document) {
        this.document = document;
        this.ownsDocument = false;
    }

    /**
     * Creates a {@code PdfFileSignature} bound to the PDF at {@code inputFile}.
     * The facade owns the underlying {@link Document} and will close it on {@link #close()}.
     *
     * @param inputFile path to the PDF file
     */
    public PdfFileSignature(String inputFile) {
        bindPdf(inputFile);
    }

    /**
     * Binds a PDF file to this signature facade.
     *
     * @param inputFile path to the PDF file
     */
    public void bindPdf(String inputFile) {
        try {
            this.document = new Document(inputFile);
            this.ownsDocument = true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to bind PDF from file: " + inputFile, e);
        }
    }

    /**
     * Binds a PDF from an input stream.
     *
     * @param inputStream the input stream containing PDF data
     */
    public void bindPdf(InputStream inputStream) {
        try {
            this.document = new Document(inputStream);
            this.ownsDocument = true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to bind PDF from stream", e);
        }
    }

    /**
     * Binds an existing {@link Document} to this signature facade.
     *
     * @param document the document to bind
     */
    public void bindPdf(Document document) {
        if (document == null) {
            LOG.warning("Cannot bind null document");
            return;
        }
        this.document = document;
        this.ownsDocument = false;
    }

    /**
     * Returns the names of the <em>signed</em> signature fields in the document.
     * <p>
     * Mirrors the C# {@code PdfFileSignature.GetSignNames()} semantic: a
     * signature field is reported only when it actually carries a signature
     * value (its {@code /V} entry is present — ISO 32000-1:2008 §12.8). A
     * blank/unsigned signature field — including one left behind after
     * {@link #removeSignature(String, boolean)} clears {@code /V} without
     * deleting the field — is intentionally excluded; use
     * {@link #getBlankSignNames()} for those. This uses the same
     * {@link SignatureField#isSigned()} gate already applied by
     * {@link #containsSignature()} and {@link #getTotalRevision()}.
     * </p>
     *
     * @return list of signed signature field names
     */
    public List<String> getSignNames() {
        List<String> result = new ArrayList<>();
        if (document == null) return result;
        try {
            Form form = document.getForm();
            if (form == null) return result;
            for (Field field : form.getFields()) {
                if (field instanceof SignatureField && ((SignatureField) field).isSigned()) {
                    result.add(field.getFullName());
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error getting signature names", e);
        }
        return result;
    }

    /**
     * Returns the names of all signature fields in the document as
     * {@link SignatureName} objects with metadata about whether each field is signed.
     *
     * @param onlyActive if {@code true}, return only signed fields;
     *                   if {@code false}, return all signature fields
     * @return list of signature name descriptors
     */
    public List<SignatureName> getSignatureNames(boolean onlyActive) {
        List<SignatureName> result = new ArrayList<>();
        if (document == null) return result;
        try {
            Form form = document.getForm();
            if (form == null) return result;
            for (Field field : form.getFields()) {
                if (field instanceof SignatureField) {
                    SignatureField sf = (SignatureField) field;
                    boolean signed = sf.isSigned();
                    if (!onlyActive || signed) {
                        result.add(new SignatureName(field.getFullName(), signed));
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error getting signature names", e);
        }
        return result;
    }

    /**
     * Returns the names of all signature fields (both signed and unsigned).
     *
     * @return list of signature name descriptors
     */
    public List<SignatureName> getSignatureNames() {
        return getSignatureNames(false);
    }

    /**
     * Returns the names of unsigned (blank) signature fields.
     *
     * @return list of blank signature field names
     */
    public List<String> getBlankSignNames() {
        List<String> result = new ArrayList<>();
        if (document == null) return result;
        try {
            Form form = document.getForm();
            if (form == null) return result;
            for (Field field : form.getFields()) {
                if (field instanceof SignatureField) {
                    SignatureField sf = (SignatureField) field;
                    if (!sf.isSigned()) {
                        result.add(field.getFullName());
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error getting blank signature names", e);
        }
        return result;
    }

    /**
     * Verifies the digital signature of the specified signature field.
     *
     * @param signatureName the name or {@link SignatureName} of the signature field
     * @return {@code true} if the signature is valid
     */
    public boolean verifySignature(SignatureName signatureName) {
        if (signatureName == null) return false;
        return verifySignature(signatureName.getFullName());
    }

    /**
     * Verifies the digital signature of the specified signature field.
     *
     * @param signName the fully-qualified name of the signature field
     * @return {@code true} if the signature is valid
     */
    public boolean verifySignature(String signName) {
        if (document == null || signName == null) return false;
        try {
            SignatureField sf = findSignatureField(signName);
            if (sf == null || !sf.isSigned()) return false;

            byte[] sigBytes = sf.getSignatureBytes();
            int[] byteRange = sf.getByteRange();
            if (sigBytes == null || byteRange == null || byteRange.length != 4) {
                // Without /ByteRange or /Contents we cannot verify — treat as invalid.
                LOG.fine("Signature '" + signName + "' lacks byte range or signature bytes");
                return false;
            }

            // Read the original document bytes — needed to recompute the digest.
            byte[] docBytes = readDocumentBytes();
            if (docBytes == null) {
                // Document was loaded from a stream/byte[] without a backing file —
                // we genuinely cannot recompute the byte-range digest. Treat as invalid
                // rather than masking the failure.
                LOG.fine("Cannot read source bytes for verification of: " + signName);
                return false;
            }

            // Build the signed data from byte ranges (concatenation of the two ranges
            // around the /Contents placeholder, per ISO 32000-1:2008 §12.8.1).
            byte[] signedData = extractSignedData(docBytes, byteRange);

            // Dispatch on /SubFilter (ISO 32000-1:2008 §12.8.3).
            String subFilter = sf.getSubFilter();
            if ("adbe.x509.rsa_sha1".equals(subFilter)) {
                return verifyX509RsaSha1(sf, sigBytes, signedData);
            }
            // Default: PKCS#7 (adbe.pkcs7.detached, adbe.pkcs7.sha1, ETSI.CAdES.detached).
            PKCS7SignedData pkcs7 = PKCS7SignedData.parse(sigBytes);
            if (!pkcs7.verify(signedData)) return false;
            // For adbe.pkcs7.sha1 (ISO 32000-1:2008 §12.8.3.3), the encapsulated
            // content is SHA-1(byteRange); cross-check that binding so the
            // signature can't be detached from a different document.
            if ("adbe.pkcs7.sha1".equals(subFilter)) {
                byte[] encap = pkcs7.getEncapsulatedContent();
                if (encap == null) return false;
                byte[] expected = java.security.MessageDigest.getInstance("SHA-1").digest(signedData);
                return java.security.MessageDigest.isEqual(expected, encap);
            }
            return true;
        } catch (Exception e) {
            // A parse/verify exception means the signature is not in a recognisable
            // and verifiable form — report invalid rather than hiding the failure.
            LOG.log(Level.FINE, "Signature verification threw for: " + signName, e);
            return false;
        }
    }

    /**
     * Verifies the digital signature with validation options.
     *
     * @param signName the fully-qualified name of the signature field
     * @param options  the validation options
     * @return {@code true} if the signature is valid
     */
    public boolean verifySignature(String signName, ValidationOptions options) {
        // The current implementation does not differentiate based on options;
        // it performs the same core PKCS#7 verification regardless.
        return verifySignature(signName);
    }

    /**
     * Verifies the digital signature with validation options and returns a result.
     *
     * @param signName the fully-qualified name of the signature field
     * @param options  the validation options
     * @param result   output array of size 1 to receive the {@link ValidationResult}
     * @return {@code true} if the signature is valid
     */
    public boolean verifySignature(String signName, ValidationOptions options, ValidationResult[] result) {
        boolean verified = verifySignature(signName, options);
        if (result != null && result.length > 0) {
            result[0] = new ValidationResult(verified,
                    verified ? "Signature is valid" : "Signature verification failed");
        }
        return verified;
    }

    /**
     * Verifies the digital signature of the specified {@link SignatureName} with options.
     *
     * @param signatureName the signature name descriptor
     * @param options       the validation options
     * @return {@code true} if the signature is valid
     */
    public boolean verifySignature(SignatureName signatureName, ValidationOptions options) {
        if (signatureName == null) return false;
        return verifySignature(signatureName.getFullName(), options);
    }

    /**
     * Alias for {@link #containsSignature()} matching the C# {@code IsContainSignature()} name.
     *
     * @return {@code true} if at least one signature field is signed
     */
    public boolean isContainSignature() {
        return containsSignature();
    }

    /**
     * Returns whether the document contains at least one signed signature field.
     *
     * @return {@code true} if at least one signature field is signed
     */
    public boolean containsSignature() {
        if (document == null) return false;
        try {
            Form form = document.getForm();
            if (form == null) return false;
            for (Field field : form.getFields()) {
                if (field instanceof SignatureField && ((SignatureField) field).isSigned()) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error checking for signatures", e);
        }
        return false;
    }

    /**
     * Returns whether the specified signature field is signed.
     *
     * @param signName the fully-qualified name of the signature field
     * @return {@code true} if the field is signed
     */
    public boolean isSigned(String signName) {
        SignatureField sf = findSignatureField(signName);
        return sf != null && sf.isSigned();
    }

    /**
     * Returns the signing reason for the specified signature field.
     *
     * @param signName the fully-qualified name of the signature field
     * @return the reason string, or {@code null}
     */
    public String getReason(String signName) {
        SignatureField sf = findSignatureField(signName);
        return sf != null ? sf.getReason() : null;
    }

    /**
     * Returns the signing location for the specified signature field.
     *
     * @param signName the fully-qualified name of the signature field
     * @return the location string, or {@code null}
     */
    public String getLocation(String signName) {
        SignatureField sf = findSignatureField(signName);
        return sf != null ? sf.getLocation() : null;
    }

    /**
     * Returns the signer name for the specified signature field.
     *
     * @param signName the fully-qualified name of the signature field
     * @return the signer name, or {@code null}
     */
    public String getSignerName(String signName) {
        SignatureField sf = findSignatureField(signName);
        return sf != null ? sf.getSignerName() : null;
    }

    /**
     * Returns the signing date for the specified signature field.
     *
     * @param signName the fully-qualified name of the signature field
     * @return the signing date, or {@code null}
     */
    public Date getDateTime(String signName) {
        SignatureField sf = findSignatureField(signName);
        if (sf == null) return null;
        String dateStr = sf.getDate();
        if (dateStr == null) return null;
        // Parse PDF date format D:YYYYMMDDHHmmSS
        try {
            return parsePdfDate(dateStr);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the contact info for the specified signature field.
     *
     * @param signName the fully-qualified name of the signature field
     * @return the contact info, or {@code null}
     */
    public String getContactInfo(String signName) {
        // SignatureField does not expose contact info directly from the sig dict,
        // but we can try to get it from the signature dictionary
        SignatureField sf = findSignatureField(signName);
        if (sf == null) return null;
        var sigDict = sf.getSignatureDictionary();
        if (sigDict == null) return null;
        var val = sigDict.get("ContactInfo");
        if (val instanceof org.aspose.pdf.engine.cos.COSString) {
            return ((org.aspose.pdf.engine.cos.COSString) val).getString();
        }
        return null;
    }

    /**
     * Saves the bound document to a file.
     *
     * @param outputFile path to the output file
     * @throws IOException if saving fails
     */
    public void save(String outputFile) throws IOException {
        if (signedOutput != null) {
            Files.write(Path.of(outputFile), signedOutput);
            signedOutput = null;
        } else if (document != null) {
            document.requestFullRewrite();
            document.save(outputFile);
        }
    }

    /**
     * Saves the bound document to an output stream.
     *
     * @param outputStream the output stream
     * @throws IOException if saving fails
     */
    public void save(OutputStream outputStream) throws IOException {
        if (signedOutput != null) {
            outputStream.write(signedOutput);
            signedOutput = null;
        } else if (document != null) {
            document.requestFullRewrite();
            document.save(outputStream);
        }
    }

    /**
     * Closes the signature facade and releases the bound document
     * (if this facade owns it).
     */
    @Override
    public void close() {
        if (document != null && ownsDocument) {
            try {
                document.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Error closing document", e);
            }
        }
        document = null;
    }

    /**
     * Signs the document by creating a new signature field on the given page.
     * <p>
     * Creates a new signature field at the specified rectangle, populates the
     * signature dictionary with reason/contact/location metadata, and applies
     * the signature from the provided {@link Signature} object.
     * </p>
     *
     * @param pageNumber 1-based page number
     * @param reason     the signing reason (may be null)
     * @param contact    the signer contact info (may be null)
     * @param location   the signing location (may be null)
     * @param visible    whether the signature field should be visible
     * @param rect       the rectangle for the visible signature (may be null if not visible)
     * @param signature  the {@link Signature} object containing the certificate and key
     */
    public void sign(int pageNumber, String reason, String contact, String location,
                     boolean visible, Rectangle rect, Signature signature) {
        if (document == null) {
            LOG.warning("Cannot sign: no document bound");
            return;
        }
        try {
            // Propagate metadata from facade parameters into the Signature object
            // so PdfSigner can include them in the /Sig dictionary
            if (reason != null && signature.getReason() == null) {
                signature.setReason(reason);
            }
            if (contact != null && signature.getContactInfo() == null) {
                signature.setContactInfo(contact);
            }
            if (location != null && signature.getLocation() == null) {
                signature.setLocation(location);
            }

            // Generate a unique field name
            String fieldName = "Signature_" + System.currentTimeMillis();

            // Create the signature field widget (name, rect, page) — PdfSigner
            // will find this field by name and attach the /Sig dictionary to it
            ensureSignatureField(fieldName, pageNumber, visible, rect);

            // Delegate to PdfSigner engine — produces fully signed PDF bytes
            // with PKCS#7 /Contents and /ByteRange
            PdfSigner signer = new PdfSigner();
            ByteArrayOutputStream signedOut = new ByteArrayOutputStream();
            signer.sign(document, fieldName, signature, signedOut);
            this.signedOutput = signedOut.toByteArray();

            LOG.fine("Signed document with field '" + fieldName + "' on page " + pageNumber);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to sign document", e);
        }
    }

    /**
     * Signs an existing signature field by name.
     * <p>
     * Locates the named field and populates its /V entry with a signature dictionary
     * built from the provided {@link Signature} object.
     * </p>
     *
     * @param fieldName the fully-qualified name of the signature field
     * @param signature the {@link Signature} object containing the certificate and key
     */
    public void sign(String fieldName, Signature signature) {
        if (document == null || fieldName == null || signature == null) {
            LOG.warning("Cannot sign: invalid arguments");
            return;
        }
        try {
            // Delegate to PdfSigner engine — it finds the existing field by name
            PdfSigner signer = new PdfSigner();
            ByteArrayOutputStream signedOut = new ByteArrayOutputStream();
            signer.sign(document, fieldName, signature, signedOut);
            this.signedOutput = signedOut.toByteArray();
            LOG.fine("Signed existing field: " + fieldName);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to sign field: " + fieldName, e);
        }
    }

    /**
     * Removes a signature from the specified signature field.
     * The field itself is preserved but its value (/V) is removed.
     *
     * @param signName the fully-qualified name of the signature field
     */
    public void removeSignature(String signName) {
        removeSignature(signName, false);
    }

    /**
     * Removes a signature from the specified signature field.
     *
     * @param signName    the fully-qualified name of the signature field
     * @param removeField if {@code true}, remove the field entirely;
     *                    if {@code false}, only clear the signature value
     */
    public void removeSignature(String signName, boolean removeField) {
        if (document == null || signName == null) return;
        try {
            SignatureField sf = findSignatureField(signName);
            if (sf == null) {
                LOG.fine("Signature field not found for removal: " + signName);
                return;
            }

            if (removeField) {
                // Remove the field from the form entirely
                Form form = document.getForm();
                if (form != null) {
                    form.delete(signName);
                }
            } else {
                // Clear the signature value
                sf.setSignatureDictionary(null);
            }
            LOG.fine("Removed signature from field: " + signName + " (removeField=" + removeField + ")");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to remove signature: " + signName, e);
        }
    }

    /**
     * Returns whether the document contains usage rights (UR3 dictionary in the document catalog).
     * <p>
     * Usage rights allow a document to enable additional interactive features
     * when opened in Adobe Reader.
     * </p>
     *
     * @return {@code true} if the document contains usage rights
     */
    public boolean containsUsageRights() {
        if (document == null) return false;
        try {
            COSDictionary catalog = document.getCatalog();
            if (catalog == null) return false;
            COSBase perms = catalog.get("Perms");
            if (perms instanceof COSObjectReference) {
                perms = ((COSObjectReference) perms).dereference();
            }
            if (perms instanceof COSDictionary) {
                COSBase ur3 = ((COSDictionary) perms).get("UR3");
                if (ur3 == null) {
                    ur3 = ((COSDictionary) perms).get("UR");
                }
                return ur3 != null;
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error checking usage rights", e);
        }
        return false;
    }

    /**
     * Removes usage rights from the document.
     * <p>
     * Removes the /UR3 and /UR entries from the document catalog's /Perms dictionary.
     * </p>
     */
    public void removeUsageRights() {
        if (document == null) return;
        try {
            COSDictionary catalog = document.getCatalog();
            if (catalog == null) return;
            COSBase perms = catalog.get("Perms");
            if (perms instanceof COSObjectReference) {
                perms = ((COSObjectReference) perms).dereference();
            }
            if (perms instanceof COSDictionary) {
                COSDictionary permsDict = (COSDictionary) perms;
                permsDict.remove(COSName.of("UR3"));
                permsDict.remove(COSName.of("UR"));
                LOG.fine("Removed usage rights from document");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error removing usage rights", e);
        }
    }

    /**
     * Returns the total number of signed revisions in the document.
     * <p>
     * Each signature creates an incremental update revision (ISO 32000-1:2008 §12.8.1).
     * This implementation returns the count of signed signature fields, which is
     * equivalent for documents that follow the standard signing convention.
     * </p>
     *
     * @return the number of signed revisions
     */
    public int getTotalRevision() {
        if (document == null) return 0;
        int count = 0;
        try {
            Form form = document.getForm();
            if (form == null) return 0;
            for (Field field : form.getFields()) {
                if (field instanceof SignatureField && ((SignatureField) field).isSigned()) {
                    count++;
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error counting revisions", e);
        }
        return count;
    }

    /**
     * Returns the 1-based revision number for the specified signature field.
     * Revisions are numbered in the order signatures appear in the form fields.
     *
     * @param signName the fully-qualified name of the signature field
     * @return the 1-based revision number, or 0 if the field is not signed
     */
    public int getRevision(String signName) {
        if (document == null || signName == null) return 0;
        try {
            Form form = document.getForm();
            if (form == null) return 0;
            int rev = 0;
            for (Field field : form.getFields()) {
                if (field instanceof SignatureField && ((SignatureField) field).isSigned()) {
                    rev++;
                    if (signName.equals(field.getFullName())) {
                        return rev;
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error finding revision for: " + signName, e);
        }
        return 0;
    }

    /**
     * Returns the 1-based revision number for the specified signature field.
     *
     * @param signatureName the signature name descriptor
     * @return the 1-based revision number, or 0 if not signed / not found
     */
    public int getRevision(SignatureName signatureName) {
        if (signatureName == null) return 0;
        return getRevision(signatureName.getFullName());
    }

    /**
     * Returns whether the byte range of the named signature covers the
     * entire document (i.e. the signature is over the full current state).
     * <p>
     * Per ISO 32000-1:2008 §12.8.1, a signature's byte range is
     * {@code [offset1, length1, offset2, length2]} where the gap between
     * {@code offset1+length1} and {@code offset2} is the /Contents value
     * placeholder. A signature covers the whole document when
     * {@code offset2 + length2 == fileSize} (no bytes follow the signature).
     * </p>
     *
     * @param signName the fully-qualified name of the signature field
     * @return {@code true} if the signature covers the whole document
     */
    public boolean isCoversWholeDocument(String signName) {
        SignatureField sf = findSignatureField(signName);
        if (sf == null) return false;
        int[] br = sf.getByteRange();
        if (br == null || br.length != 4) return false;
        byte[] docBytes = readDocumentBytes();
        if (docBytes == null) {
            // Without source bytes, treat the largest revision as covering whole
            return false;
        }
        int endOfCovered = br[2] + br[3];
        // Allow trailing whitespace (\n, \r, EOF marker) — common for PDFs
        return endOfCovered >= docBytes.length - 8;
    }

    /**
     * Returns whether the byte range of the named signature covers the
     * entire document.
     *
     * @param signatureName the signature name descriptor
     * @return {@code true} if the signature covers the whole document
     */
    public boolean coversWholeDocument(SignatureName signatureName) {
        if (signatureName == null) return false;
        return isCoversWholeDocument(signatureName.getFullName());
    }

    /** {@link #getReason(String)} overload taking {@link SignatureName}. */
    public String getReason(SignatureName name) {
        return name == null ? null : getReason(name.getFullName());
    }

    /** {@link #getLocation(String)} overload taking {@link SignatureName}. */
    public String getLocation(SignatureName name) {
        return name == null ? null : getLocation(name.getFullName());
    }

    /** {@link #getSignerName(String)} overload taking {@link SignatureName}. */
    public String getSignerName(SignatureName name) {
        return name == null ? null : getSignerName(name.getFullName());
    }

    /** {@link #getDateTime(String)} overload taking {@link SignatureName}. */
    public Date getDateTime(SignatureName name) {
        return name == null ? null : getDateTime(name.getFullName());
    }

    /** {@link #getContactInfo(String)} overload taking {@link SignatureName}. */
    public String getContactInfo(SignatureName name) {
        return name == null ? null : getContactInfo(name.getFullName());
    }

    // ── Internal helpers ──

    /**
     * Creates a SignatureField widget with the given name, rect, and page binding.
     * Does NOT attach a /V (signature dictionary) — that is done by PdfSigner.
     */
    private void ensureSignatureField(String fieldName, int pageNumber,
                                       boolean visible, Rectangle rect) throws IOException {
        Form form = document.getForm();
        COSDictionary fieldDict = new COSDictionary();
        fieldDict.set(COSName.of("Type"), COSName.of("Annot"));
        fieldDict.set(COSName.of("Subtype"), COSName.of("Widget"));
        fieldDict.set(COSName.of("FT"), COSName.of("Sig"));
        fieldDict.set(COSName.of("T"),
                new COSString(fieldName.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        if (visible && rect != null) {
            fieldDict.set(COSName.of("Rect"), rect.toCOSArray());
        } else {
            COSArray zeroRect = new COSArray();
            zeroRect.add(COSInteger.valueOf(0));
            zeroRect.add(COSInteger.valueOf(0));
            zeroRect.add(COSInteger.valueOf(0));
            zeroRect.add(COSInteger.valueOf(0));
            fieldDict.set(COSName.of("Rect"), zeroRect);
        }

        Page page = document.getPages().get(pageNumber);
        SignatureField sf = new SignatureField(fieldDict, page, fieldName);
        form.add(sf, pageNumber);
    }

    private SignatureField findSignatureField(String signName) {
        if (document == null || signName == null) return null;
        try {
            Form form = document.getForm();
            if (form == null) return null;
            Field field = form.get(signName);
            if (field instanceof SignatureField) {
                return (SignatureField) field;
            }
            // Try searching all fields (in case name matching is imprecise)
            for (Field f : form.getFields()) {
                if (f instanceof SignatureField && signName.equals(f.getFullName())) {
                    return (SignatureField) f;
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error finding signature field: " + signName, e);
        }
        return null;
    }

    private byte[] readDocumentBytes() {
        try {
            String sourcePath = document.getSourcePath();
            if (sourcePath != null) {
                return Files.readAllBytes(Path.of(sourcePath));
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error reading document bytes", e);
        }
        return null;
    }

    private String formatPdfDate(Date date) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("'D:'yyyyMMddHHmmss'Z'");
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return fmt.format(date);
    }

    /**
     * Verifies a signature with /SubFilter == {@code adbe.x509.rsa_sha1}
     * (ISO 32000-1:2008 §12.8.3.4).
     * <p>
     * In this format, the signature value in /Contents is the raw RSA-encrypted
     * SHA-1 digest of the byte range, and /Cert holds the DER-encoded X.509
     * certificate of the signer (or an array of certificates whose first entry
     * is the signer).
     * </p>
     */
    private boolean verifyX509RsaSha1(org.aspose.pdf.forms.SignatureField sf,
                                       byte[] sigBytes, byte[] signedData) throws Exception {
        COSDictionary sigDict = sf.getSignatureDictionary();
        if (sigDict == null) return false;
        COSBase certEntry = sigDict.get("Cert");
        if (certEntry instanceof COSObjectReference) {
            certEntry = ((COSObjectReference) certEntry).dereference();
        }
        byte[] certBytes;
        if (certEntry instanceof COSString) {
            certBytes = ((COSString) certEntry).getBytes();
        } else if (certEntry instanceof COSArray) {
            COSArray arr = (COSArray) certEntry;
            if (arr.size() == 0) return false;
            COSBase first = arr.get(0);
            if (first instanceof COSObjectReference) {
                first = ((COSObjectReference) first).dereference();
            }
            if (!(first instanceof COSString)) return false;
            certBytes = ((COSString) first).getBytes();
        } else {
            LOG.fine("adbe.x509.rsa_sha1: missing /Cert entry");
            return false;
        }

        java.security.cert.X509Certificate cert =
                (java.security.cert.X509Certificate) java.security.cert.CertificateFactory
                        .getInstance("X.509")
                        .generateCertificate(new java.io.ByteArrayInputStream(certBytes));

        // Per ISO 32000-1:2008 §12.8.3.4, /Contents holds a DER-encoded OCTET STRING
        // wrapping the raw RSASSA-PKCS1-v1_5 signature bytes. Unwrap it before
        // handing to the JCA verifier (which expects exactly the modulus-sized signature).
        byte[] rawSig = unwrapDerOctetString(sigBytes);

        java.security.Signature verifier = java.security.Signature.getInstance("SHA1withRSA");
        verifier.initVerify(cert.getPublicKey());
        verifier.update(signedData);
        return verifier.verify(rawSig);
    }

    /**
     * Strips a leading DER OCTET STRING TLV header if present, returning the inner value.
     * Falls back to the input unchanged when the bytes are not a recognisable OCTET STRING.
     */
    private static byte[] unwrapDerOctetString(byte[] data) {
        if (data == null || data.length < 2 || (data[0] & 0xFF) != 0x04) return data;
        int idx = 1;
        int firstLen = data[idx++] & 0xFF;
        int len;
        if ((firstLen & 0x80) == 0) {
            len = firstLen;
        } else {
            int numLenBytes = firstLen & 0x7F;
            if (numLenBytes == 0 || numLenBytes > 4 || idx + numLenBytes > data.length) return data;
            len = 0;
            for (int i = 0; i < numLenBytes; i++) {
                len = (len << 8) | (data[idx++] & 0xFF);
            }
        }
        if (idx + len != data.length) return data;
        byte[] out = new byte[len];
        System.arraycopy(data, idx, out, 0, len);
        return out;
    }

    private byte[] extractSignedData(byte[] docBytes, int[] byteRange) {
        // byteRange = [offset1, length1, offset2, length2]
        int len1 = byteRange[1];
        int offset2 = byteRange[2];
        int len2 = byteRange[3];
        byte[] result = new byte[len1 + len2];
        System.arraycopy(docBytes, byteRange[0], result, 0, len1);
        System.arraycopy(docBytes, offset2, result, len1, len2);
        return result;
    }

    private Date parsePdfDate(String dateStr) {
        // PDF date format: D:YYYYMMDDHHmmSSOHH'mm'
        String s = dateStr;
        if (s.startsWith("D:")) s = s.substring(2);
        // Remove timezone info for simple parsing
        int tzIdx = s.indexOf('+');
        if (tzIdx < 0) tzIdx = s.indexOf('-');
        if (tzIdx < 0) tzIdx = s.indexOf('Z');
        if (tzIdx > 0) s = s.substring(0, tzIdx);

        // Pad to at least 14 characters
        while (s.length() < 14) s += "0";

        int year = Integer.parseInt(s.substring(0, 4));
        int month = Integer.parseInt(s.substring(4, 6)) - 1;
        int day = Integer.parseInt(s.substring(6, 8));
        int hour = Integer.parseInt(s.substring(8, 10));
        int min = Integer.parseInt(s.substring(10, 12));
        int sec = Integer.parseInt(s.substring(12, 14));

        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(year, month, day, hour, min, sec);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTime();
    }
}
