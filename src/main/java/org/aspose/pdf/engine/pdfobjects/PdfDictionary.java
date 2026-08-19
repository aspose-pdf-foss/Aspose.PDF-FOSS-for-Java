package org.aspose.pdf.engine.pdfobjects;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * PDF dictionary object (§7.3.7, ISO 32000-1:2008).
 * <p>
 * An associative map from {@link PdfName} to {@link PdfBase}. The fundamental structure
 * of PDF: pages, fonts, images, etc. are all represented as dictionaries.
 * Insertion order is preserved via {@link LinkedHashMap}.
 * </p>
 */
public class PdfDictionary extends PdfBase implements Iterable<Map.Entry<PdfName, PdfBase>> {

    private static final Logger LOG = Logger.getLogger(PdfDictionary.class.getName());

    /** The underlying map. Protected so PdfStream can access it. */
    protected final LinkedHashMap<PdfName, PdfBase> map;

    private static final byte[] DICT_OPEN = {'<', '<'};
    private static final byte[] DICT_CLOSE = {'>', '>'};
    private static final byte[] NULL_BYTES = {'n', 'u', 'l', 'l'};
    private static final byte NEWLINE = '\n';
    private static final byte SPACE = ' ';

    /**
     * Creates an empty dictionary.
     */
    public PdfDictionary() {
        this.map = new LinkedHashMap<>();
    }

    /**
     * Creates a dictionary as a shallow copy of another dictionary.
     *
     * @param other the dictionary to copy
     */
    public PdfDictionary(PdfDictionary other) {
        this.map = new LinkedHashMap<>(other.map);
    }

    /**
     * Returns the number of entries.
     *
     * @return the size
     */
    public int size() {
        return map.size();
    }

    /**
     * Returns whether the dictionary is empty.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return map.isEmpty();
    }

    /**
     * Returns whether the dictionary contains the given key.
     *
     * @param key the key
     * @return true if the key exists
     */
    public boolean containsKey(PdfName key) {
        return map.containsKey(key);
    }

    /**
     * Returns whether the dictionary contains the given key (convenience method).
     *
     * @param key the key name string
     * @return true if the key exists
     */
    public boolean containsKey(String key) {
        return map.containsKey(PdfName.of(key));
    }

    /**
     * Returns the value for the given key, or null.
     *
     * @param key the key
     * @return the value or null
     */
    public PdfBase get(PdfName key) {
        return map.get(key);
    }

    /**
     * Returns the value for the given key (convenience method), or null.
     *
     * @param key the key name string
     * @return the value or null
     */
    public PdfBase get(String key) {
        return map.get(PdfName.of(key));
    }

    /**
     * Sets a key-value pair. If value is null, the key is removed (§7.3.7).
     *
     * @param key   the key
     * @param value the value, or null to remove
     */
    public void set(PdfName key, PdfBase value) {
        if (key == null) return;
        if (value == null || value instanceof PdfNull) {
            if (map.containsKey(key)) {
                map.remove(key);
                markDirty();
            }
            return;
        }
        PdfBase old = map.put(key, value);
        if (old != value) {
            markDirty();
        }
    }

    /**
     * Sets a key-value pair (convenience method). If value is null, the key is removed.
     *
     * @param key   the key name string
     * @param value the value, or null to remove
     */
    public void set(String key, PdfBase value) {
        set(PdfName.of(key), value);
    }

    /**
     * Removes a key.
     *
     * @param key the key
     * @return the removed value, or null
     */
    public PdfBase remove(PdfName key) {
        markDirty();
        return map.remove(key);
    }

    /**
     * Returns the set of keys.
     *
     * @return the key set
     */
    public Set<PdfName> keySet() {
        return map.keySet();
    }

    /**
     * Returns the collection of values.
     *
     * @return the values
     */
    public Collection<PdfBase> values() {
        return map.values();
    }

    // === Typed getters ===

