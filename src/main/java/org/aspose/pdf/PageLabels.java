package org.aspose.pdf;

import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfString;
import org.aspose.pdf.engine.pdfobjects.NumberTree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Page labelling for a PDF document (ISO 32000-1:2008, §12.4.2).
 * Maps page indices to display labels (e.g., "i", "ii", "1", "2", "A-1").
 *
 * <p>Stored as a number tree under {@code /PageLabels} in the catalog.
 * Each entry maps a page index to a label dictionary with:</p>
 * <ul>
 *   <li>/S — numbering style: D (decimal), r (lowercase roman), R (uppercase roman),
 *           a (lowercase alpha), A (uppercase alpha)</li>
 *   <li>/P — label prefix string</li>
 *   <li>/St — starting number (default 1)</li>
 * </ul>
 */
public class PageLabels {

    private static final Logger LOG = Logger.getLogger(PageLabels.class.getName());

    private final List<LabelRange> ranges = new ArrayList<>();
    private PdfDictionary catalog;
    private PdfDictionary pageLabelsDict;

    /**
     * A labelling range starting at a page index.
     */
    public static class LabelRange {
        /** The 0-based page index where this range starts. */
        public final int startPage;
        /** The numbering style: "D", "r", "R", "a", "A", or null. */
        public final String style;
        /** The label prefix string. */
        public final String prefix;
        /** The first number in this range (default 1). */
        public final int startNumber;

        /**
         * Creates a label range.
         *
         * @param startPage   the 0-based starting page index
         * @param style       the numbering style
         * @param prefix      the label prefix
         * @param startNumber the first number in the range
         */
        public LabelRange(int startPage, String style, String prefix, int startNumber) {
            this.startPage = startPage;
            this.style = style;
            this.prefix = prefix != null ? prefix : "";
            this.startNumber = startNumber;
        }
    }

    /**
     * Parses page labels from the catalog's {@code /PageLabels} number tree.
     *
     * @param catalog the document catalog dictionary
     * @return the page labels, or {@code null} if not present
     * @throws IOException if parsing fails
     */
    public static PageLabels parse(PdfDictionary catalog) throws IOException {
        PdfBase plObj = resolve(catalog.get("PageLabels"));
        if (!(plObj instanceof PdfDictionary)) return null;

        PageLabels labels = new PageLabels();
        labels.catalog = catalog;
        labels.pageLabelsDict = (PdfDictionary) plObj;
        for (Map.Entry<Integer, PdfBase> entry : new NumberTree((PdfDictionary) plObj).entries()) {
            PdfBase labelDict = entry.getValue();
            if (labelDict instanceof PdfDictionary) {
                PdfDictionary ld = (PdfDictionary) labelDict;
                String style = ld.getNameAsString("S");
                String prefix = ld.getString("P");
                int start = ld.getInt("St", 1);
                labels.ranges.add(new LabelRange(entry.getKey(), style, prefix, start));
            }
        }
        labels.ranges.sort(Comparator.comparingInt(r -> r.startPage));
        return labels.ranges.isEmpty() ? null : labels;
    }

    /**
     * Updates or adds a page-label range starting at the given 0-based page index.
     * <p>
     * The change is immediately written back to the underlying {@code /PageLabels}
     * number tree in the catalog, so a subsequent document save persists it.
     * </p>
     *
     * @param pageIndex the 0-based page index where the label range starts
     * @param label the label definition
     */
    public void updateLabel(int pageIndex, PageLabel label) {
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must be >= 0");
        }
        if (label == null) {
            throw new IllegalArgumentException("label must not be null");
        }

        LabelRange updated = new LabelRange(
                pageIndex,
                toStyleCode(label.getNumberingStyle()),
                label.getPrefix(),
                Math.max(1, label.getStartingValue()));

        boolean replaced = false;
        for (int i = 0; i < ranges.size(); i++) {
            if (ranges.get(i).startPage == pageIndex) {
                ranges.set(i, updated);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            ranges.add(updated);
        }
        ranges.sort(Comparator.comparingInt(r -> r.startPage));
        syncToCatalog();
    }

