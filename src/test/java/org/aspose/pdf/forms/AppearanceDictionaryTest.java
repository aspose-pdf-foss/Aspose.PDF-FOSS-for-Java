package org.aspose.pdf.forms;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.XForm;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSStream;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AppearanceDictionaryTest {

    @Test
    void emptyAppearance_returnsEmptyStateSet_andNullNormal() {
        AppearanceDictionary ap = new AppearanceDictionary(new COSDictionary());
        assertTrue(ap.getStateNames().isEmpty());
        assertNull(ap.getNormal());
        assertNull(ap.get("Off"));
        assertFalse(ap.isMultiState());
    }

    @Test
    void singleStateStream_returnsViaGetNormal() {
        COSDictionary apDict = new COSDictionary();
        COSStream stream = new COSStream();
        stream.set(COSName.TYPE, COSName.of("XObject"));
        stream.set(COSName.SUBTYPE, COSName.of("Form"));
        stream.setDecodedData(new byte[]{'B', 'T'});
        apDict.set(COSName.N, stream);

        AppearanceDictionary ap = new AppearanceDictionary(apDict);
        XForm n = ap.getNormal();
        assertNotNull(n);
        assertEquals("N", n.getName());
        assertSame(stream, n.getCOSStream());
        assertFalse(ap.isMultiState());
        assertTrue(ap.getStateNames().isEmpty());
    }

    @Test
    void multiStateDict_listsStates_andReturnsByName() {
        COSDictionary apDict = new COSDictionary();
        COSDictionary nDict = new COSDictionary();
        COSStream offStream = new COSStream();
        COSStream yesStream = new COSStream();
        nDict.set(COSName.of("Off"), offStream);
        nDict.set(COSName.of("Yes"), yesStream);
        apDict.set(COSName.N, nDict);

        AppearanceDictionary ap = new AppearanceDictionary(apDict);
        assertTrue(ap.isMultiState());

        Set<String> names = ap.getStateNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("Off"));
        assertTrue(names.contains("Yes"));

        assertSame(offStream, ap.get("Off").getCOSStream());
        assertSame(yesStream, ap.get("Yes").getCOSStream());
        assertNull(ap.get("Maybe"));
        assertNull(ap.get(null));
    }

    @Test
    void multiState_getNormalReturnsNull() {
        COSDictionary apDict = new COSDictionary();
        COSDictionary nDict = new COSDictionary();
        nDict.set(COSName.of("Off"), new COSStream());
        apDict.set(COSName.N, nDict);

        AppearanceDictionary ap = new AppearanceDictionary(apDict);
        assertNull(ap.getNormal(), "getNormal must return null when /N is a dict");
    }

    @Test
    void ctor_nullApDict_throws() {
        assertThrows(IllegalArgumentException.class, () -> new AppearanceDictionary(null));
    }

    @Test
    void field_getAppearance_lazyCreatesApDict() throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tf = new TextBoxField(page, new Rectangle(0, 0, 100, 20));
            // before any setValue, /AP is absent
            assertNull(tf.getCOSDictionary().get("AP"));

            AppearanceDictionary ap = tf.getAppearance();
            assertNotNull(ap);
            // /AP sub-dict should be created lazily
            assertNotNull(tf.getCOSDictionary().get("AP"));
            assertTrue(ap.getStateNames().isEmpty());
            assertNull(ap.getNormal());
        }
    }

    @Test
    void field_getAppearance_afterSetValue_returnsNormal() throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tf = new TextBoxField(page, new Rectangle(0, 0, 100, 20));
            tf.setValue("hello");

            AppearanceDictionary ap = tf.getAppearance();
            XForm n = ap.getNormal();
            assertNotNull(n, "TextBoxField.setValue should produce a single-state /AP/N stream");
            assertFalse(ap.isMultiState());
        }
    }
}
