# Digital Signatures

Sign PDF documents and verify existing signatures using the
`org.aspose.pdf.facades.PdfFileSignature` facade together with the signature
types in `org.aspose.pdf.forms`. Signing produces standard PKCS#7 signatures
(ISO 32000-1:2008, §12.8) and works with RSA, DSA, and ECDSA keys.

This library has **zero third-party dependencies**: all cryptography goes
through the JDK's `java.security` / `javax.crypto` stack. Certificates and
private keys are therefore loaded with the standard Java `java.security.KeyStore`
API — most commonly from a PKCS#12 (`.pfx` / `.p12`) file. The `Signature`
subclasses (`PKCS7Detached`, `PKCS7`, `PKCS1`) wrap a `KeyStore` for you.

- Version 26.7, Java 11+
- Root package: `org.aspose.pdf` (facade in `org.aspose.pdf.facades`, signature
  types in `org.aspose.pdf.forms`)
- Page indices are **1-based**.

## Key classes

| Class | Package | Role |
|-------|---------|------|
| `PdfFileSignature` | `org.aspose.pdf.facades` | Facade: bind a PDF, sign, verify, enumerate, remove signatures. `AutoCloseable`. |
| `Signature` | `org.aspose.pdf.forms` | Abstract base. Loads a PKCS#12 keystore; exposes `getPrivateKey()`, `getCertificate()`, `getCertificateChain()`, and reason/location/contact metadata. |
| `PKCS7Detached` | `org.aspose.pdf.forms` | PKCS#7 detached signature (`adbe.pkcs7.detached`) — recommended. |
| `PKCS7` | `org.aspose.pdf.forms` | Legacy PKCS#7 SHA-1 (`adbe.pkcs7.sha1`). |
| `PKCS1` | `org.aspose.pdf.forms` | Legacy raw RSA (`adbe.x509.rsa_sha1`). |
| `SignatureName` | `org.aspose.pdf.facades` | Descriptor returned by `getSignatureNames(...)`: `getFullName()`, `getHasSignature()`. |

## Signing a PDF with a PKCS#12 (.pfx) keystore

`PKCS7Detached` (and its siblings) load a PKCS#12 keystore internally, so the
simplest path is to pass the `.pfx` path and password directly. Construct the
signature object, bind the source PDF, place the signature on a page, then call
`sign(...)` and `save(...)`.

```java
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.facades.PdfFileSignature;
import org.aspose.pdf.forms.PKCS7Detached;
import org.aspose.pdf.forms.Signature;

public class SignPdf {
    public static void main(String[] args) throws Exception {
        // PKCS7Detached loads the PKCS#12 keystore for you (KeyStore.getInstance("PKCS12")).
        Signature signature = new PKCS7Detached("keystore.pfx", "secret");
        signature.setReason("Approval");
        signature.setLocation("Berlin");
        signature.setContactInfo("signer@example.com");

        try (PdfFileSignature facade = new PdfFileSignature()) {
            facade.bindPdf("input.pdf");

            // Place a visible signature on page 1 (1-based).
            Rectangle rect = new Rectangle(100, 100, 300, 160);
            facade.sign(
                    1,                // 1-based page number
                    "Approval",       // reason
                    "signer@example.com", // contact
                    "Berlin",         // location
                    true,             // visible
                    rect,             // rectangle for the visible field
                    signature);

            facade.save("signed.pdf");
        }
    }
}
```

The facade names the new field `Signature1`, `Signature2`, … (first free name).
Pass `visible = false` (and `rect = null`) for an invisible signature — an
all-zero rectangle is used automatically.

### Loading the key/certificate yourself via `java.security.KeyStore`

Because the crypto is pure JDK, you can inspect or supply the certificate and
private key with the standard `java.security.KeyStore` API. The `Signature`
object exposes them directly:

```java
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import org.aspose.pdf.forms.PKCS7Detached;
import org.aspose.pdf.forms.Signature;

Signature signature = new PKCS7Detached("keystore.pfx", "secret");

// Standard java.security types — no third-party library involved.
PrivateKey key = signature.getPrivateKey();
X509Certificate cert = signature.getCertificate();
X509Certificate[] chain = signature.getCertificateChain(); // may be null
```

If you already have an open keystore stream, both a path-based and a
stream-based constructor exist:

```java
import java.io.FileInputStream;
import java.io.InputStream;
import org.aspose.pdf.forms.PKCS7Detached;

try (InputStream pfx = new FileInputStream("keystore.pfx")) {
    PKCS7Detached signature = new PKCS7Detached(pfx, "secret");
    // ... sign as above
}
```

Internally `Signature` calls `KeyStore.getInstance("PKCS12")`, loads it with the
supplied password, and picks the first alias in the keystore.

### Signing an existing (blank) signature field

If the PDF already contains an empty signature field, sign it by name instead of
creating a new one:

```java
try (PdfFileSignature facade = new PdfFileSignature("form.pdf")) {
    Signature signature = new PKCS7Detached("keystore.pfx", "secret");
    facade.sign("Signature1", signature);   // field must already exist
    facade.save("signed.pdf");
}
```

## Verifying signatures in a document

Enumerate the signature fields, then call `verifySignature`. Verification
recomputes the byte-range digest from the **original file bytes** and checks it
against the embedded PKCS#7 signature.

