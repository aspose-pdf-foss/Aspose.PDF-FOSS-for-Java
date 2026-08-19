package org.aspose.pdf.engine.pdfobjects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

import org.aspose.pdf.engine.security.PDFDecryptor;
import org.aspose.pdf.engine.security.PDFEncryptionDict;
import org.junit.jupiter.api.Test;

/**
 * Text-mode-transfer salvage in {@link PdfStream#getDecodedData()}: an
 * FTP/text-mode copy inserts {@code \r} before every bare {@code \n} inside
 * binary stream bytes; for an encrypted stream one inserted byte shifts the
 * RC4 keystream, so the plaintext garbles from the first injected byte on and
 * the filter chain throws. The decode retry strips {@code \r\n → \n} from the
 * FILE bytes (before decryption) and must recover the original data. Corpus
 * case: 34492.pdf — every content stream damaged this way, 19 pages read
 * empty.
 */
public class PdfStreamCrlfSalvageTest {

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater();
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        while (!d.finished()) {
            out.write(buf, 0, d.deflate(buf));
        }
        return out.toByteArray();
    }

    /** Insert \r before every bare \n — the classic text-mode FTP damage. */
    private static byte[] injectCr(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0x0A && (i == 0 || data[i - 1] != 0x0D)) {
                out.write(0x0D);
            }
            out.write(data[i]);
        }
        return out.toByteArray();
    }

    /** RC4 40-bit standard-security decryptor (V=1 R=2) with a fixed key. */
    private static PDFDecryptor rc4Decryptor() {
        PdfDictionary enc = new PdfDictionary();
        enc.set(PdfName.of("Filter"), PdfName.of("Standard"));
        enc.set(PdfName.of("V"), PdfInteger.valueOf(1));
        enc.set(PdfName.of("R"), PdfInteger.valueOf(2));
        enc.set(PdfName.of("O"), new PdfString(new byte[32]));
        enc.set(PdfName.of("U"), new PdfString(new byte[32]));
        enc.set(PdfName.of("P"), PdfInteger.valueOf(-44));
        return new PDFDecryptor(
                new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A},
                new PDFEncryptionDict(enc));
    }

    /**
     * Rebuilds the 34492 shape: content ops → Flate → ASCII85 (with bare \n
     * line wraps) → RC4 → \r injected before every \n of the ciphertext.
     */
    @Test
    public void crDamagedEncryptedStreamIsSalvaged() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("BT /F1 12 Tf 72 ").append(700 - i).append(" Td (line ")
              .append(i * 7919 % 1000).append(") Tj ET\n");
        }
        byte[] payload = sb.toString().getBytes(StandardCharsets.ISO_8859_1);

        byte[] a85 = new org.aspose.pdf.engine.filter.ASCII85Filter()
                .encode(deflate(payload), null);
        // Wrap ASCII85 text with bare \n every 64 chars, as producers do.
        ByteArrayOutputStream wrapped = new ByteArrayOutputStream();
        for (int i = 0; i < a85.length; i++) {
            wrapped.write(a85[i]);
            if (i % 64 == 63) {
                wrapped.write(0x0A);
            }
        }
        PDFDecryptor decryptor = rc4Decryptor();
        // RC4 is symmetric: decrypt(plaintext) = ciphertext for the same key.
        byte[] cipher = decryptor.decrypt(wrapped.toByteArray(), 121, 0);
        byte[] damaged = injectCr(cipher);
        assertTrue(damaged.length > cipher.length,
                "test setup: ciphertext must contain bare \\n bytes to damage");

        PdfDictionary dict = new PdfDictionary();
        PdfArray filters = new PdfArray();
        filters.add(PdfName.of("ASCII85Decode"));
        filters.add(PdfName.of("FlateDecode"));
        dict.set(PdfName.of("Filter"), filters);
        dict.set(PdfName.of("Length"), PdfInteger.valueOf(damaged.length));
        PdfStream stream = new PdfStream(dict, damaged);
        stream.setDecryptor(decryptor, 121, 0);

        assertArrayEquals(payload, stream.getDecodedData(),
                "the \\r\\n→\\n salvage retry must recover the original stream");
    }

    @Test
    public void trulyCorruptStreamStillThrows() {
        byte[] garbage = new byte[128]; // all zeros: not zlib, no \r\n pairs
        PdfDictionary dict = new PdfDictionary();
        dict.set(PdfName.of("Filter"), PdfName.of("FlateDecode"));
        dict.set(PdfName.of("Length"), PdfInteger.valueOf(garbage.length));
        assertThrows(Exception.class, () -> new PdfStream(dict, garbage).getDecodedData(),
                "salvage must not mask genuinely undecodable data");
    }
}
