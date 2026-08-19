package org.aspose.pdf.engine.filter;

import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Registry of PDF stream filters (§7.4, ISO 32000-1:2008).
 * <p>
 * Maps filter names (both standard and abbreviated) to {@link PdfFilter} implementations.
 * Provides chain-decoding and chain-encoding: when a stream has multiple filters
 * (e.g. {@code /Filter [/ASCII85Decode /FlateDecode]}), decoding applies them left-to-right
 * and encoding applies them right-to-left, per §7.4.1.
 * </p>
 */
public final class FilterFactory {

    private static final Logger LOG = Logger.getLogger(FilterFactory.class.getName());

    private static final Map<String, PdfFilter> FILTERS = new HashMap<>();

    static {
        register(new FlateFilter());
        register(new LZWFilter());
        register(new ASCIIHexFilter());
        register(new ASCII85Filter());
        register(new RunLengthFilter());

        // Image filters with real decoders
        register(new DCTDecodeFilter());
        register(new CCITTFaxDecodeFilter());

        // JPEG 2000 decoder
        register(new JPXDecodeFilter());

        // JBIG2 decoder (segment parser + MMR generic regions)
        register(new JBIG2DecodeFilter());

        // Crypt marker filter (§7.4.10) — pass-through, see CryptFilter notes
        register(new CryptFilter());
    }

    private FilterFactory() {
        // Utility class
    }

    /**
     * Registers a filter. Both the standard name and the abbreviated name are registered.
     *
     * @param filter the filter to register
     */
    public static void register(PdfFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("filter must not be null");
        }
        PdfName name = filter.getName();
        FILTERS.put(name.getName(), filter);

        // Also register abbreviated name
        String abbreviated = getAbbreviation(name.getValue());
        if (abbreviated != null) {
            FILTERS.put(abbreviated, filter);
        }

        LOG.fine(() -> "Registered filter: " + name.getName());
    }

    /**
     * Returns the filter for the given name.
     *
     * @param name the filter name (e.g. {@code /FlateDecode} or {@code /Fl})
     * @return the filter
     * @throws IOException if no filter is registered for the name
     */
    public static PdfFilter getFilter(PdfName name) throws IOException {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        PdfFilter filter = FILTERS.get(name.getName());
        if (filter == null) {
            throw new IOException("Unknown filter: " + name.getName());
        }
        return filter;
    }

    /**
     * Decodes data through a chain of filters, applying them left-to-right
     * (§7.4.1: the first filter in the array is applied first during decoding).
     *
     * @param data    the encoded data
     * @param filters the ordered list of filter names
     * @param params  the ordered list of decode-parameter dictionaries (may be null, or contain nulls)
     * @return the fully decoded data
     * @throws IOException if any filter fails
     */
    public static byte[] decodeChain(byte[] data, List<PdfName> filters, List<PdfDictionary> params)
            throws IOException {
        if (filters == null || filters.isEmpty()) {
            return data;
        }
        byte[] result = data;
        for (int i = 0; i < filters.size(); i++) {
            PdfFilter filter = getFilter(filters.get(i));
            PdfDictionary param = (params != null && i < params.size()) ? params.get(i) : null;
            result = filter.decode(result, param);
        }
        return result;
    }

    /**
     * Encodes data through a chain of filters, applying them right-to-left
     * (reverse of decode order).
     *
     * @param data    the raw data
     * @param filters the ordered list of filter names
     * @param params  the ordered list of encode-parameter dictionaries (may be null, or contain nulls)
     * @return the fully encoded data
     * @throws IOException if any filter fails
     */
    public static byte[] encodeChain(byte[] data, List<PdfName> filters, List<PdfDictionary> params)
            throws IOException {
        if (filters == null || filters.isEmpty()) {
            return data;
        }
        byte[] result = data;
        for (int i = filters.size() - 1; i >= 0; i--) {
            PdfFilter filter = getFilter(filters.get(i));
            PdfDictionary param = (params != null && i < params.size()) ? params.get(i) : null;
            result = filter.encode(result, param);
        }
        return result;
    }

    /**
     * Returns the abbreviated filter name for a standard name, or null.
     */
    private static String getAbbreviation(String standardName) {
        switch (standardName) {
            case "FlateDecode":
                return "Fl";
            case "LZWDecode":
                return "LZW";
            case "ASCIIHexDecode":
                return "AHx";
            case "ASCII85Decode":
                return "A85";
            case "RunLengthDecode":
                return "RL";
            case "CCITTFaxDecode":
                return "CCF";
            case "DCTDecode":
                return "DCT";
            case "JPXDecode":
                return null; // No abbreviation
            default:
                return null;
        }
    }
}