    /**
     * Returns the value as an int, or the default.
     *
     * @param key          the key name
     * @param defaultValue the default
     * @return the int value or default
     */
    public int getInt(String key, int defaultValue) {
        PdfBase obj = resolveScalar(get(key));
        if (obj instanceof PdfInteger) return ((PdfInteger) obj).intValue();
        if (obj instanceof PdfFloat) return (int) ((PdfFloat) obj).doubleValue();
        return defaultValue;
    }

    /**
     * Dereferences an indirect value. ISO 32000-1:2008 §7.3.10 allows
     * ANY dictionary value to be an indirect reference — real-world scans
     * (corpus 33319-1) put even /Height behind one, and returning the default
     * instead broke the raster (a 0-height full-page image rendered as a
     * solid black page); the same applies to container values (/PageLabels,
     * /Pages held as references made typed getters return null). Non-references
     * pass through untouched; a dangling reference resolves to null and falls
     * back to the default.
     */
    private static PdfBase resolveScalar(PdfBase obj) {
        if (obj instanceof PdfObjectReference) {
            try {
                return ((PdfObjectReference) obj).dereference();
            } catch (Exception e) {
                return null;
            }
        }
        return obj;
    }

    /**
     * Returns the value as an int, or the default (PdfName key variant).
     *
     * @param key          the key
     * @param defaultValue the default
     * @return the int value or default
     */
    public int getInt(PdfName key, int defaultValue) {
        PdfBase obj = resolveScalar(get(key));
        if (obj instanceof PdfInteger) return ((PdfInteger) obj).intValue();
        if (obj instanceof PdfFloat) return (int) ((PdfFloat) obj).doubleValue();
        return defaultValue;
    }

    /**
     * Returns the value as a long, or the default.
     *
     * @param key          the key name
     * @param defaultValue the default
     * @return the long value or default
     */
    public long getLong(String key, long defaultValue) {
        PdfBase obj = resolveScalar(get(key));
        if (obj instanceof PdfInteger) return ((PdfInteger) obj).longValue();
        if (obj instanceof PdfFloat) return (long) ((PdfFloat) obj).doubleValue();
        return defaultValue;
    }

    /**
     * Returns the value as a float, or the default.
     *
     * @param key          the key name
     * @param defaultValue the default
     * @return the float value or default
     */
    public float getFloat(String key, float defaultValue) {
        PdfBase obj = resolveScalar(get(key));
        if (obj instanceof PdfFloat) return ((PdfFloat) obj).floatValue();
        if (obj instanceof PdfInteger) return ((PdfInteger) obj).floatValue();
        return defaultValue;
    }

    /**
     * Returns the value as a boolean, or the default.
     *
     * @param key          the key name
     * @param defaultValue the default
     * @return the boolean value or default
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        PdfBase obj = resolveScalar(get(key));
        if (obj instanceof PdfBoolean) return ((PdfBoolean) obj).getValue();
        return defaultValue;
    }

    /**
     * Returns the value as a name string, or null.
     *
     * @param key the key name
     * @return the name value or null
     */
    public String getNameAsString(String key) {
        PdfBase obj = resolveScalar(get(key));
        return (obj instanceof PdfName) ? ((PdfName) obj).getName() : null;
    }

    /**
     * Returns the value as a decoded string, or null.
     *
     * @param key the key name
     * @return the string value or null
     */
    public String getString(String key) {
        PdfBase obj = resolveScalar(get(key));
        return (obj instanceof PdfString) ? ((PdfString) obj).getString() : null;
    }

    /**
     * Returns the value as a PdfDictionary, or null.
     *
     * @param key the key name
     * @return the dictionary or null
     */
    public PdfDictionary getDictionary(String key) {
        PdfBase obj = resolveScalar(get(key));
        return (obj instanceof PdfDictionary) ? (PdfDictionary) obj : null;
    }

    /**
     * Returns the value as a PdfArray, or null.
     *
     * @param key the key name
     * @return the array or null
     */
    public PdfArray getArray(String key) {
        PdfBase obj = resolveScalar(get(key));
        return (obj instanceof PdfArray) ? (PdfArray) obj : null;
    }

