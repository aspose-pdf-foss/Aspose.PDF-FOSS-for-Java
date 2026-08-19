package org.aspose.pdf.engine.writer;

import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectKey;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.pdfobjects.PdfString;
import org.aspose.pdf.engine.filter.FlateFilter;
import org.aspose.pdf.engine.io.RandomAccessReader;
import org.aspose.pdf.engine.parser.XRefParser;
import org.aspose.pdf.engine.security.PDFEncryptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serializes a graph of PDF objects into a valid PDF file.
 * Writes the header, body (all indirect objects), cross-reference table, and trailer,
 * conforming to ISO 32000-1:2008, §7.5.
 *
 * <p>Also supports incremental updates (appending modified objects to an existing PDF).</p>
 */
public final class PDFWriter {

    private static final Logger LOGGER = Logger.getLogger(PDFWriter.class.getName());

    /** Binary hint bytes after the header, per §7.5.2.
     *  Four bytes with high bit set to indicate binary content. */
    private static final byte[] BINARY_HINT = {(byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3};

    private final OutputStream output;
    private final float pdfVersion;

    /** Tracks byte offsets of each object for the xref table. */
    private final Map<PdfObjectKey, Long> objectOffsets = new LinkedHashMap<>();

    /** Current byte position in the output. */
    private long currentOffset = 0;

    /** Counter for assigning object numbers to new objects. */
    private int nextObjectNumber = 1;

    /**
     * Lower bound for newly-allocated object numbers. In an incremental update the
     * original objects are preserved verbatim in the copied file prefix but are
     * absent from the modified-objects map, so {@link #getMaxObjectNumber(Map)}
     * under-counts. Without this floor a freshly-created object (e.g. a redaction
     * content stream) could reuse a still-live original number — in particular an
     * {@code /ObjStm} that backs compressed objects — leaving dangling type-2
     * references that fail to load on reopen ("Invalid index 0 in object stream N").
     * Set to the original {@code /Size} before registering orphan streams; 0 for
     * full (non-incremental) writes, where the objects map is complete.
     */
    private int objectNumberFloor = 0;

    /**
     * When true, {@link #write} emits the hybrid-reference layout
     * (ISO 32000-1 §7.5.8.4): a cross-reference stream object followed by a
     * classic xref table whose trailer points at the stream via
     * {@code /XRefStm}. Set by callers whose source document was hybrid.
     */
    private boolean preserveHybridXRef;

    /** Encryptor for write-side encryption (null when not encrypting). */
    private PDFEncryptor encryptor;

    /** Object key of the /Encrypt dictionary — excluded from encryption per ISO 32000 §7.6.1. */
    private PdfObjectKey encryptDictKey;

    /**
     * Sets the encryptor for write-side encryption.
     * When set, all objects except the /Encrypt dictionary and XRef streams
     * will have their strings and stream data encrypted.
     *
     * @param encryptor      the encryptor (null to disable encryption)
     * @param encryptDictKey the object key of the /Encrypt dictionary (excluded from encryption)
     */
    public void setEncryptor(PDFEncryptor encryptor, PdfObjectKey encryptDictKey) {
        this.encryptor = encryptor;
        this.encryptDictKey = encryptDictKey;
    }

    /**
     * Requests the hybrid-reference layout (§7.5.8.4) from {@link #write}:
     * classic xref table + parallel cross-reference stream, the table
     * trailer carrying {@code /XRefStm}. Used to preserve the file shape of
     * hybrid source documents on full rewrite.
     *
     * @param preserve {@code true} to emit the hybrid layout
     */
    public void setPreserveHybridXRef(boolean preserve) {
        this.preserveHybridXRef = preserve;
    }

    /**
     * Constructs a new PDFWriter.
     *
     * @param output     the output stream to write the PDF to
     * @param pdfVersion the PDF version (e.g. 1.7f)
     */
    public PDFWriter(OutputStream output, float pdfVersion) {
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        // Buffer raw file/socket streams: the writer emits many small chunks
        // (xref entries are 20 bytes each), and one syscall per chunk made
        // large-document saves I/O-bound (PDFNET_48386: minutes in
        // writeXRefTable). All write modes flush() before returning, so
        // wrapping is transparent to callers.
        this.output = (output instanceof java.io.BufferedOutputStream
                || output instanceof java.io.ByteArrayOutputStream)
                ? output
                : new java.io.BufferedOutputStream(output, 1 << 18);
        this.pdfVersion = pdfVersion;
    }

    /**
     * Writes a complete PDF file: header, objects, xref table, and trailer.
     *
     * @param trailer the trailer dictionary (must contain /Root at minimum)
     * @param objects the map of object keys to PDF objects
     * @throws IOException if writing fails
     */
    public void write(PdfDictionary trailer, Map<PdfObjectKey, PdfBase> objects) throws IOException {
        LOGGER.log(Level.FINE, "Writing PDF {0} with {1} objects", new Object[]{pdfVersion, objects.size()});

        // 0. Promote any in-graph PdfStream that lacks an object key to an
        //    indirect object — per ISO 32000-1:2008 §7.3.8.1 streams MUST be
        //    indirect objects. (Bug N2: without this pass the writer emitted
        //    inline `<<…>> stream…endstream` constructs inside parent dicts,
        //    which strict readers reject.)
        registerOrphanStreams(objects, trailer);

        // 1. Write header
        writeHeader();

        // 2. Write objects sorted by object number
        List<Map.Entry<PdfObjectKey, PdfBase>> sorted = new ArrayList<>(objects.entrySet());
        sorted.sort(Comparator.comparingInt(e -> e.getKey().getObjectNumber()));

        for (Map.Entry<PdfObjectKey, PdfBase> entry : sorted) {
            writeObject(entry.getKey(), entry.getValue());
        }

        // 3./4. Cross-reference section + trailer.
        //
        // Hybrid-reference sources (ISO 32000-1 §7.5.8.4): when the original
        // trailer carries /XRefStm, the file was written for both PDF 1.4-
        // and 1.5-era readers — a classic xref table whose trailer points at
        // a parallel cross-reference stream. Aspose preserves that shape on
        // full rewrite (PDFNET-45599), so we emit: xref stream object (no
        // startxref) → classic table → table trailer with /XRefStm.
        boolean hybrid = preserveHybridXRef || trailer.get("XRefStm") != null;
        if (hybrid) {
            int sizeWithoutXRefStm = getMaxObjectNumber(objects) + 1;
            long xrefStmOffset = writeXRefStreamObject(trailer, sizeWithoutXRefStm, null, false);
            long xrefOffset = currentOffset;
            writeXRefTable(objects);
            PdfDictionary finalTrailer = copyDictionary(trailer);
            finalTrailer.set(PdfName.PREV, null);
            // /Size covers the xref stream object too (it takes number sizeWithoutXRefStm).
            writeTrailer(finalTrailer, sizeWithoutXRefStm + 1, xrefOffset, xrefStmOffset);
        } else {
            long xrefOffset = currentOffset;
            writeXRefTable(objects);
            PdfDictionary finalTrailer = copyDictionary(trailer);
            finalTrailer.set(PdfName.PREV, null);
            finalTrailer.set(PdfName.of("XRefStm"), null);
            writeTrailer(finalTrailer, getMaxObjectNumber(objects) + 1, xrefOffset);
        }

        output.flush();
        LOGGER.log(Level.FINE, "PDF written: {0} bytes", currentOffset);
    }

    /**
     * Writes an incremental update to an existing PDF.
     * Copies the original file content, then appends modified objects,
     * a new xref table, and a new trailer with /Prev pointing to the old xref.
     *
     * @param original        reader for the original PDF file
     * @param trailer         the new trailer dictionary
     * @param modifiedObjects the modified/new objects to append
     * @throws IOException if writing fails
     */
    public void writeIncremental(RandomAccessReader original,
                                  PdfDictionary trailer,
                                  Map<PdfObjectKey, PdfBase> modifiedObjects) throws IOException {
        LOGGER.log(Level.FINE, "Writing incremental update with {0} modified objects", modifiedObjects.size());

        // Copy original file content
        original.seek(0);
        long originalLength = original.getLength();
        byte[] buffer = new byte[8192];
        long remaining = originalLength;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int read = original.read(buffer, 0, toRead);
            if (read <= 0) break;
            writeBytes(buffer, 0, read);
            remaining -= read;
        }

        // Find the old xref offset from the original file (we need it for /Prev)
        long oldXrefOffset = findOldXrefOffset(original);

        // Floor new object numbers at the original /Size so freshly-created
        // objects never reuse a preserved original number (the original objects
        // live in the copied prefix but are absent from modifiedObjects).
        objectNumberFloor = trailerSize(trailer);

        // Promote orphan streams in the modified set to indirect objects
        // before writing (Bug N2 — see write(...) above for rationale).
        registerOrphanStreams(modifiedObjects, trailer);

        // Write modified objects
        for (Map.Entry<PdfObjectKey, PdfBase> entry : modifiedObjects.entrySet()) {
            writeObject(entry.getKey(), entry.getValue());
        }

        // Write new xref table (subsections only for modified objects)
        long newXrefOffset = currentOffset;
        writeIncrementalXRefTable();

        // Compute /Size: max across all revisions (original + modified)
        PdfBase origSize = trailer.get("Size");
        int originalSize = (origSize instanceof PdfInteger) ? ((PdfInteger) origSize).intValue() : 0;
        int newMaxObj = getMaxObjectNumber(modifiedObjects);
        int newSize = Math.max(originalSize, newMaxObj + 1);

        // Write new trailer with /Prev. (writeTrailer strips any stream-only keys the source's
        // cross-reference-stream dictionary may carry, keeping this table trailer valid.)
        PdfDictionary newTrailer = copyDictionary(trailer);
        newTrailer.set(PdfName.of("Prev"), PdfInteger.valueOf(oldXrefOffset));
        writeTrailer(newTrailer, newSize, newXrefOffset);

        output.flush();
        LOGGER.log(Level.FINE, "Incremental update written: {0} bytes total", currentOffset);
    }

