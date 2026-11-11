package org.aspose.pdf;

/**
 * Enumerates the document "fixups" that can be applied through
 * {@link Document#convert(Fixup, String)}.
 *
 * <p>A fixup is a canned document-repair/normalization operation, mirroring the
 * {@code Aspose.Pdf.Fixup} enumeration. Only the page-rotation fixups are
 * currently implemented by this FOSS engine; applying any other value throws
 * {@link UnsupportedOperationException}.</p>
 */
public enum Fixup {

    /**
     * Rotates every page so that it is displayed in landscape orientation
     * (displayed width &gt; displayed height). Pages already shown in landscape
     * are left unchanged.
     */
    RotatePagesToLandscape,

    /**
     * Rotates every page so that it is displayed in portrait orientation
     * (displayed height &gt; displayed width). Pages already shown in portrait
     * are left unchanged.
     */
    RotatePagesToPortrait
}
