package org.aspose.pdf.engine.security;

import org.aspose.pdf.CryptoAlgorithm;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSInteger;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSString;

import java.util.logging.Logger;

/**
 * Wraps the /Encrypt dictionary from the PDF trailer
 * (ISO 32000-1:2008, §7.6.1, Tables 20-21).
 * <p>
 * Use the constructor for read-side (parsing existing PDFs) and
 * {@link #build} for write-side (creating new encrypted PDFs).
 * </p>
 */
public class PDFEncryptionDict {

    private static final Logger LOG = Logger.getLogger(PDFEncryptionDict.class.getName());

    private final COSDictionary dict;

    /**
     * Creates an encryption dictionary wrapper.
     *
     * @param dict the /Encrypt COS dictionary
     */
    public PDFEncryptionDict(COSDictionary dict) {
        this.dict = dict;
    }

    /** Filter name — must be "Standard" for standard handler. */
    public String getFilter() { return dict.getNameAsString("Filter"); }

    /** V: algorithm version (0-5). */
    public int getV() { return dict.getInt("V", 0); }

    /** R: security handler revision (2-6). */
    public int getR() { return dict.getInt("R", 0); }

    /** Key length in bits (40-256). */
    public int getLength() {
        int v = getV();
        if (v == 1) return 40;
        if (v == 5) return 256;
        return dict.getInt("Length", 40);
    }

    /** Key length in bytes. */
    public int getKeyLength() { return getLength() / 8; }

    /** O: owner password hash. */
    public byte[] getO() {
        COSBase o = dict.get("O");
        return (o instanceof COSString) ? ((COSString) o).getBytes() : new byte[0];
    }

    /** U: user password hash. */
    public byte[] getU() {
        COSBase u = dict.get("U");
        return (u instanceof COSString) ? ((COSString) u).getBytes() : new byte[0];
    }

    /** OE: owner encryption key (R=6, 32 bytes). */
    public byte[] getOE() {
        COSBase oe = dict.get("OE");
        return (oe instanceof COSString) ? ((COSString) oe).getBytes() : null;
    }

    /** UE: user encryption key (R=6, 32 bytes). */
    public byte[] getUE() {
        COSBase ue = dict.get("UE");
        return (ue instanceof COSString) ? ((COSString) ue).getBytes() : null;
    }

    /** Perms: encrypted permissions (R=6, 16 bytes). */
    public byte[] getPerms() {
        COSBase p = dict.get("Perms");
        return (p instanceof COSString) ? ((COSString) p).getBytes() : null;
    }

    /**
     * P: permission flags as a signed 32-bit integer.
     * <p>
     * Real-world PDFs often serialize {@code /P} as an unsigned decimal value
     * (for example {@code 4294963392}) even though the security algorithm
     * expects the low 32 bits interpreted as a signed integer. Reading via
     * {@code int} directly would overflow and break password authentication.
     * </p>
     */
    public int getP() {
        long raw = dict.getLong("P", 0L);
        return (int) raw;
    }

    /** EncryptMetadata: default true. */
    public boolean getEncryptMetadata() { return dict.getBoolean("EncryptMetadata", true); }

    /** StmF: crypt filter for streams (V=4+). */
    public String getStmF() {
        String stmF = dict.getNameAsString("StmF");
        return stmF != null ? stmF : "Identity";
    }

    /** StrF: crypt filter for strings (V=4+). */
    public String getStrF() {
        String strF = dict.getNameAsString("StrF");
        return strF != null ? strF : "Identity";
    }

    /** CF: crypt filter dictionary (V=4+). */
    public COSDictionary getCF() {
        COSBase cf = dict.get("CF");
        return (cf instanceof COSDictionary) ? (COSDictionary) cf : null;
    }

    /**
     * Determines the cipher type based on V and crypt filters.
     *
     * @return the cipher type to use
     */
    public CipherType getCipherType() {
        int v = getV();
        if (v <= 3) return CipherType.RC4;
        if (v == 4) {
            String stmF = getStmF();
            COSDictionary cf = getCF();
            if (cf != null && !"Identity".equals(stmF)) {
                COSBase filterObj = cf.get(COSName.of(stmF));
                if (filterObj instanceof COSDictionary) {
                    String cfm = ((COSDictionary) filterObj).getNameAsString("CFM");
                    if ("AESV2".equals(cfm)) return CipherType.AES_128;
                }
            }
            return CipherType.RC4;
        }
        return CipherType.AES_256;
    }