    /**
     * Assigns a new object number and returns the key.
     * Useful when adding new objects that don't yet have a key.
     *
     * @return a new PdfObjectKey with the next available object number
     */
    public PdfObjectKey allocateObjectNumber() {
        return new PdfObjectKey(nextObjectNumber++, 0);
    }

    // ========== Private implementation methods ==========

    /**
     * Walks the object graph rooted at {@code objects.values()} (and the
     * trailer) and ensures every {@link PdfStream} reachable from it has an
     * object key and is registered in {@code objects}. After this pass
     * {@link PdfDictionary#writeTo} sees an object key on every stream and
     * emits a reference ({@code N G R}) rather than serialising the stream
     * inline — which would violate ISO 32000-1:2008 §7.3.8.1 ("All streams
     * shall be indirect objects").
     *
     * <p>Three cases are handled:</p>
     * <ul>
     *   <li><strong>Inline orphan</strong> — {@code PdfStream} with no
     *       object key. Assigned a fresh key and added to {@code objects}.</li>
     *   <li><strong>Stale reference</strong> — {@code PdfObjectReference}
     *       whose target is a {@code PdfStream} with a key from a previous
     *       save, but the target is missing from the current {@code objects}
     *       map. The target is re-registered under the reference's key (if
     *       free) or under a fresh key (in which case the parent slot is
     *       rewritten to point at the new key). Surfaces on the second save
     *       of any {@code Document} that contains annotation appearance
     *       streams ({@code /AP /N}) or imported content streams — those
     *       sit behind {@code PdfObjectReference} after the first save.</li>
     *   <li><strong>Active reference</strong> — target stream is already
     *       in {@code objects}; no-op but descend into the target so nested
     *       streams (e.g. a Form XObject's {@code /Resources}) get walked.</li>
     * </ul>
     */
    private void registerOrphanStreams(Map<PdfObjectKey, PdfBase> objects,
                                       PdfDictionary trailer) {
        java.util.Set<PdfBase> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        // O(1) allocation + reverse lookup for the whole registration phase.
        // Without these, nextFreeObjectNumber() rescans the objects map for
        // every orphan and re-registration does a linear identity search —
        // quadratic on documents with tens of thousands of objects
        // (PDFNET_48386 hung the writer for minutes).
        allocCursor = getMaxObjectNumber(objects);
        reverseIndex = new java.util.IdentityHashMap<>(objects.size() * 2);
        for (Map.Entry<PdfObjectKey, PdfBase> e : objects.entrySet()) {
            reverseIndex.putIfAbsent(e.getValue(), e.getKey());
        }
        try {
            // Visit every existing indirect object so its key marks the stream as
            // already registered (and so we walk its children).
            for (PdfBase root : new java.util.ArrayList<>(objects.values())) {
                collectOrphanStreams(root, visited, objects);
            }
            if (trailer != null) {
                collectOrphanStreams(trailer, visited, objects);
            }
        } finally {
            allocCursor = -1;
            reverseIndex = null;
        }
    }

    /** Monotonic allocation cursor for the orphan-registration phase; -1 outside it. */
    private int allocCursor = -1;

    /** Identity index (object → its key) for the orphan-registration phase. */
    private java.util.IdentityHashMap<PdfBase, PdfObjectKey> reverseIndex;

    /** Records that {@code obj} is now registered under {@code key} in the phase indexes. */
    private void indexRegistered(PdfBase obj, PdfObjectKey key) {
        if (reverseIndex != null) {
            reverseIndex.put(obj, key);
        }
        if (allocCursor >= 0 && key.getObjectNumber() > allocCursor) {
            allocCursor = key.getObjectNumber();
        }
    }

