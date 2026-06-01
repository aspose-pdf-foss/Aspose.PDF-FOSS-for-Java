package org.aspose.pdf.engine.security;

import org.aspose.pdf.security.ICustomSecurityHandler;

import java.util.logging.Logger;

/**
 * Encrypts individual PDF objects (strings and streams).
 * <p>
 * Mirror of {@link PDFDecryptor}: uses {@link PDFCryptoUtils#computeObjectKey}
 * for Algorithm 1 (§7.6.2) per-object key computation, then encrypts with
 * the appropriate cipher (RC4 or AES-CBC).
 * </p>
 * <p>
 * Per ISO 32000-1:2008 §7.6.2, encryption is NOT applied to:
 * <ul>
 *   <li>The /Encrypt dictionary itself</li>
 *   <li>Object numbers, generation numbers</li>
 *   <li>Cross-reference table/stream entries</li>
 *   <li>The file trailer</li>
 * </ul>
 * The caller (PDFWriter) is responsible for excluding these objects.
 * </p>
 */
public class PDFEncryptor {

    private static final Logger LOG = Logger.getLogger(PDFEncryptor.class.getName());

    private final byte[] encryptionKey;
    private final PDFEncryptionDict.CipherType cipherType;
    private final int revision;
    private final ICustomSecurityHandler customHandler;

    /**
     * Creates an encryptor with the given key and encryption parameters.
     *
     * @param encryptionKey the file encryption key
     * @param encDict       the encryption dictionary
     */
    public PDFEncryptor(byte[] encryptionKey, PDFEncryptionDict encDict) {
        this.encryptionKey = encryptionKey;
        this.cipherType = encDict.getCipherType();
        this.revision = encDict.getR();
        this.customHandler = null;
    }

    /**
     * Creates an encryptor backed by a custom security handler.
     *
     * @param encryptionKey file encryption key
     * @param encDict       encryption dictionary
     * @param customHandler custom handler implementation
     */
    public PDFEncryptor(byte[] encryptionKey, PDFEncryptionDict encDict, ICustomSecurityHandler customHandler) {
        this.encryptionKey = encryptionKey;
        this.cipherType = encDict.getCipherType();
        this.revision = encDict.getR();
        this.customHandler = customHandler;
    }

    /**
     * Encrypts data belonging to a specific PDF object.
     *
     * @param data             the plaintext bytes
     * @param objectNumber     the object number
     * @param generationNumber the generation number
     * @return the encrypted bytes
     */
    public byte[] encrypt(byte[] data, int objectNumber, int generationNumber) {
        if (data == null) return null;
        // Empty input under AES still produces 32 bytes (IV + one PKCS#7 pad
        // block); under RC4 it stays empty. The dispatch below handles both.
        try {
            if (customHandler != null) {
                return customHandler.encrypt(data, objectNumber, generationNumber, encryptionKey);
            }
            if (revision >= 5) {
                // R=5/R=6: AES-256 with file key directly
                return AESCipher.encrypt(encryptionKey, data);
            }
            byte[] objectKey = PDFCryptoUtils.computeObjectKey(
                    encryptionKey, objectNumber, generationNumber, cipherType);
            switch (cipherType) {
                case RC4:
                    if (data.length == 0) return data;
                    return RC4Cipher.process(objectKey, data);
                case AES_128:
                    return AESCipher.encrypt(objectKey, data);
                default:
                    return data;
            }
        } catch (Exception e) {
            LOG.fine(() -> "Encryption failed for obj " + objectNumber + ": " + e.getMessage());
            return data;
        }
    }

    /** Returns true if the encryptor has a valid key. */
    public boolean isActive() { return encryptionKey != null; }

    /**
     * Returns the custom handler used by this encryptor, if any.
     *
     * @return custom handler or {@code null}
     */
    public ICustomSecurityHandler getCustomHandler() {
        return customHandler;
    }
}
