package org.aspose.pdf.sdm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Deterministic GUID minting for SDM/PGM nodes — RFC 4122 name-based UUIDs
 * (version 5, SHA-1), implemented in pure Java (no external libraries).
 * <p>
 * Scheme (IR spec §0.3):
 * <ul>
 *   <li>{@code nsDoc = uuidV5(NS_PRODUCT, sha256(fileBytes))} — the document
 *       namespace is determined by content: two openings of the same file
 *       yield the same namespace;</li>
 *   <li>{@code nodeId = uuidV5(nsDoc, canonical(sourceRef))} — assigned ONCE
 *       at first construction. Ids are then VALUES living with nodes: edits
 *       never recompute them (v5 is a way to mint a reproducible id, not to
 *       maintain it);</li>
 *   <li>session-created nodes (no sourceRef):
 *       {@code uuidV5(nsDoc, "session:{sid}/seq:{n}")}.</li>
 * </ul>
 * </p>
 */
public final class SdmIds {

    /** RFC 4122 Appendix C DNS namespace (used only to derive NS_PRODUCT). */
    public static final UUID NAMESPACE_DNS =
            UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    /** RFC 4122 Appendix C URL namespace (exposed for known-answer tests). */
    public static final UUID NAMESPACE_URL =
            UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    /**
     * Product namespace for all IR document namespaces. Derived (not a magic
     * literal) as {@code uuidV5(NAMESPACE_DNS, "ir.foss.pdf.aspose.org")};
     * frozen by test — changing it changes every id of every document.
     */
    public static final UUID NS_PRODUCT = uuidV5(NAMESPACE_DNS, "ir.foss.pdf.aspose.org");

    private SdmIds() {
    }

    /**
     * Computes an RFC 4122 version-5 (name-based, SHA-1) UUID.
     *
     * @param namespace the namespace UUID
     * @param name      the name within the namespace
     * @return the version-5 UUID
     */
    public static UUID uuidV5(UUID namespace, String name) {
        return uuidV5(namespace, name.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Computes an RFC 4122 version-5 (name-based, SHA-1) UUID from raw name bytes.
     *
     * @param namespace the namespace UUID
     * @param nameBytes the name bytes
     * @return the version-5 UUID
     */
    public static UUID uuidV5(UUID namespace, byte[] nameBytes) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
        sha1.update(toBytes(namespace));
        sha1.update(nameBytes);
        byte[] hash = sha1.digest();
        // First 16 bytes of the hash, with version (5) and variant (10x) bits
        // forced per RFC 4122 §4.3.
        hash[6] = (byte) ((hash[6] & 0x0F) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (hash[i] & 0xFF);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (hash[i] & 0xFF);
        }
        return new UUID(msb, lsb);
    }

    /**
     * Computes the document namespace: {@code uuidV5(NS_PRODUCT, sha256(fileBytes))}.
     * The sha256 digest is fed as raw bytes (not hex), frozen by determinism tests.
     *
     * @param fileBytes the raw bytes of the source file
     * @return the document namespace UUID
     */
    public static UUID nsDoc(byte[] fileBytes) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        return uuidV5(NS_PRODUCT, sha256.digest(fileBytes));
    }

    /**
     * Mints the id of a node born from a source location.
     *
     * @param nsDoc     the document namespace (see {@link #nsDoc})
     * @param sourceRef the node's provenance
     * @return the node GUID as a lowercase UUID string
     */
    public static String nodeId(UUID nsDoc, SourceRef sourceRef) {
        if (sourceRef == null) {
            throw new IllegalArgumentException("sourceRef must not be null; "
                    + "use sessionNodeId for nodes created by editing");
        }
        return uuidV5(nsDoc, sourceRef.canonical()).toString();
    }

    /**
     * Mints the id of a node created during an editing session (no sourceRef).
     *
     * @param nsDoc     the document namespace
     * @param sessionId the session identifier
     * @param seq       the per-session sequence number
     * @return the node GUID as a lowercase UUID string
     */
    public static String sessionNodeId(UUID nsDoc, String sessionId, long seq) {
        return uuidV5(nsDoc, "session:" + sessionId + "/seq:" + seq).toString();
    }

    /** Serializes a UUID to its 16 network-order bytes (RFC 4122 §4.1.2). */
    private static byte[] toBytes(UUID uuid) {
        byte[] out = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (msb >>> (8 * (7 - i)));
            out[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }
        return out;
    }
}