```java
import java.util.List;
import org.aspose.pdf.facades.PdfFileSignature;
import org.aspose.pdf.facades.SignatureName;

public class VerifyPdf {
    public static void main(String[] args) {
        try (PdfFileSignature facade = new PdfFileSignature("signed.pdf")) {

            // Only signed fields (onlyActive = true).
            List<SignatureName> names = facade.getSignatureNames(true);
            for (SignatureName name : names) {
                boolean ok = facade.verifySignature(name.getFullName());
                System.out.println(name.getFullName()
                        + " signed=" + name.getHasSignature()
                        + " valid=" + ok);

                // Signature metadata (may be null):
                System.out.println("  signer:   " + facade.getSignerName(name));
                System.out.println("  reason:   " + facade.getReason(name));
                System.out.println("  location: " + facade.getLocation(name));
                System.out.println("  date:     " + facade.getDateTime(name));
            }
        }
    }
}
```

`verifySignature(String)` dispatches on the signature's `/SubFilter`:

- `adbe.pkcs7.detached`, `adbe.pkcs7.sha1`, `ETSI.CAdES.detached` → PKCS#7 path
  (`adbe.pkcs7.sha1` additionally cross-checks the encapsulated SHA-1 digest).
- `adbe.x509.rsa_sha1` → raw RSA (`SHA1withRSA`) against the certificate in `/Cert`.

`getSignNames()` returns just the names (as `List<String>`) of the signed
fields, mirroring the same "has a `/V` value" gate.

### Overloads returning a result object

`verifySignature` has overloads that accept
`org.aspose.pdf.security.ValidationOptions` and can fill a
`org.aspose.pdf.security.ValidationResult[]` of length 1:

```java
import org.aspose.pdf.security.ValidationOptions;
import org.aspose.pdf.security.ValidationResult;

ValidationResult[] out = new ValidationResult[1];
boolean valid = facade.verifySignature("Signature1", new ValidationOptions(), out);
// out[0] carries the boolean outcome and a human-readable message.
```

Note: the options argument does not currently change the verification logic;
the same core PKCS#7 / byte-range check runs regardless.

## Checking whether a document is signed

```java
try (PdfFileSignature facade = new PdfFileSignature("input.pdf")) {
    boolean hasSignature = facade.containsSignature();   // any signed field?
    boolean same         = facade.isContainSignature();  // alias of containsSignature()
    boolean thisOne      = facade.isSigned("Signature1"); // a specific field

    // Blank (unsigned) signature fields, if any:
    var blanks = facade.getBlankSignNames();

    // Signature revisions (one per signed field, in field order):
    int revisions = facade.getTotalRevision();
    int rev       = facade.getRevision("Signature1"); // 1-based, 0 if not signed

    // Does the named signature cover the whole file?
    boolean whole = facade.isCoversWholeDocument("Signature1");
}
```

## Notes & limitations

- **Supported signing format.** The public `PdfFileSignature.sign(...)` methods
  always produce a **PKCS#7 detached** signature (`/SubFilter adbe.pkcs7.detached`)
  with a **SHA-256** message digest, regardless of which `Signature` subclass you
  pass. The `getSubFilter()` value of `PKCS7`/`PKCS1` affects the declared type
  but the facade path emits detached PKCS#7. Use `PKCS7Detached` to keep intent
  and output aligned.
- **Key algorithms.** Signing selects the JCA signature algorithm from the
  private key family, so **RSA, DSA, and ECDSA** keystores are all supported for
  signing. The `digestEncryptionAlgorithm` OID in the produced SignerInfo is set
  to `rsaEncryption` / `id-dsa` / `id-ecPublicKey` accordingly.
- **Verification formats.** Verification handles PKCS#7 detached and SHA-1
  variants, the `ETSI.CAdES.detached` subfilter (as PKCS#7), and legacy
  `adbe.x509.rsa_sha1`. Verification uses the JDK verifier for the certificate's
  algorithm (RSA/DSA/ECDSA).
- **Verification needs the source bytes.** `verifySignature(...)` reads the
  document's backing file to recompute the byte-range digest. If the document was
  opened purely from a stream or `byte[]` with no file path, byte-range
  verification cannot run and the method returns `false`.
- **No trust-chain / revocation checking.** Verification confirms the
  cryptographic integrity of the byte range against the embedded certificate. It
  does **not** validate the certificate chain, expiry, CRL/OCSP status, or
  timestamps. Do that separately with `java.security.cert.*` if required.
- **No certification (DocMDP) API.** There is no `isCertified` /
  author-certification method on `PdfFileSignature`; signatures created here are
  ordinary approval signatures. Use `containsSignature()` / `isSigned(name)` to
  test whether a document carries a signature.
- **Signing writes a full document rewrite**, not a strict incremental update;
  the signed bytes are emitted by `save(...)`.
- **Removing signatures.** `removeSignature(name)` clears the field value;
  `removeSignature(name, true)` deletes the field entirely.
- `PdfFileSignature` is `AutoCloseable`; use try-with-resources. When the facade
  owns the `Document` (bound by path or stream) it closes it for you.

## See also

- `org.aspose.pdf.forms.SignatureField` — the underlying form field type
  (`isSigned()`, `getByteRange()`, `getSignatureBytes()`, `getSubFilter()`).
- `org.aspose.pdf.forms.SignatureCustomAppearance` — customize a visible
  signature's appearance (via `Signature.setCustomAppearance(...)`).
- `org.aspose.pdf.forms.Form` — enumerate and manage form fields.
- `java.security.KeyStore`, `java.security.cert.X509Certificate` — standard JDK
  types used for key/certificate material.