    // === Typed setters ===

    /**
     * Sets an integer value.
     *
     * @param key   the key name
     * @param value the int value
     */
    public void setInt(String key, int value) {
        set(key, PdfInteger.valueOf(value));
    }

    /**
     * Sets a float value.
     *
     * @param key   the key name
     * @param value the float value
     */
    public void setFloat(String key, float value) {
        set(key, new PdfFloat(value));
    }

    /**
     * Sets a boolean value.
     *
     * @param key   the key name
     * @param value the boolean value
     */
    public void setBoolean(String key, boolean value) {
        set(key, PdfBoolean.valueOf(value));
    }

    /**
     * Sets a name value.
     *
     * @param key       the key name
     * @param nameValue the name value string
     */
    public void setName(String key, String nameValue) {
        set(key, PdfName.of(nameValue));
    }

    /**
     * Sets a string value.
     *
     * @param key         the key name
     * @param stringValue the string value
     */
    public void setString(String key, String stringValue) {
        set(key, new PdfString(stringValue));
    }

    // === PDF-specific convenience ===

    /**
     * Returns the /Type value as a string (e.g. "Page", "Font"), or null.
     *
     * @return the type name or null
     */
    public String getType() {
        return getNameAsString("Type");
    }

    /**
     * Returns the /Subtype (or /S) value as a string, or null.
     *
     * @return the subtype name or null
     */
    public String getSubtype() {
        String s = getNameAsString("Subtype");
        if (s == null) {
            s = getNameAsString("S");
        }
        return s;
    }

    /**
     * Traverses nested dictionaries by key path.
     * Returns null if any key in the path is missing or not a dictionary.
     *
     * @param keys the key path
     * @return the value at the end of the path, or null
     */
    public PdfBase getPath(String... keys) {
        PdfBase current = this;
        for (String key : keys) {
            // Resolve indirect references before accessing as dictionary
            if (current instanceof PdfObjectReference) {
                try {
                    current = ((PdfObjectReference) current).dereference();
                } catch (java.io.IOException e) {
                    return null;
                }
            }
            if (!(current instanceof PdfDictionary)) {
                return null;
            }
            current = ((PdfDictionary) current).get(key);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    @Override
    public void writeTo(OutputStream os) throws IOException {
        if (!enterWriting(this)) {
            // Direct cyclic reference (parent dict already on the writing stack).
            // Emit a /Null placeholder rather than recurse forever (§7.3.9 — null
            // is equivalent to a missing entry, so consumers degrade safely).
            os.write(NULL_BYTES);
            return;
        }
        try {
            os.write(DICT_OPEN);
            for (Map.Entry<PdfName, PdfBase> entry : map.entrySet()) {
                os.write(NEWLINE);
                entry.getKey().writeTo(os);
                os.write(SPACE);
                PdfBase value = entry.getValue();
                // If the value is an indirect object (has an objectKey), write as reference
                // to prevent infinite recursion from circular references (e.g. Page → /Parent → Pages → /Kids → Page)
                if (value != null && !(value instanceof PdfObjectReference)
                        && value.getObjectKey() != null && value.getObjectKey().getObjectNumber() > 0) {
                    // Write as indirect reference "N G R"
                    String ref = value.getObjectKey().getObjectNumber() + " "
                            + value.getObjectKey().getGenerationNumber() + " R";
                    os.write(ref.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                } else {
                    value.writeTo(os);
                }
            }
            os.write(NEWLINE);
            os.write(DICT_CLOSE);
        } finally {
            leaveWriting(this);
        }
    }

    @Override
    public Iterator<Map.Entry<PdfName, PdfBase>> iterator() {
        return map.entrySet().iterator();
    }

    @Override
    public <T> T accept(IPdfVisitor<T> visitor) {
        return visitor.visitDictionary(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PdfDictionary)) return false;
        return map.equals(((PdfDictionary) o).map);
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }

    @Override
    public String toString() {
        return "PdfDictionary{size=" + map.size() + "}";
    }
}