    /**
     * Registers a stream that is the target of a reference into
     * {@code objects}, returning the key the reference should point at.
     * If the reference's original key is free, the stream is registered
     * under it. If the key collides with a different object, a fresh key
     * is allocated and the caller is expected to rewrite the parent slot
     * to use the new key.
     */
    private PdfObjectKey registerStreamUnderRefKey(PdfStream s,
                                                    PdfObjectKey refKey,
                                                    Map<PdfObjectKey, PdfBase> objects) {
        PdfBase existing = objects.get(refKey);
        if (existing == s) {
            return refKey;  // already registered, nothing to do
        }
        if (existing == null) {
            s.setObjectKey(refKey);
            objects.put(refKey, s);
            indexRegistered(s, refKey);
            return refKey;
        }
        // Collision: another object owns refKey. Allocate fresh.
        int next = nextFreeObjectNumber(objects);
        PdfObjectKey fresh = new PdfObjectKey(next, 0);
        s.setObjectKey(fresh);
        objects.put(fresh, s);
        indexRegistered(s, fresh);
        return fresh;
    }

    /**
     * Walks {@code node}, registering any orphan / stale streams and
     * reference-target dictionaries it encounters per the contract on
     * {@link #registerOrphanStreams(Map, PdfDictionary)}. Iterative with an
     * explicit work stack: the object graph of a large document (thousands of
     * pages) is deeper than the JVM call stack allows (StackOverflowError in
     * PDFNET_40631 with recursive descent).
     */
    private void collectOrphanStreams(PdfBase root,
                                      java.util.Set<PdfBase> visited,
                                      Map<PdfObjectKey, PdfBase> objects) {
        java.util.ArrayDeque<PdfBase> work = new java.util.ArrayDeque<>();
        if (root != null) {
            work.push(root);
        }
        while (!work.isEmpty()) {
            PdfBase node = work.pop();
            if (node == null || !visited.add(node)) continue;

            if (node instanceof PdfStream) {
                PdfStream s = (PdfStream) node;
                PdfObjectKey existing = s.getObjectKey();
                if (existing == null) {
                    // Inline orphan — assign fresh key.
                    int next = nextFreeObjectNumber(objects);
                    PdfObjectKey fresh = new PdfObjectKey(next, 0);
                    s.setObjectKey(fresh);
                    objects.put(fresh, s);
                    indexRegistered(s, fresh);
                } else if (objects.get(existing) != s) {
                    // Has a key from a previous save / import but not in the
                    // current objects map. Try to register under the same key,
                    // re-key on collision.
                    registerStreamUnderRefKey(s, existing, objects);
                }
                // fall through to descend into the stream's dict entries
            }

            if (node instanceof PdfDictionary) {
                // Snapshot keys because we may rewrite entries when a reference
                // collides and forces a re-key.
                java.util.List<PdfName> keys =
                        new java.util.ArrayList<>(((PdfDictionary) node).keySet());
                for (PdfName k : keys) {
                    PdfBase value = ((PdfDictionary) node).get(k);
                    PdfBase descendInto = walkReferenceForReregistration(
                            value, objects,
                            newRef -> ((PdfDictionary) node).set(k, newRef));
                    work.push(descendInto != null ? descendInto : value);
                }
            } else if (node instanceof PdfArray) {
                PdfArray arr = (PdfArray) node;
                for (int i = 0; i < arr.size(); i++) {
                    final int idx = i;
                    PdfBase value = arr.get(i);
                    PdfBase descendInto = walkReferenceForReregistration(
                            value, objects, newRef -> arr.set(idx, newRef));
                    work.push(descendInto != null ? descendInto : value);
                }
            }
        }
    }

    /**
     * If {@code value} is a {@link PdfObjectReference} whose target is a
     * {@link PdfStream}, ensure the target is registered in {@code objects}
     * (possibly under a fresh key, in which case {@code slotSetter} is
     * invoked to rewrite the parent slot to point at the new key). Returns
     * the dereferenced target so the caller can continue walking into it,
     * or {@code null} when {@code value} is not a reference (caller falls
     * through to walking {@code value} directly).
     */
    private PdfBase walkReferenceForReregistration(PdfBase value,
                                                    Map<PdfObjectKey, PdfBase> objects,
                                                    java.util.function.Consumer<PdfObjectReference> slotSetter) {
        if (!(value instanceof PdfObjectReference)) return null;
        PdfObjectReference ref = (PdfObjectReference) value;
        PdfObjectKey refKey = ref.getKey();
        PdfBase target;
        try {
            target = ref.dereference();
        } catch (IOException | IllegalStateException e) {
            // No resolver attached (e.g. references built by test fixtures
            // or low-level callers that bypass the parser/writer wiring).
            // The reference will be emitted verbatim — that's acceptable
            // as long as the target it points at has already been
            // registered by some other code path.
            return null;
        }
        if (target instanceof PdfStream) {
            PdfStream s = (PdfStream) target;
            PdfObjectKey effectiveKey = registerStreamUnderRefKey(s, refKey, objects);
            if (!effectiveKey.equals(refKey)) {
                // Re-keyed due to collision — rewrite the slot so the
                // parent points at the new key.
                slotSetter.accept(new PdfObjectReference(effectiveKey, k -> objects.get(k)));
            }
        } else if (target instanceof PdfDictionary || target instanceof PdfArray) {
            // Orphan NON-stream reference target (e.g. a FontDescriptor
            // dictionary injected by the PDF/A converter with a hand-allocated
            // key). Same contract as streams: register under the reference's
            // key when free; on collision, reuse the key the identical object
            // is already registered under, else allocate fresh and rewrite the
            // parent slot. Parser-loaded objects are already in {@code objects}
            // under their own key (identity match) — this is a no-op for them.
            PdfBase existing = objects.get(refKey);
            if (existing == null) {
                objects.put(refKey, target);
                indexRegistered(target, refKey);
            } else if (existing != target) {
                // Identity lookup via the phase index (linear entrySet scan
                // here was quadratic on large documents).
                PdfObjectKey found = reverseIndex != null ? reverseIndex.get(target) : null;
                if (found == null) {
                    for (Map.Entry<PdfObjectKey, PdfBase> e : objects.entrySet()) {
                        if (e.getValue() == target) {
                            found = e.getKey();
                            break;
                        }
                    }
                }
                PdfObjectKey effectiveKey = found;
                if (effectiveKey == null) {
                    effectiveKey = new PdfObjectKey(nextFreeObjectNumber(objects), 0);
                    objects.put(effectiveKey, target);
                    indexRegistered(target, effectiveKey);
                }
                slotSetter.accept(new PdfObjectReference(effectiveKey, k -> objects.get(k)));
            }
        }
        return target;
    }

    /**
     * Writes the PDF header: %PDF-X.Y followed by a binary hint comment.
     */
    private void writeHeader() throws IOException {
        // Format version with one decimal place (always use '.' decimal separator per PDF spec)
        String versionStr = String.format(Locale.US, "%%PDF-%.1f\n", pdfVersion);
        writeBytes(versionStr.getBytes(StandardCharsets.US_ASCII));

        // Binary hint: %âãÏÓ
        writeBytes(new byte[]{'%'});
        writeBytes(BINARY_HINT);
        writeBytes(new byte[]{'\n'});
    }

