package org.aspose.pdf;

import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class DocumentActionsTest {

    @Test
    void emptyCatalog_allGettersReturnNull() {
        COSDictionary catalog = new COSDictionary();
        DocumentActions actions = new DocumentActions(catalog, null);

        assertNull(actions.getOpenAction());
        assertNull(actions.getBeforeClosing());
        assertNull(actions.getBeforeSaving());
        assertNull(actions.getAfterSaving());
        assertNull(actions.getBeforePrinting());
        assertNull(actions.getAfterPrinting());
    }

    @Test
    void setOpenAction_thenGet_roundTrips() throws IOException {
        COSDictionary catalog = new COSDictionary();
        DocumentActions actions = new DocumentActions(catalog, null);

        GoToURIAction goTo = new GoToURIAction("https://example.com");
        actions.setOpenAction(goTo);

        PdfAction read = actions.getOpenAction();
        assertNotNull(read);
        assertEquals("URI", read.getType());
        assertTrue(read instanceof UriAction);
        assertEquals("https://example.com", ((UriAction) read).getUri());

        // Catalog entry actually written
        assertNotNull(catalog.get(COSName.of("OpenAction")));
    }

    @Test
    void setOpenAction_null_removesEntry() {
        COSDictionary catalog = new COSDictionary();
        DocumentActions actions = new DocumentActions(catalog, null);

        actions.setOpenAction(new GoToURIAction("https://x"));
        assertNotNull(catalog.get(COSName.of("OpenAction")));

        actions.setOpenAction(null);
        assertNull(catalog.get(COSName.of("OpenAction")));
    }

    @Test
    void setAfterPrinting_thenGet_roundTrips() throws IOException {
        COSDictionary catalog = new COSDictionary();
        DocumentActions actions = new DocumentActions(catalog, null);

        actions.setAfterPrinting(new GoToURIAction("https://done"));
        PdfAction read = actions.getAfterPrinting();
        assertNotNull(read);
        assertTrue(read instanceof UriAction);

        // /AA dictionary was created with the /DP entry
        COSDictionary aa = (COSDictionary) catalog.get(COSName.of("AA"));
        assertNotNull(aa);
        assertNotNull(aa.get(COSName.of("DP")));
    }

    @Test
    void setAfterPrinting_null_removesEntry_andPrunesAAIfEmpty() {
        COSDictionary catalog = new COSDictionary();
        DocumentActions actions = new DocumentActions(catalog, null);

        actions.setAfterPrinting(new GoToURIAction("https://x"));
        actions.setBeforeSaving(new GoToURIAction("https://y"));

        // Clear only DP: AA should still exist with WS
        actions.setAfterPrinting(null);
        COSDictionary aa = (COSDictionary) catalog.get(COSName.of("AA"));
        assertNotNull(aa, "/AA should remain while WS is still set");
        assertNotNull(aa.get(COSName.of("WS")));

        // Clear the last one: /AA itself should go away
        actions.setBeforeSaving(null);
        assertNull(catalog.get(COSName.of("AA")), "/AA should be pruned when empty");
    }

    @Test
    void allFiveAAEntries_setIndependently() throws IOException {
        COSDictionary catalog = new COSDictionary();
        DocumentActions actions = new DocumentActions(catalog, null);

        actions.setBeforeClosing(new GoToURIAction("https://a"));
        actions.setBeforeSaving(new GoToURIAction("https://b"));
        actions.setAfterSaving(new GoToURIAction("https://c"));
        actions.setBeforePrinting(new GoToURIAction("https://d"));
        actions.setAfterPrinting(new GoToURIAction("https://e"));

        assertEquals("https://a", ((UriAction) actions.getBeforeClosing()).getUri());
        assertEquals("https://b", ((UriAction) actions.getBeforeSaving()).getUri());
        assertEquals("https://c", ((UriAction) actions.getAfterSaving()).getUri());
        assertEquals("https://d", ((UriAction) actions.getBeforePrinting()).getUri());
        assertEquals("https://e", ((UriAction) actions.getAfterPrinting()).getUri());
    }

    @Test
    void document_getActions_returnsLiveView() throws IOException {
        try (Document doc = new Document()) {
            doc.getPages().add();
            DocumentActions actions = doc.getActions();
            assertNotNull(actions);
            assertNull(actions.getOpenAction());

            actions.setOpenAction(new GoToURIAction("https://example.com"));
            // Re-fetch a new view; same catalog → entry visible
            assertNotNull(doc.getActions().getOpenAction());
        }
    }

    @Test
    void ctor_nullCatalog_throws() {
        assertThrows(IllegalArgumentException.class, () -> new DocumentActions(null, null));
    }
}