    /**
     * Returns the display label for a given 0-based page index.
     *
     * @param pageIndex the 0-based page index
     * @return the display label string
     */
    public String getLabel(int pageIndex) {
        LabelRange range = null;
        for (LabelRange r : ranges) {
            if (r.startPage <= pageIndex) range = r;
            else break;
        }
        if (range == null) return String.valueOf(pageIndex + 1);

        int offset = pageIndex - range.startPage;
        int number = range.startNumber + offset;

        String numStr;
        if (range.style == null) {
            numStr = "";
        } else {
            switch (range.style) {
                case "D": numStr = String.valueOf(number); break;
                case "r": numStr = toRoman(number).toLowerCase(); break;
                case "R": numStr = toRoman(number); break;
                case "a": numStr = toAlpha(number).toLowerCase(); break;
                case "A": numStr = toAlpha(number); break;
                default:  numStr = String.valueOf(number); break;
            }
        }
        return range.prefix + numStr;
    }

    /**
     * Returns all label ranges.
     *
     * @return unmodifiable list of ranges
     */
    public List<LabelRange> getRanges() {
        return Collections.unmodifiableList(ranges);
    }

    /**
     * Returns the 0-based page indices at which label ranges start,
     * in ascending order (ISO 32000-1:2008, §12.4.2 number-tree keys).
     *
     * @return array of range start page indices
     */
    public int[] getPages() {
        int[] pages = new int[ranges.size()];
        for (int i = 0; i < ranges.size(); i++) {
            pages[i] = ranges.get(i).startPage;
        }
        return pages;
    }

    /**
     * Removes the label range starting at the given 0-based page index.
     * The change is written back to the {@code /PageLabels} number tree
     * so a subsequent save persists it.
     *
     * @param pageIndex the 0-based page index of the range to remove
     * @return {@code true} if a range starting at that index existed and was removed
     */
    public boolean removeLabel(int pageIndex) {
        for (int i = 0; i < ranges.size(); i++) {
            if (ranges.get(i).startPage == pageIndex) {
                ranges.remove(i);
                syncToCatalog();
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Number formatting
    // ═══════════════════════════════════════════════════════════════

    /**
     * Converts an integer to uppercase Roman numerals (1–3999).
     */
    static String toRoman(int num) {
        String[] thousands = {"", "M", "MM", "MMM"};
        String[] hundreds  = {"", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM"};
        String[] tens      = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
        String[] ones      = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        num = Math.max(1, Math.min(3999, num));
        return thousands[num / 1000] + hundreds[(num % 1000) / 100]
                + tens[(num % 100) / 10] + ones[num % 10];
    }

    /**
     * Converts an integer to alphabetic label (1=A, 2=B, ..., 26=Z, 27=AA, ...).
     */
    static String toAlpha(int num) {
        StringBuilder sb = new StringBuilder();
        while (num > 0) {
            num--;
            sb.insert(0, (char) ('A' + num % 26));
            num /= 26;
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════════════════

    private static PdfBase resolve(PdfBase obj) throws IOException {
        if (obj instanceof PdfObjectReference) {
            return ((PdfObjectReference) obj).dereference();
        }
        return obj;
    }

    private void syncToCatalog() {
        if (catalog == null) {
            return;
        }
        if (pageLabelsDict == null) {
            pageLabelsDict = new PdfDictionary();
            catalog.set(PdfName.of("PageLabels"), pageLabelsDict);
        }
        NumberTree tree = new NumberTree(pageLabelsDict);
        // We rebuild from the in-memory ranges list (already sorted by
        // startPage), so collapse any existing multi-level structure into a
        // single root leaf first — NumberTree.put then re-adds entries in
        // sorted order.
        tree.clear();
        for (LabelRange range : ranges) {
            PdfDictionary labelDict = new PdfDictionary();
            if (range.style != null && !range.style.isEmpty()) {
                labelDict.set(PdfName.of("S"), PdfName.of(range.style));
            }
            if (range.prefix != null && !range.prefix.isEmpty()) {
                labelDict.set(PdfName.of("P"), new PdfString(range.prefix));
            }
            if (range.startNumber > 1) {
                labelDict.set(PdfName.of("St"), PdfInteger.valueOf(range.startNumber));
            }
            tree.put(range.startPage, labelDict);
        }
    }

    private static String toStyleCode(NumberingStyle style) {
        if (style == null) {
            return null;
        }
        switch (style) {
            case NumeralsArabic:
                return "D";
            case NumeralsRomanLowercase:
                return "r";
            case NumeralsRomanUppercase:
                return "R";
            case LettersLowercase:
                return "a";
            case LettersUppercase:
                return "A";
            case None:
            default:
                return null;
        }
    }
}
