package org.aspose.pdf.sdm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PART 1 gate: uuidV5 against RFC 4122 / python-uuid known-answer vectors
 * (exact), GUID determinism, canonical(sourceRef) freeze.
 */
public class SdmIdsTest {

    /**
     * Python docs example: uuid5(NAMESPACE_DNS, 'python.org').
     */
    @Test
    public void uuidV5MatchesPythonDnsVector() {
        assertEquals("886313e1-3b8a-5372-9b90-0c9aee199e5d",
                SdmIds.uuidV5(SdmIds.NAMESPACE_DNS, "python.org").toString());
    }

    /**
     * python: uuid5(NAMESPACE_URL, 'http://www.example.com/').
     */
    @Test
    public void uuidV5MatchesPythonUrlVector() {
        assertEquals("fcde3c85-2270-590f-9e7c-ee003d65e0e2",
                SdmIds.uuidV5(SdmIds.NAMESPACE_URL, "http://www.example.com/").toString());
    }

    /**
     * NS_PRODUCT is derived, not random — freeze it (changing it changes every
     * id of every document). Vector computed independently with python uuid5.
     */
    @Test
    public void nsProductIsFrozen() {
        assertEquals("d973ebf5-adb7-51c0-95d1-8a2fe550cef7", SdmIds.NS_PRODUCT.toString());
    }

    /**
     * Full pipeline vector computed independently in python:
     * nsDoc = uuid5(NS_PRODUCT, sha256(b'hello pdf')) then
     * nodeId = uuid5(nsDoc, 'pdf:content:12:34-56').
     */
    @Test
    public void nsDocAndNodeIdMatchPythonPipelineVector() {
        UUID nsDoc = SdmIds.nsDoc("hello pdf".getBytes(StandardCharsets.UTF_8));
        assertEquals("5da825d7-5257-535e-bdd0-cbd1f7d95fa5", nsDoc.toString());
        String nodeId = SdmIds.nodeId(nsDoc, new ContentRange(12, 34, 56));
        assertEquals("f5673195-736a-5c2d-a6ba-ab6c76989b8b", nodeId);
    }

    /** Version and variant bits are forced per RFC 4122 §4.3. */
    @Test
    public void versionAndVariantBitsAreCorrect() {
        UUID u = SdmIds.uuidV5(SdmIds.NAMESPACE_DNS, "anything at all");
        assertEquals(5, u.version());
        assertEquals(2, u.variant()); // IETF variant (10x)
    }

    /** Same file bytes + same sourceRef → same id, on repeated computation. */
    @Test
    public void guidIsDeterministicAcrossTwoReads() {
        byte[] file = "fake pdf file bytes".getBytes(StandardCharsets.UTF_8);
        SourceRef ref = new ContentRange(3, 0, 17);
        String first = SdmIds.nodeId(SdmIds.nsDoc(file), ref);
        String second = SdmIds.nodeId(SdmIds.nsDoc(file), new ContentRange(3, 0, 17));
        assertEquals(first, second);
    }

    /** Different sourceRef → different id; different file bytes → different id. */
    @Test
    public void guidDiffersForDifferentSourceOrFile() {
        byte[] file = "fake pdf file bytes".getBytes(StandardCharsets.UTF_8);
        UUID ns = SdmIds.nsDoc(file);
        String a = SdmIds.nodeId(ns, new ContentRange(3, 0, 17));
        String b = SdmIds.nodeId(ns, new ContentRange(3, 0, 18));
        String c = SdmIds.nodeId(ns, new ObjectRef(3, 0));
        assertNotEquals(a, b);
        assertNotEquals(a, c);
        String otherFile = SdmIds.nodeId(
                SdmIds.nsDoc("other bytes".getBytes(StandardCharsets.UTF_8)),
                new ContentRange(3, 0, 17));
        assertNotEquals(a, otherFile);
    }

    /** The canonical string formats are FROZEN (IR spec Part 5 open item #1). */
    @Test
    public void canonicalFormatsAreFrozen() {
        assertEquals("pdf:content:12:34-56", new ContentRange(12, 34, 56).canonical());
        assertEquals("pdf:object:7/0", new ObjectRef(7, 0).canonical());
    }

    /** Session-created nodes get deterministic per-session ids. */
    @Test
    public void sessionNodeIdsAreDeterministicAndDistinct() {
        UUID ns = SdmIds.nsDoc("f".getBytes(StandardCharsets.UTF_8));
        String s1 = SdmIds.sessionNodeId(ns, "sess1", 1);
        String s1again = SdmIds.sessionNodeId(ns, "sess1", 1);
        String s2 = SdmIds.sessionNodeId(ns, "sess1", 2);
        assertEquals(s1, s1again);
        assertNotEquals(s1, s2);
    }

    /** Invalid inputs are rejected with clear errors. */
    @Test
    public void invalidArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ContentRange(1, -1, 5));
        assertThrows(IllegalArgumentException.class, () -> new ContentRange(1, 5, 4));
        assertThrows(IllegalArgumentException.class, () -> new ObjectRef(0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SdmIds.nodeId(SdmIds.NS_PRODUCT, null));
    }
}
