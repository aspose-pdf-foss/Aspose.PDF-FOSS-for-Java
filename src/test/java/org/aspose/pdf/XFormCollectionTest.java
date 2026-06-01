package org.aspose.pdf;

import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSStream;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class XFormCollectionTest {

    @Test
    void emptyXObjectDictionary_isEmpty() {
        COSDictionary xobj = new COSDictionary();
        XFormCollection forms = new XFormCollection(xobj, null);
        assertTrue(forms.isEmpty());
        assertEquals(0, forms.size());
        assertTrue(forms.getNames().isEmpty());
        assertFalse(forms.iterator().hasNext());
    }

    @Test
    void filtersOutImageXObjects() {
        COSDictionary xobj = new COSDictionary();
        xobj.set("Im1", imageStream());
        xobj.set("Fm1", formStream());
        xobj.set("Im2", imageStream());

        XFormCollection forms = new XFormCollection(xobj, null);
        assertEquals(1, forms.size());
        assertEquals(List.of("Fm1"), forms.getNames());
        assertNotNull(forms.get("Fm1"));
        assertNull(forms.get("Im1"));
    }

    @Test
    void iteratesInInsertionOrder() {
        COSDictionary xobj = new COSDictionary();
        xobj.set("Fm1", formStream());
        xobj.set("Im1", imageStream());
        xobj.set("Fm2", formStream());
        xobj.set("Fm3", formStream());

        XFormCollection forms = new XFormCollection(xobj, null);
        assertEquals(3, forms.size());

        List<String> names = forms.getNames();
        assertEquals(List.of("Fm1", "Fm2", "Fm3"), names);

        XForm first = forms.get(1);
        assertEquals("Fm1", first.getName());
        XForm second = forms.get(2);
        assertEquals("Fm2", second.getName());
        XForm third = forms.get(3);
        assertEquals("Fm3", third.getName());
    }

    @Test
    void getByIndex_outOfRange_throws() {
        COSDictionary xobj = new COSDictionary();
        xobj.set("Fm1", formStream());

        XFormCollection forms = new XFormCollection(xobj, null);
        assertThrows(IndexOutOfBoundsException.class, () -> forms.get(0));
        assertThrows(IndexOutOfBoundsException.class, () -> forms.get(2));
    }

    @Test
    void getByName_nullName_returnsNull() {
        COSDictionary xobj = new COSDictionary();
        xobj.set("Fm1", formStream());
        XFormCollection forms = new XFormCollection(xobj, null);
        assertNull(forms.get((String) null));
    }

    @Test
    void resources_getForms_returnsLiveView() {
        COSDictionary resourcesDict = new COSDictionary();
        Resources resources = new Resources(resourcesDict);

        XFormCollection forms1 = resources.getForms();
        assertNotNull(forms1);
        assertTrue(forms1.isEmpty());

        // /XObject dictionary was lazy-created — add a form into it
        COSDictionary xobjects = (COSDictionary) resourcesDict.get(COSName.XOBJECT);
        assertNotNull(xobjects, "/XObject should be lazy-created");
        xobjects.set("Fm1", formStream());

        // Same collection instance reflects the new entry (live view)
        assertEquals(1, forms1.size());
        assertEquals("Fm1", forms1.get(1).getName());
    }

    @Test
    void ctor_nullDict_throws() {
        assertThrows(IllegalArgumentException.class, () -> new XFormCollection(null, null));
    }

    private static COSStream formStream() {
        COSStream s = new COSStream();
        s.set(COSName.TYPE, COSName.XOBJECT);
        s.set(COSName.SUBTYPE, COSName.FORM);
        s.setDecodedData(new byte[0]);
        return s;
    }

    private static COSStream imageStream() {
        COSStream s = new COSStream();
        s.set(COSName.TYPE, COSName.XOBJECT);
        s.set(COSName.SUBTYPE, COSName.IMAGE);
        s.setDecodedData(new byte[0]);
        return s;
    }
}