    /** Supported cipher types. */
    public enum CipherType { RC4, AES_128, AES_256 }

    // ── Write-side: static factory ──────────────────────────────────

    /**
     * Builds a new /Encrypt dictionary for the given algorithm and parameters.
     * <p>
     * Maps {@link CryptoAlgorithm} to the V/R/Length values defined in
     * ISO 32000-1:2008, Table 21 and §7.6.3.2.
     * </p>
     *
     * @param algorithm   the encryption algorithm
     * @param permissions the P flags (32-bit signed integer)
     * @param O           owner password hash (32 bytes for R2-R4, 48 bytes for R6)
     * @param U           user password hash (32 bytes for R2-R4, 48 bytes for R6)
     * @param OE          owner encryption key (32 bytes, R6 only — null for R2-R4)
     * @param UE          user encryption key (32 bytes, R6 only — null for R2-R4)
     * @param Perms       encrypted permissions (16 bytes, R6 only — null for R2-R4)
     * @return a fully populated PDFEncryptionDict
     */
    public static PDFEncryptionDict build(CryptoAlgorithm algorithm, int permissions,
                                           byte[] O, byte[] U,
                                           byte[] OE, byte[] UE, byte[] Perms) {
        COSDictionary d = new COSDictionary();
        d.set(COSName.of("Filter"), COSName.of("Standard"));

        int V, R, length;
        switch (algorithm) {
            case RC4x40:
                V = 1; R = 2; length = 40;
                break;
            case RC4x128:
                V = 2; R = 3; length = 128;
                break;
            case AESx128:
                V = 4; R = 4; length = 128;
                break;
            case AESx256:
                V = 5; R = 6; length = 256;
                break;
            default:
                throw new IllegalArgumentException("Unsupported algorithm: " + algorithm);
        }

        d.set(COSName.of("V"), COSInteger.valueOf(V));
        d.set(COSName.of("R"), COSInteger.valueOf(R));
        // Top-level /Length is required by Adobe Acrobat for V=5 per the
        // Adobe Supplement to ISO 32000 (PDF 1.7 Extension Level 3) — Acrobat
        // refuses to open the file with "the document cannot be decrypted"
        // when this entry is missing. ISO 32000-2 Annex K's example dict also
        // includes /Length 256 for V=5/R=6. (In PDF 2.0 it is marked
        // deprecated but still tolerated.) For V ∈ {1, 2, 4} the entry is
        // also defined by ISO 32000-1 Table 20.
        d.set(COSName.of("Length"), COSInteger.valueOf(length));
        d.set(COSName.of("P"), COSInteger.valueOf(permissions));
        d.set(COSName.of("O"), new COSString(O));
        d.set(COSName.of("U"), new COSString(U));

        // V=4 and V=5: add crypt filter dictionaries (§7.6.3.2.3, Table 25)
        if (V >= 4) {
            d.set(COSName.of("StmF"), COSName.of("StdCF"));
            d.set(COSName.of("StrF"), COSName.of("StdCF"));
            // /EncryptMetadata default is true but Adobe Acrobat is strict
            // about its presence on the encrypt dict for V≥4. Set it
            // explicitly to keep Perms[8]='T' (set by encrypt()) consistent.
            d.set(COSName.of("EncryptMetadata"), org.aspose.pdf.engine.cos.COSBoolean.TRUE);

            String cfm = (V == 4) ? "AESV2" : "AESV3";
            int cfLen = (V == 4) ? 16 : 32;

            COSDictionary stdCF = new COSDictionary();
            stdCF.set(COSName.of("Type"), COSName.of("CryptFilter"));
            stdCF.set(COSName.of("CFM"), COSName.of(cfm));
            stdCF.set(COSName.of("AuthEvent"), COSName.of("DocOpen"));
            stdCF.set(COSName.of("Length"), COSInteger.valueOf(cfLen));

            COSDictionary cf = new COSDictionary();
            cf.set(COSName.of("StdCF"), stdCF);
            d.set(COSName.of("CF"), cf);
        }

        // R=6: add OE, UE, Perms (§7.6.3.3)
        if (R == 6) {
            if (OE != null) d.set(COSName.of("OE"), new COSString(OE));
            if (UE != null) d.set(COSName.of("UE"), new COSString(UE));
            if (Perms != null) d.set(COSName.of("Perms"), new COSString(Perms));
        }

        return new PDFEncryptionDict(d);
    }

    /** Returns the underlying dictionary. */
    public COSDictionary getCOSDictionary() { return dict; }
}
