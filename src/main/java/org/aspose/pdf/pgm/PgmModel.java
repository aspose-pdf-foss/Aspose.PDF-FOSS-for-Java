package org.aspose.pdf.pgm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole geometry model (IR spec §2.3): pages plus the id index. Spatial
 * indexing is deferred (linear scan is sufficient for Stage-1..5 tasks).
 */
public final class PgmModel {

    private final List<PgmPage> pages = new ArrayList<>();
    private final Map<String, List<PgmBox>> byId = new LinkedHashMap<>();

    /**
     * Adds a page to the model.
     *
     * @param page the page
     */
    public void addPage(PgmPage page) {
        pages.add(page);
    }

    /**
     * Returns the pages in document order.
     *
     * @return the pages (unmodifiable)
     */
    public List<PgmPage> getPages() {
        return Collections.unmodifiableList(pages);
    }

    /**
     * Returns the page at the given index.
     *
     * @param index the 0-based page index
     * @return the page
     */
    public PgmPage getPage(int index) {
        return pages.get(index);
    }

    /**
     * Registers a box in the id index. Called by builders after adding the box
     * to its page.
     *
     * @param box the box
     */
    public void indexBox(PgmBox box) {
        if (box.getId() != null) {
            byId.computeIfAbsent(box.getId(), k -> new ArrayList<>()).add(box);
        }
    }

    /**
     * Returns all boxes projecting the given SDM node (1..N for multi-box
     * nodes, IR spec §2.4).
     *
     * @param guid the node id
     * @return the boxes (empty list if none)
     */
    public List<PgmBox> byId(String guid) {
        List<PgmBox> list = byId.get(guid);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    /**
     * Returns the total box count across all pages.
     *
     * @return the count
     */
    public int totalBoxes() {
        int n = 0;
        for (PgmPage p : pages) {
            n += p.getBoxes().size();
        }
        return n;
    }
}
