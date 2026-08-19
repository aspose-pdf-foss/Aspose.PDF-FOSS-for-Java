package org.aspose.pdf.engine.filter;

import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * The {@code /Crypt} filter (ISO 32000-1:2008, §7.4.10).
 * <p>
 * A Crypt filter marks a stream as encrypted by a specific crypt filter from
 * the document's {@code /CF} map. Its decode parameters carry
 * {@code /Name}: {@code /Identity} means the data is passed through
 * unchanged. For non-Identity names the actual decryption is performed by the
 * document security handler at the raw-stream level BEFORE ordinary filters
 * run, so by the time this filter executes the data is already plaintext —
 * in both cases the correct behaviour here is a pass-through.
 * </p>
 */
public class CryptFilter implements PdfFilter {

    private static final Logger LOG = Logger.getLogger(CryptFilter.class.getName());

    /**
     * Passes the data through unchanged (see class notes: either the crypt
     * filter is {@code /Identity}, or decryption already ran upstream).
     *
     * @param encoded the stream bytes
     * @param params  the decode parameters ({@code /Name}), may be null
     * @return the same bytes
     */
    @Override
    public byte[] decode(byte[] encoded, PdfDictionary params) throws IOException {
        if (params != null) {
            String name = params.getNameAsString("Name");
            if (name != null && !"Identity".equals(name)) {
                LOG.fine(() -> "Crypt filter with non-Identity /Name " + name
                        + " — passing through (decryption is handled at the raw-stream level)");
            }
        }
        return encoded;
    }

    /**
     * Passes the data through unchanged.
     *
     * @param decoded the raw bytes
     * @param params  the encode parameters, may be null
     * @return the same bytes
     */
    @Override
    public byte[] encode(byte[] decoded, PdfDictionary params) throws IOException {
        return decoded;
    }

    /**
     * Returns the canonical name {@code /Crypt}.
     *
     * @return the filter name
     */
    @Override
    public PdfName getName() {
        return PdfName.of("Crypt");
    }
}
