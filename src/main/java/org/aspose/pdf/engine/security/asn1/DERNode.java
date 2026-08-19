package org.aspose.pdf.engine.security.asn1;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a parsed ASN.1 DER (Distinguished Encoding Rules) node.
 * <p>
 * Each DER value is a TLV (Tag-Length-Value) triple. Constructed types
 * (SEQUENCE, SET, context tags) contain child nodes.
 * </p>
 */
public class DERNode {

    private final int tag;
    private final byte[] value;
    private final List<DERNode> children;
    private final boolean constructed;

    private DERNode(int tag, byte[] value, List<DERNode> children, boolean constructed) {
        this.tag = tag;
        this.value = value;
        this.children = children != null ? children : Collections.emptyList();
        this.constructed = constructed;
    }

    /**
     * Parses a DER-encoded byte array into a node tree.
     *
     * @param data the DER bytes
     * @return the root node
     * @throws IOException if parsing fails
     */
    public static DERNode parse(byte[] data) throws IOException {
        return parse(data, 0, data.length);
    }

    /**
     * Parses starting at offset, consuming up to limit.
     * Returns a single node. Use parseAll for multiple.
     */
    public static DERNode parse(byte[] data, int offset, int limit) throws IOException {
        if (offset >= limit) throw new IOException("DER: unexpected end of data at offset " + offset);
        int[] pos = {offset};
        return parseNode(data, pos, limit);
    }

    /**
     * Parses all sequential nodes between offset and limit.
     */
    public static List<DERNode> parseAll(byte[] data, int offset, int limit) throws IOException {
        List<DERNode> nodes = new ArrayList<>();
        int[] pos = {offset};
        while (pos[0] < limit) {
            nodes.add(parseNode(data, pos, limit));
        }
        return nodes;
    }

    private static DERNode parseNode(byte[] data, int[] pos, int limit) throws IOException {
        if (pos[0] >= limit) throw new IOException("DER: unexpected end at " + pos[0]);

        int tag = data[pos[0]++] & 0xFF;
        boolean isConstructed = (tag & 0x20) != 0;

        // Parse length (-1 = BER indefinite form, terminated by an end-of-contents octet pair)
        int length = parseLength(data, pos, limit);
        if (length == -1) {
            // BER indefinite length (CMS producers such as OpenSSL emit `30 80 …`
            // SignedData). Only legal on constructed types: parse children until
            // the 0x00 0x00 end-of-contents marker (ITU-T X.690 §8.1.3.6).
            if (!isConstructed) {
                throw new IOException("BER: indefinite length on a primitive type at " + pos[0]);
            }
            int start = pos[0];
            List<DERNode> children = new ArrayList<>();
            while (true) {
                if (pos[0] + 1 >= limit) {
                    throw new IOException("BER: missing end-of-contents for indefinite length");
                }
                if (data[pos[0]] == 0 && data[pos[0] + 1] == 0) {
                    break;
                }
                children.add(parseNode(data, pos, limit));
            }
            int end = pos[0];
            pos[0] += 2; // consume end-of-contents
            byte[] value = new byte[end - start];
            System.arraycopy(data, start, value, 0, end - start);
            return new DERNode(tag, value, children, true);
        }
        if (pos[0] + length > limit) {
            throw new IOException("DER: length " + length + " exceeds data at offset " + pos[0]);
        }

        byte[] value = new byte[length];
        System.arraycopy(data, pos[0], value, 0, length);

        List<DERNode> children = null;
        if (isConstructed && length > 0) {
            children = parseAll(data, pos[0], pos[0] + length);
        }

        pos[0] += length;
        return new DERNode(tag, value, children, isConstructed);
    }

    private static int parseLength(byte[] data, int[] pos, int limit) throws IOException {
        if (pos[0] >= limit) throw new IOException("DER: missing length byte");
        int first = data[pos[0]++] & 0xFF;
        if (first < 0x80) return first;
        int numBytes = first & 0x7F;
        if (numBytes == 0) return -1; // BER indefinite length
        if (numBytes > 4) throw new IOException("DER: invalid length encoding: " + numBytes);
        int length = 0;
        for (int i = 0; i < numBytes; i++) {
            if (pos[0] >= limit) throw new IOException("DER: truncated length");
            length = (length << 8) | (data[pos[0]++] & 0xFF);
        }
        return length;
    }

    // ── Accessors ──

    /** Returns the tag byte. */
    public int getTag() { return tag; }

    /** Returns the raw value bytes. */
    public byte[] getValue() { return value; }

    /** Returns child nodes (for constructed types). */
    public List<DERNode> getChildren() { return children; }

    /** Returns true if this is a constructed type. */
    public boolean isConstructed() { return constructed; }

    /** Returns the child at the given index. */
    public DERNode getChild(int index) {
        return children.get(index);
    }

    /** Returns the number of children. */
    public int getChildCount() { return children.size(); }

    // ── Type checks ──

    public boolean isSequence() { return tag == 0x30; }
    public boolean isSet() { return tag == 0x31; }
    public boolean isOctetString() { return tag == 0x04; }
    public boolean isInteger() { return tag == 0x02; }
    public boolean isOID() { return tag == 0x06; }
    public boolean isBitString() { return tag == 0x03; }
    public boolean isNull() { return tag == 0x05; }
    public boolean isContextTag(int n) { return tag == (0xA0 | n); }

    // ── Value extraction ──

    /** Extracts an INTEGER value. */
    public BigInteger getInteger() {
        return new BigInteger(value);
    }

    /** Decodes an OID to dotted string. */
    public String getOID() {
        if (value.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int first = value[0] & 0xFF;
        sb.append(first / 40).append('.').append(first % 40);
        long acc = 0;
        for (int i = 1; i < value.length; i++) {
            int b = value[i] & 0xFF;
            acc = (acc << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) {
                sb.append('.').append(acc);
                acc = 0;
            }
        }
        return sb.toString();
    }

    /** Extracts a string value (PrintableString, UTF8String, IA5String, etc.). */
    public String getString() {
        if (tag == 0x0C) return new String(value, StandardCharsets.UTF_8);
        return new String(value, StandardCharsets.ISO_8859_1);
    }

    /** Returns the total DER-encoded size of this node. */
    public int getEncodedSize() {
        return 1 + DEREncoder.encodedLengthSize(value.length) + value.length;
    }

    @Override
    public String toString() {
        return String.format("DERNode{tag=0x%02X, len=%d, children=%d}", tag, value.length, children.size());
    }
}