    /**
     * Writes a single indirect object.
     * Format: "N G obj\n...content...\nendobj\n"
     * <p>
     * When an encryptor is active, strings and stream data are encrypted
     * per ISO 32000-1:2008 §7.6.2. The /Encrypt dictionary itself and
     * XRef streams are excluded from encryption.
     * </p>
     */
    private void writeObject(PdfObjectKey key, PdfBase object) throws IOException {
        objectOffsets.put(key, currentOffset);

        // "N G obj\n"
        String objHeader = key.getObjectNumber() + " " + key.getGenerationNumber() + " obj\n";
        writeBytes(objHeader.getBytes(StandardCharsets.US_ASCII));

        // Determine if this object should be encrypted
        boolean shouldEncrypt = encryptor != null && encryptor.isActive()
                && !key.equals(encryptDictKey)
                && !isXRefStream(object);

        if (shouldEncrypt) {
            writeEncryptedObject(key, object);
        } else {
            ByteArrayOutputStream objContent = new ByteArrayOutputStream();
            object.writeTo(objContent);
            writeBytes(objContent.toByteArray());
        }

        // "\nendobj\n"
        writeBytes("\nendobj\n".getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Returns true if the object is an XRef stream (/Type /XRef).
     * XRef streams are not encrypted per ISO 32000-1:2008 §7.6.1.
     */
    private boolean isXRefStream(PdfBase object) {
        if (object instanceof PdfDictionary) {
            String type = ((PdfDictionary) object).getNameAsString("Type");
            return "XRef".equals(type);
        }
        return false;
    }

    /**
     * Writes an object with encryption applied to strings and stream data.
     */
    private void writeEncryptedObject(PdfObjectKey key, PdfBase object) throws IOException {
        int objNum = key.getObjectNumber();
        int genNum = key.getGenerationNumber();

        if (object instanceof PdfStream) {
            writeEncryptedStream(key, (PdfStream) object);
        } else if (object instanceof PdfDictionary) {
            PdfDictionary copy = encryptDictionaryStrings((PdfDictionary) object, objNum, genNum);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            copy.writeTo(buf);
            writeBytes(buf.toByteArray());
        } else if (object instanceof PdfArray) {
            PdfArray copy = encryptArrayStrings((PdfArray) object, objNum, genNum);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            copy.writeTo(buf);
            writeBytes(buf.toByteArray());
        } else if (object instanceof PdfString) {
            byte[] encrypted = encryptor.encrypt(((PdfString) object).getBytes(), objNum, genNum);
            PdfString encStr = new PdfString(encrypted);
            encStr.setForceHex(true);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            encStr.writeTo(buf);
            writeBytes(buf.toByteArray());
        } else {
            // Non-encryptable types (integer, name, boolean, null)
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            object.writeTo(buf);
            writeBytes(buf.toByteArray());
        }
    }

    /**
     * Writes an encrypted PdfStream: compresses → encrypts → writes dict + encrypted data.
     * Per ISO 32000-1:2008 §7.6.2: stream data is encrypted AFTER filter encoding.
     * <p>
     * <b>Pass-through optimization for re-saved encrypted documents.</b> When a
     * stream was loaded from an encrypted source and has not been modified
     * ({@link PdfStream#hasActiveDecryptor()} is true and
     * {@link PdfStream#hasPendingDecodedData()} is false), its
     * {@code encodedData} is still the original ciphertext on disk. Re-encrypting
     * it would corrupt the bytes (RC4 is symmetric, so a second pass decrypts;
     * AES would yield different ciphertext that no longer matches the recorded
     * key). In that case we write the bytes as-is. Streams with pending decoded
     * data — i.e. content the caller modified through {@link PdfStream#setDecodedData(byte[])}
     * — are re-encoded through filters and then encrypted, as before.
     * </p>
     */
    private void writeEncryptedStream(PdfObjectKey key, PdfStream stream) throws IOException {
        int objNum = key.getObjectNumber();
        int genNum = key.getGenerationNumber();

        // Step 1: Get the encoded (compressed) data
        byte[] encoded = stream.prepareEncodedData();

        // Step 2: Encrypt unless this is unmodified ciphertext from the source PDF.
        byte[] outputData;
        if (stream.hasActiveDecryptor() && !stream.hasPendingDecodedData()) {
            // Original bytes from the encrypted source — already ciphertext under
            // the same key. Pass through unchanged (re-encryption with the same
            // key would corrupt them: RC4 is symmetric and would decrypt; AES
            // would yield a different ciphertext that no longer matches the
            // /Length recorded for round-tripping). PDFNEWNET-33376 reproduces
            // the corruption when this guard is missing.
            outputData = encoded;
        } else {
            outputData = encryptor.encrypt(encoded, objNum, genNum);
        }

        // Step 3: Build a plain dict copy with encrypted strings and updated /Length
        PdfDictionary dictCopy = encryptDictionaryStrings(stream, objNum, genNum);
        dictCopy.set(PdfName.LENGTH, PdfInteger.valueOf(outputData.length));

        // Step 4: Write dict
        ByteArrayOutputStream dictBuf = new ByteArrayOutputStream();
        dictCopy.writeTo(dictBuf);
        writeBytes(dictBuf.toByteArray());

        // Step 5: Write stream data (§7.3.8.1: CR+LF or LF after "stream")
        writeBytes("\nstream\r\n".getBytes(StandardCharsets.US_ASCII));
        writeBytes(outputData);
        writeBytes("\r\nendstream".getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Creates a shallow copy of a PdfDictionary with all PdfString values encrypted.
     * Recurses into inline (non-indirect) sub-dictionaries and arrays.
     */
    private PdfDictionary encryptDictionaryStrings(PdfDictionary dict, int objNum, int genNum) {
        PdfDictionary copy = new PdfDictionary();
        for (Map.Entry<PdfName, PdfBase> entry : dict) {
            copy.set(entry.getKey(), encryptValue(entry.getValue(), objNum, genNum));
        }
        return copy;
    }

    /**
     * Creates a shallow copy of a PdfArray with all PdfString values encrypted.
     */
    private PdfArray encryptArrayStrings(PdfArray array, int objNum, int genNum) {
        PdfArray copy = new PdfArray(array.size());
        for (int i = 0; i < array.size(); i++) {
            copy.add(encryptValue(array.get(i), objNum, genNum));
        }
        return copy;
    }

    /**
     * Encrypts a single PDF value if it's a string, or recurses into inline dicts/arrays.
     * Indirect references are returned as-is (their target objects are encrypted separately).
     */
    private PdfBase encryptValue(PdfBase value, int objNum, int genNum) {
        if (value == null) return null;

        // Indirect references: skip — the referenced object is encrypted when written separately
        if (value instanceof PdfObjectReference) return value;
        if (value.getObjectKey() != null && value.getObjectKey().getObjectNumber() > 0) return value;

        if (value instanceof PdfString) {
            byte[] encrypted = encryptor.encrypt(((PdfString) value).getBytes(), objNum, genNum);
            PdfString encStr = new PdfString(encrypted);
            encStr.setForceHex(true);
            return encStr;
        }
        if (value instanceof PdfDictionary && !(value instanceof PdfStream)) {
            return encryptDictionaryStrings((PdfDictionary) value, objNum, genNum);
        }
        if (value instanceof PdfArray) {
            return encryptArrayStrings((PdfArray) value, objNum, genNum);
        }
        // All other types (PdfName, PdfInteger, PdfReal, PdfBoolean, PdfNull) — pass through
        return value;
    }

    /**
     * Writes the cross-reference table.
     * Each entry is EXACTLY 20 bytes: "OOOOOOOOOO GGGGG n \n" (or "f" for free).
     * Per ISO 32000-1:2008 §7.5.4, the EOL marker is SP CR or SP LF —
     * NOT SP CR LF — the latter produces 21-byte entries and breaks readers
     * that index xref by absolute byte offset (Ghostscript, Adobe Reader,
     * mupdf).
     */
    private void writeXRefTable(Map<PdfObjectKey, PdfBase> objects) throws IOException {
        // Determine the range of object numbers
        int maxObjNum = getMaxObjectNumber(objects);
        int totalEntries = maxObjNum + 1; // includes entry 0

        writeBytes("xref\n".getBytes(StandardCharsets.US_ASCII));

        // One subsection: 0 to maxObjNum+1
        String subsectionHeader = "0 " + totalEntries + "\n";
        writeBytes(subsectionHeader.getBytes(StandardCharsets.US_ASCII));

        // Entry 0: free head entry
        // "0000000000 65535 f \n" — exactly 20 bytes
        writeBytes("0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));

        // Build lookup map: objectNumber → (offset, generation)
        Map<Integer, Map.Entry<Long, Integer>> objNumToInfo = new java.util.HashMap<>();
        for (Map.Entry<PdfObjectKey, Long> entry : objectOffsets.entrySet()) {
            objNumToInfo.put(entry.getKey().getObjectNumber(),
                    new java.util.AbstractMap.SimpleEntry<>(entry.getValue(), entry.getKey().getGenerationNumber()));
        }

        // Write entries for objects 1..maxObjNum
        for (int i = 1; i <= maxObjNum; i++) {
            Map.Entry<Long, Integer> info = objNumToInfo.get(i);
            if (info != null) {
                // In-use entry: "OOOOOOOOOO GGGGG n \n" — exactly 20 bytes
                String xrefEntry = String.format(Locale.ROOT, "%010d %05d n \n",
                        info.getKey(), info.getValue());
                writeBytes(xrefEntry.getBytes(StandardCharsets.US_ASCII));
            } else {
                // Free entry
                writeBytes("0000000000 00000 f \n".getBytes(StandardCharsets.US_ASCII));
            }
        }
    }

    /**
     * Writes an incremental xref table containing only the modified objects.
     * Uses subsections to avoid writing entries for unmodified objects.
     * ISO 32000-1:2008 §7.5.4: "Each cross-reference section shall contain one or more
     * cross-reference subsections."
     */
    private void writeIncrementalXRefTable() throws IOException {
        writeBytes("xref\n".getBytes(StandardCharsets.US_ASCII));

        // Sort by object number to form contiguous subsections
        List<PdfObjectKey> sortedKeys = new ArrayList<>(objectOffsets.keySet());
        sortedKeys.sort(Comparator.comparingInt(PdfObjectKey::getObjectNumber));

        if (sortedKeys.isEmpty()) return;

        // Group into contiguous subsections
        List<List<PdfObjectKey>> subsections = new ArrayList<>();
        List<PdfObjectKey> currentSubsection = new ArrayList<>();
        currentSubsection.add(sortedKeys.get(0));

        for (int i = 1; i < sortedKeys.size(); i++) {
            PdfObjectKey prev = sortedKeys.get(i - 1);
            PdfObjectKey curr = sortedKeys.get(i);
            if (curr.getObjectNumber() == prev.getObjectNumber() + 1) {
                currentSubsection.add(curr);
            } else {
                subsections.add(currentSubsection);
                currentSubsection = new ArrayList<>();
                currentSubsection.add(curr);
            }
        }
        subsections.add(currentSubsection);

        // Write each subsection
        for (List<PdfObjectKey> sub : subsections) {
            int startNum = sub.get(0).getObjectNumber();
            int count = sub.size();
            String subHeader = startNum + " " + count + "\n";
            writeBytes(subHeader.getBytes(StandardCharsets.US_ASCII));

            for (PdfObjectKey key : sub) {
                Long offset = objectOffsets.get(key);
                if (offset != null) {
                    // 20-byte entry — see comment on writeXRefTable for the
                    // ISO 32000-1:2008 §7.5.4 rationale.
                    String entry = String.format(Locale.ROOT, "%010d %05d n \n",
                            offset, key.getGenerationNumber());
                    writeBytes(entry.getBytes(StandardCharsets.US_ASCII));
                }
            }
        }
    }

    /**
     * Writes the trailer section: trailer dictionary, startxref, and %%EOF.
     */
    private void writeTrailer(PdfDictionary trailer, int size, long xrefOffset) throws IOException {
        writeTrailer(trailer, size, xrefOffset, -1);
    }

    /**
     * Writes the trailer section. When {@code xrefStmOffset} is non-negative
     * the trailer is a hybrid-reference table trailer (§7.5.8.4) carrying
     * {@code /XRefStm} — the byte offset of the parallel cross-reference
     * stream that PDF 1.5+ readers process instead of the table.
     */
    private void writeTrailer(PdfDictionary trailer, int size, long xrefOffset,
                              long xrefStmOffset) throws IOException {
        // Set /Size in trailer
        PdfDictionary trailerCopy = copyDictionary(trailer);
        trailerCopy.set(PdfName.of("Size"), PdfInteger.valueOf(size));
        // This method always emits a plain `xref` TABLE trailer. When the source document used a
        // cross-reference STREAM, `trailer` is that stream's dictionary and carries stream-only keys
        // — illegal in a table trailer (ISO 32000 §7.5.5). Acrobat then reports the file as damaged
        // (and on recovery ignores appended revisions, so filled XFA data is dropped). Strip them so
        // BOTH the full-rewrite (write) and incremental (writeIncremental) paths stay valid.
        for (String streamOnlyKey : new String[]{"Type", "W", "Index", "Filter", "DecodeParms", "Length", "XRefStm"}) {
            trailerCopy.set(PdfName.of(streamOnlyKey), null);
        }
        if (xrefStmOffset >= 0) {
            trailerCopy.set(PdfName.of("XRefStm"), PdfInteger.valueOf(xrefStmOffset));
        }
        writeBytes("trailer\n".getBytes(StandardCharsets.US_ASCII));

        // Write trailer dictionary
        ByteArrayOutputStream dictBytes = new ByteArrayOutputStream();
        trailerCopy.writeTo(dictBytes);
        writeBytes(dictBytes.toByteArray());

        // startxref
        String startxref = "\nstartxref\n" + xrefOffset + "\n%%EOF\n";
        writeBytes(startxref.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Writes bytes to the output and tracks the current offset.
     */
    private void writeBytes(byte[] data) throws IOException {
        output.write(data);
        currentOffset += data.length;
    }

    /**
     * Writes a portion of a byte array to the output and tracks the current offset.
     */
    private void writeBytes(byte[] data, int offset, int length) throws IOException {
        output.write(data, offset, length);
        currentOffset += length;
    }

    /**
     * Creates a shallow copy of a PdfDictionary.
     */
    private PdfDictionary copyDictionary(PdfDictionary source) {
        PdfDictionary copy = new PdfDictionary();
        for (java.util.Map.Entry<PdfName, PdfBase> entry : source) {
            copy.set(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    /**
     * Finds the maximum object number in the given objects map.
     */
    private int getMaxObjectNumber(Map<PdfObjectKey, PdfBase> objects) {
        int max = 0;
        for (PdfObjectKey key : objects.keySet()) {
            if (key.getObjectNumber() > max) {
                max = key.getObjectNumber();
            }
        }
        return max;
    }

    /**
     * Returns the next free object number for a newly-created object, never below
     * {@link #objectNumberFloor}. In incremental updates the floor accounts for
     * original objects that are preserved in the copied prefix but missing from
     * {@code objects}, preventing a collision with a still-live original number.
     */
    private int nextFreeObjectNumber(Map<PdfObjectKey, PdfBase> objects) {
        if (allocCursor >= 0) {
            // Orphan-registration phase: O(1) monotonic allocation. The cursor
            // is kept >= every key in the map by indexRegistered().
            allocCursor = Math.max(allocCursor + 1, objectNumberFloor);
            return allocCursor;
        }
        return Math.max(getMaxObjectNumber(objects) + 1, objectNumberFloor);
    }

    /**
     * Reads the trailer's {@code /Size} (one greater than the highest object
     * number per ISO 32000-1:2008 §7.5.5), or 0 when absent. Used as the
     * allocation floor for new objects in incremental updates.
     */
    private int trailerSize(PdfDictionary trailer) {
        if (trailer == null) return 0;
        PdfBase size = trailer.get("Size");
        return (size instanceof PdfInteger) ? ((PdfInteger) size).intValue() : 0;
    }

    /**
     * Finds the old xref offset from the original PDF file for incremental updates.
     */
    private long findOldXrefOffset(RandomAccessReader original) throws IOException {
        try {
            return XRefParser.findStartxref(original);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not find old xref offset", e);
            return 0;
        }
    }

    // ========== Compressed PDF 1.5+ writing (Object Streams + XRef Streams) ==========

    /**
     * Writes a complete PDF with object streams and xref stream (PDF 1.5+).
     * This produces significantly smaller files than the text-based {@link #write} method.
     *
     * <p>Eligible objects are packed into compressed object streams (§7.5.7),
     * and the cross-reference table is replaced with a compressed xref stream (§7.5.8).</p>
     *
     * @param trailer      the trailer dictionary (/Root, /Info, etc.)
     * @param objects      all objects in the document
     * @param maxPerStream max objects per object stream
     * @throws IOException if writing fails
     */
    public void writeCompressed(PdfDictionary trailer,
                                 Map<PdfObjectKey, PdfBase> objects,
                                 int maxPerStream) throws IOException {
        LOGGER.log(Level.FINE, "Writing compressed PDF 1.5+ with {0} objects", objects.size());

        // 0. Promote in-graph orphan streams to indirect objects — same pass as
        //    write() (§7.3.8.1: streams MUST be indirect). Without it an edited
        //    page's new /Contents stream (from Page.setContents) is written
        //    without a valid key; the fallback numbering can collide with a real
        //    object (e.g. a page whose object number is 1), dropping that page
        //    from the reloaded document.
        registerOrphanStreams(objects, trailer);

        // 1. Write header — ensure version is at least 1.5
        float version = Math.max(pdfVersion, 1.5f);
        String versionStr = String.format(Locale.US, "%%PDF-%.1f\n", version);
        writeBytes(versionStr.getBytes(StandardCharsets.US_ASCII));
        writeBytes(new byte[]{'%'});
        writeBytes(BINARY_HINT);
        writeBytes(new byte[]{'\n'});

        // 2. Build object streams (pack eligible objects)
        ObjectStreamResult osResult = buildObjectStreams(objects, maxPerStream);

        // 3. Write non-compressed objects (streams, high-gen objects)
        List<Map.Entry<PdfObjectKey, PdfBase>> sorted = new ArrayList<>(
                osResult.nonCompressedObjects.entrySet());
        sorted.sort(Comparator.comparingInt(e -> e.getKey().getObjectNumber()));
        for (Map.Entry<PdfObjectKey, PdfBase> entry : sorted) {
            writeObject(entry.getKey(), entry.getValue());
        }

        // 4. Write object streams themselves
        for (ObjectStreamInfo osi : osResult.objectStreams) {
            writeObject(osi.key, osi.stream);
        }

        // 5. Compute size (max object number across all objects + object streams)
        int maxObj = getMaxObjectNumber(objects);
        if (osResult.nextObjectNumber > maxObj) {
            maxObj = osResult.nextObjectNumber;
        }

        // 6. Write xref stream (replaces both xref table and trailer)
        writeXRefStream(trailer, maxObj + 1, osResult.compressedLocations);

        output.flush();
        LOGGER.log(Level.FINE, "Compressed PDF written: {0} bytes", currentOffset);
    }

    /**
     * Writes an incremental update using an xref stream instead of a text xref table.
     * Same as {@link #writeIncremental} but uses §7.5.8 format.
     *
     * @param original        reader for the original PDF file
     * @param trailer         the trailer dictionary
     * @param modifiedObjects the modified/new objects to append
     * @throws IOException if writing fails
     */
    public void writeIncrementalWithXRefStream(RandomAccessReader original,
                                                PdfDictionary trailer,
                                                Map<PdfObjectKey, PdfBase> modifiedObjects) throws IOException {
        LOGGER.log(Level.FINE, "Writing incremental update (xref stream) with {0} modified objects",
                modifiedObjects.size());

        // Copy original file content
        original.seek(0);
        long originalLength = original.getLength();
        byte[] buffer = new byte[8192];
        long remaining = originalLength;
        while (remaining > 0) {
            int toRead = (int) Math.min(buffer.length, remaining);
            int read = original.read(buffer, 0, toRead);
            if (read <= 0) break;
            writeBytes(buffer, 0, read);
            remaining -= read;
        }

        long oldXrefOffset = findOldXrefOffset(original);

        // Floor new object numbers at the original /Size (see writeIncremental).
        objectNumberFloor = trailerSize(trailer);

        // Write modified objects
        for (Map.Entry<PdfObjectKey, PdfBase> entry : modifiedObjects.entrySet()) {
            writeObject(entry.getKey(), entry.getValue());
        }

        // Compute /Size across all revisions
        PdfBase origSize = trailer.get("Size");
        int originalSize = (origSize instanceof PdfInteger) ? ((PdfInteger) origSize).intValue() : 0;
        int newMaxObj = getMaxObjectNumber(modifiedObjects);
        int newSize = Math.max(originalSize, newMaxObj + 1);

        // Build new trailer with /Prev
        PdfDictionary newTrailer = copyDictionary(trailer);
        newTrailer.set(PdfName.of("Prev"), PdfInteger.valueOf(oldXrefOffset));

        writeXRefStream(newTrailer, newSize, null);

        output.flush();
        LOGGER.log(Level.FINE, "Incremental update (xref stream) written: {0} bytes total", currentOffset);
    }

    /**
     * Packs eligible objects into object streams (ISO 32000-1:2008 §7.5.7).
     *
     * <p>Objects NOT eligible for compression:</p>
     * <ul>
     *   <li>Stream objects (streams cannot nest inside object streams)</li>
     *   <li>Objects with generation number &gt; 0</li>
     * </ul>
     *
     * @param objects      all objects to write
     * @param maxPerStream max objects per stream
     * @return result containing the object streams and compressed object info
     * @throws IOException if serialization fails
     */
    private ObjectStreamResult buildObjectStreams(
            Map<PdfObjectKey, PdfBase> objects, int maxPerStream) throws IOException {

        List<Map.Entry<PdfObjectKey, PdfBase>> eligible = new ArrayList<>();
        Map<PdfObjectKey, PdfBase> nonEligible = new LinkedHashMap<>();

        // maxPerStream <= 0 means "xref stream, but do NOT pack objects into any
        // /ObjStm" — every object stays a plain "N G obj" body. Used by the
        // optimize rewrite when CompressObjects was not requested, so downstream
        // text greps still see object bodies (Aspose parity — PDFNET-39575).
        boolean noPacking = maxPerStream <= 0;

        for (Map.Entry<PdfObjectKey, PdfBase> entry : objects.entrySet()) {
            PdfObjectKey key = entry.getKey();
            PdfBase obj = entry.getValue();
            if (noPacking) {
                nonEligible.put(key, obj);
                continue;
            }
            // The /Encrypt dictionary may not live inside an object stream
            // (§7.5.7): a reader must resolve it BEFORE it can decrypt any
            // object stream, so packing it makes the file unreadable.
            if (obj instanceof PdfStream || key.getGenerationNumber() > 0
                    || key.equals(encryptDictKey)) {
                nonEligible.put(key, obj);
            } else {
                eligible.add(entry);
            }
        }

        List<ObjectStreamInfo> objStreams = new ArrayList<>();
        Map<PdfObjectKey, int[]> compressedLocations = new LinkedHashMap<>();
        int nextObjStreamNum = getMaxObjectNumber(objects) + 1;

        for (int i = 0; i < eligible.size(); i += maxPerStream) {
            int end = Math.min(i + maxPerStream, eligible.size());
            List<Map.Entry<PdfObjectKey, PdfBase>> batch = eligible.subList(i, end);

            int objStreamObjNum = nextObjStreamNum++;

            // Serialize each object body (without "N G obj...endobj" wrapper)
            ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();
            int[] objNums = new int[batch.size()];
            int[] offsets = new int[batch.size()];

            for (int j = 0; j < batch.size(); j++) {
                Map.Entry<PdfObjectKey, PdfBase> entry = batch.get(j);
                objNums[j] = entry.getKey().getObjectNumber();
                offsets[j] = bodyBuf.size();

                entry.getValue().writeTo(bodyBuf);
                bodyBuf.write(' ');

                compressedLocations.put(entry.getKey(),
                        new int[]{objStreamObjNum, j});
            }

            // Build header: "objNum1 offset1 objNum2 offset2 ..."
            StringBuilder headerStr = new StringBuilder();
            for (int j = 0; j < batch.size(); j++) {
                if (j > 0) headerStr.append(' ');
                headerStr.append(objNums[j]).append(' ').append(offsets[j]);
            }
            headerStr.append(' ');

            byte[] headerBytes = headerStr.toString().getBytes(StandardCharsets.US_ASCII);
            byte[] bodyBytes = bodyBuf.toByteArray();

            // Combine: header + body → uncompressed stream data
            byte[] rawData = new byte[headerBytes.length + bodyBytes.length];
            System.arraycopy(headerBytes, 0, rawData, 0, headerBytes.length);
            System.arraycopy(bodyBytes, 0, rawData, headerBytes.length, bodyBytes.length);

            // Create the object stream
            PdfStream objStmStream = new PdfStream();
            objStmStream.set(PdfName.of("Type"), PdfName.of("ObjStm"));
            objStmStream.setInt("N", batch.size());
            objStmStream.setInt("First", headerBytes.length);
            objStmStream.set(PdfName.FILTER, PdfName.of("FlateDecode"));
            objStmStream.setDecodedData(rawData);

            PdfObjectKey objStmKey = new PdfObjectKey(objStreamObjNum, 0);
            objStreams.add(new ObjectStreamInfo(objStmKey, objStmStream));
        }

        return new ObjectStreamResult(nonEligible, objStreams, compressedLocations, nextObjStreamNum);
    }

    /**
     * Writes a cross-reference stream (ISO 32000-1:2008 §7.5.8) instead of a text xref table.
     * The xref stream is an indirect object that combines the xref data
     * and the trailer dictionary entries (/Root, /Info, etc.) into a single stream.
     *
     * @param trailer             the trailer dictionary entries
     * @param size                the /Size value (max obj number + 1)
     * @param compressedLocations map of compressed objects: key → [objStmNum, indexInStream], or null
     * @throws IOException if writing fails
     */
    private void writeXRefStream(PdfDictionary trailer, int size,
                                  Map<PdfObjectKey, int[]> compressedLocations) throws IOException {
        writeXRefStreamObject(trailer, size, compressedLocations, true);
    }

    /**
     * Writes the cross-reference stream object itself and returns its byte
     * offset. With {@code emitStartxref} true this terminates the file
     * ({@code startxref} + {@code %%EOF}) — the pure xref-stream layout.
     * With false, the caller appends its own cross-reference section — the
     * hybrid-reference layout (§7.5.8.4), where a classic table trailer
     * points here via {@code /XRefStm}.
     */
    private long writeXRefStreamObject(PdfDictionary trailer, int size,
                                       Map<PdfObjectKey, int[]> compressedLocations,
                                       boolean emitStartxref) throws IOException {

        // Assign an object number for the xref stream itself
        int xrefObjNum = size;
        int totalSize = xrefObjNum + 1;

        // Determine field widths /W = [w1 w2 w3]
        // w1: type field (1 byte: values 0, 1, 2)
        // w2: offset (type 1) or OBJECT STREAM NUMBER (type 2) — must fit the
        //     larger of the two; sizing for offsets alone truncates container
        //     numbers in documents with >65535 objects (sparse numbering),
        //     leaving the catalog unresolvable.
        // w3: generation (type 1) or index within stream (type 2) — a single
        //     giant object stream can push the index past 65535 as well.
        int w1 = 1;
        int w2 = bytesNeeded(Math.max(currentOffset + 4096, totalSize));
        int maxStreamIndex = 0;
        if (compressedLocations != null) {
            for (int[] loc : compressedLocations.values()) {
                maxStreamIndex = Math.max(maxStreamIndex, loc[1]);
            }
        }
        int w3 = Math.max(2, bytesNeeded(Math.max(65535, maxStreamIndex)));
        int entrySize = w1 + w2 + w3;

        // Build xref data — one entry per object number 0..xrefObjNum
        byte[] xrefData = new byte[totalSize * entrySize];

        // Entry 0: free head entry → type=0, next free=0, gen=65535
        writeXRefEntry(xrefData, 0, w1, w2, w3, 0, 0, 65535);

        // In-use entries (type 1) — objects written directly
        for (Map.Entry<PdfObjectKey, Long> entry : objectOffsets.entrySet()) {
            int objNum = entry.getKey().getObjectNumber();
            long offset = entry.getValue();
            int gen = entry.getKey().getGenerationNumber();
            if (objNum > 0 && objNum < totalSize) {
                writeXRefEntry(xrefData, objNum * entrySize, w1, w2, w3,
                        1, offset, gen);
            }
        }

        // Compressed entries (type 2) — objects inside object streams
        if (compressedLocations != null) {
            for (Map.Entry<PdfObjectKey, int[]> entry : compressedLocations.entrySet()) {
                int objNum = entry.getKey().getObjectNumber();
                int objStmNum = entry.getValue()[0];
                int index = entry.getValue()[1];
                if (objNum > 0 && objNum < totalSize) {
                    writeXRefEntry(xrefData, objNum * entrySize, w1, w2, w3,
                            2, objStmNum, index);
                }
            }
        }

        // The xref stream object's own entry: type=1, pointing to where we're about to write it
        long xrefStreamOffset = currentOffset;
        writeXRefEntry(xrefData, xrefObjNum * entrySize, w1, w2, w3,
                1, xrefStreamOffset, 0);

        // Compress xref data with FlateDecode
        FlateFilter flate = new FlateFilter();
        byte[] compressedData = flate.encode(xrefData, null);

        // Build the xref stream dictionary (replaces both xref table and trailer)
        PdfDictionary xrefDict = copyDictionary(trailer);
        xrefDict.set(PdfName.of("Type"), PdfName.of("XRef"));
        xrefDict.set(PdfName.of("Size"), PdfInteger.valueOf(totalSize));
        xrefDict.set(PdfName.PREV, null);
        xrefDict.set(PdfName.of("XRefStm"), null);
        // When the source document itself used an xref stream, the parsed
        // trailer IS that old stream's dictionary. Its /Index (possibly a
        // sparse incremental-update list) and /DecodeParms (predictor) would
        // misdescribe the entry data built above, which always covers
        // 0..Size-1 and is plain-Flate encoded — drop both so readers fall
        // back to the correct defaults (§7.5.8.2).
        xrefDict.set(PdfName.of("Index"), null);
        xrefDict.set(PdfName.of("DecodeParms"), null);

        PdfArray wArray = new PdfArray();
        wArray.add(PdfInteger.valueOf(w1));
        wArray.add(PdfInteger.valueOf(w2));
        wArray.add(PdfInteger.valueOf(w3));
        xrefDict.set(PdfName.of("W"), wArray);

        xrefDict.set(PdfName.FILTER, PdfName.of("FlateDecode"));
        xrefDict.set(PdfName.LENGTH, PdfInteger.valueOf(compressedData.length));

        // Write the xref stream as an indirect object
        String objHeader = xrefObjNum + " 0 obj\n";
        writeBytes(objHeader.getBytes(StandardCharsets.US_ASCII));

        ByteArrayOutputStream dictBuf = new ByteArrayOutputStream();
        xrefDict.writeTo(dictBuf);
        writeBytes(dictBuf.toByteArray());

        writeBytes("\nstream\r\n".getBytes(StandardCharsets.US_ASCII));
        writeBytes(compressedData);
        writeBytes("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));

        if (emitStartxref) {
            // startxref pointing to the xref stream
            String startxref = "startxref\n" + xrefStreamOffset + "\n%%EOF\n";
            writeBytes(startxref.getBytes(StandardCharsets.US_ASCII));
        }
        return xrefStreamOffset;
    }

    /**
     * Writes a single xref entry into the data array at the given byte position.
     * Fields are written in big-endian format per §7.5.8.
     */
    private static void writeXRefEntry(byte[] data, int pos,
                                        int w1, int w2, int w3,
                                        int type, long field2, int field3) {
        int offset = pos;
        writeIntBytes(data, offset, w1, type);
        offset += w1;
        writeLongBytes(data, offset, w2, field2);
        offset += w2;
        writeIntBytes(data, offset, w3, field3);
    }

    /** Writes an integer as big-endian bytes into the array. */
    private static void writeIntBytes(byte[] data, int offset, int width, int value) {
        for (int i = width - 1; i >= 0; i--) {
            data[offset + i] = (byte) (value & 0xFF);
            value >>= 8;
        }
    }

    /** Writes a long as big-endian bytes into the array. */
    private static void writeLongBytes(byte[] data, int offset, int width, long value) {
        for (int i = width - 1; i >= 0; i--) {
            data[offset + i] = (byte) (value & 0xFF);
            value >>= 8;
        }
    }

    /** Returns the number of bytes needed to represent a value. */
    private static int bytesNeeded(long value) {
        if (value <= 0xFFL) return 1;
        if (value <= 0xFFFFL) return 2;
        if (value <= 0xFFFFFFL) return 3;
        if (value <= 0xFFFFFFFFL) return 4;
        return 5;
    }

    /** Holds the result of building object streams. */
    private static final class ObjectStreamResult {
        final Map<PdfObjectKey, PdfBase> nonCompressedObjects;
        final List<ObjectStreamInfo> objectStreams;
        final Map<PdfObjectKey, int[]> compressedLocations;
        final int nextObjectNumber;

        ObjectStreamResult(Map<PdfObjectKey, PdfBase> nonCompressed,
                           List<ObjectStreamInfo> streams,
                           Map<PdfObjectKey, int[]> locations, int nextObj) {
            this.nonCompressedObjects = nonCompressed;
            this.objectStreams = streams;
            this.compressedLocations = locations;
            this.nextObjectNumber = nextObj;
        }
    }

    /** Information about a generated object stream. */
    private static final class ObjectStreamInfo {
        final PdfObjectKey key;
        final PdfStream stream;

        ObjectStreamInfo(PdfObjectKey key, PdfStream stream) {
            this.key = key;
            this.stream = stream;
        }
    }
}
