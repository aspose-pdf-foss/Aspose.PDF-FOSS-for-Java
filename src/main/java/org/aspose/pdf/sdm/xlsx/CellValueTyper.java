package org.aspose.pdf.sdm.xlsx;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.aspose.pdf.sdm.CellValue;

/**
 * Infers a typed spreadsheet value from the plain text of a table cell (IR spec
 * &sect;1.7 &mdash; the XLSX perspective). A PDF carries no cell types, so the
 * exporter must recognise a grouped number, an ISO date and a boolean literal
 * from the text alone. Formulas are unrecoverable &mdash; values only.
 *
 * <p>The recogniser is deliberately conservative: anything it cannot confidently
 * classify stays {@link CellValue.Kind#TEXT}, so a mis-parse never corrupts a
 * label into a wrong number.</p>
 */
public final class CellValueTyper {

    private CellValueTyper() {
        // static only
    }

    /** Excel's day 0 is 1899-12-30 (the serial date epoch, incl. the 1900 leap bug offset). */
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    /** No-break space, figure space and narrow no-break space — all used as digit groupers. */
    private static final String SPACE_GROUPERS = "    ";

    /** The result of typing a cell: the recognised kind plus everything the writer needs to emit it. */
    public static final class Typed {
        /** Recognised kind. */
        public final CellValue.Kind kind;
        /** Original display text (trimmed). */
        public final String text;
        /** Machine value for numeric kinds (number, date serial, bool 1/0); ignored for TEXT. */
        public final double number;
        /** Excel number-format code, or null for General / text. */
        public final String numFmt;
        /** True when {@link #number} carries a numeric payload (NUMBER, DATE, BOOL). */
        public final boolean numeric;

        Typed(CellValue.Kind kind, String text, double number, String numFmt, boolean numeric) {
            this.kind = kind;
            this.text = text;
            this.number = number;
            this.numFmt = numFmt;
            this.numeric = numeric;
        }
    }

    /**
     * Types the given cell text.
     *
     * @param raw the cell text (may be null)
     * @return the typed result; {@link CellValue.Kind#TEXT} for blank/unclassifiable text
     */
    public static Typed type(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return new Typed(CellValue.Kind.TEXT, "", 0, null, false);
        }
        if (s.equalsIgnoreCase("true")) {
            return new Typed(CellValue.Kind.BOOL, s, 1, null, true);
        }
        if (s.equalsIgnoreCase("false")) {
            return new Typed(CellValue.Kind.BOOL, s, 0, null, true);
        }
        Typed date = tryDate(s);
        if (date != null) {
            return date;
        }
        Typed number = tryNumber(s);
        if (number != null) {
            return number;
        }
        return new Typed(CellValue.Kind.TEXT, s, 0, null, false);
    }

    /**
     * Builds the SDM {@link CellValue} for a typed result, so the model itself
     * carries the tabular type (kind + original text + format).
     *
     * @param t the typed result
     * @return the SDM cell value
     */
    public static CellValue toCellValue(Typed t) {
        return new CellValue(t.kind, t.text, t.numFmt);
    }

    // ------------------------------------------------------------------
    // Dates
    // ------------------------------------------------------------------

    private static Typed tryDate(String s) {
        if (s.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
            LocalDate d = parse(s, "yyyy-M-d");
            if (d != null) {
                return dateResult(s, d, "yyyy\\-mm\\-dd");
            }
        }
        if (s.matches("\\d{1,2}\\.\\d{1,2}\\.\\d{4}")) {
            LocalDate d = parse(s, "d.M.yyyy");
            if (d != null) {
                return dateResult(s, d, "dd\\.mm\\.yyyy");
            }
        }
        if (s.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
            String[] p = s.split("/");
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            String pattern = a > 12 ? "d/M/yyyy" : b > 12 ? "M/d/yyyy" : "M/d/yyyy"; // default US
            LocalDate d = parse(s, pattern);
            if (d != null) {
                return dateResult(s, d, "mm/dd/yyyy");
            }
        }
        return null;
    }

    private static Typed dateResult(String text, LocalDate d, String numFmt) {
        long serial = d.toEpochDay() - EXCEL_EPOCH.toEpochDay();
        if (serial < 1) {
            return null; // pre-1900 serials are unreliable — keep as text
        }
        return new Typed(CellValue.Kind.DATE, text, serial, numFmt, true);
    }

    private static LocalDate parse(String s, String pattern) {
        try {
            return LocalDate.parse(s, DateTimeFormatter.ofPattern(pattern, Locale.ROOT));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Numbers
    // ------------------------------------------------------------------

    private static Typed tryNumber(String s) {
        String work = s;
        boolean negative = false;
        if (work.startsWith("(") && work.endsWith(")")) {
            negative = true; // accounting negative
            work = work.substring(1, work.length() - 1).trim();
        }
        boolean percent = false;
        if (work.endsWith("%")) {
            percent = true;
            work = work.substring(0, work.length() - 1).trim();
        }
        String currency = null;
        if (!work.isEmpty() && isCurrencyChar(work.charAt(0))) {
            currency = work.substring(0, 1);
            work = work.substring(1).trim();
        } else if (!work.isEmpty() && isCurrencyChar(work.charAt(work.length() - 1))) {
            currency = work.substring(work.length() - 1);
            work = work.substring(0, work.length() - 1).trim();
        }
        if (work.startsWith("-")) {
            negative = true;
            work = work.substring(1).trim();
        } else if (work.startsWith("+")) {
            work = work.substring(1).trim();
        }
        if (!work.matches("[0-9.,\\u0020\\u00A0\\u2007\\u202F]+") || !work.matches(".*[0-9].*")) {
            return null;
        }
        String normalized = normalizeDecimal(work);
        if (normalized == null) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
        if (negative) {
            value = -value;
        }
        boolean hasFraction = normalized.indexOf('.') >= 0;
        boolean grouped = hasGroupingSeparator(work);
        String numFmt;
        if (percent) {
            value = value / 100.0;
            numFmt = hasFraction ? "0.00%" : "0%";
        } else if (currency != null) {
            numFmt = "\"" + currency + "\"#,##0.00";
        } else if (grouped) {
            numFmt = hasFraction ? "#,##0.00" : "#,##0";
        } else {
            numFmt = null; // General
        }
        return new Typed(CellValue.Kind.NUMBER, s, value, numFmt, true);
    }

    /** True when the numeric body carries a thousands separator (comma or any space form). */
    private static boolean hasGroupingSeparator(String work) {
        for (int i = 0; i < work.length(); i++) {
            char c = work.charAt(i);
            if (c == ',' || SPACE_GROUPERS.indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Strips grouping separators and normalises the decimal mark to '.'. */
    private static String normalizeDecimal(String work) {
        StringBuilder sb = new StringBuilder(work.length());
        for (int i = 0; i < work.length(); i++) {
            char c = work.charAt(i);
            if (SPACE_GROUPERS.indexOf(c) < 0) {
                sb.append(c); // drop all space groupers
            }
        }
        String t = sb.toString();
        int dot = t.indexOf('.');
        int comma = t.indexOf(',');
        int lastDot = t.lastIndexOf('.');
        int lastComma = t.lastIndexOf(',');
        if (dot >= 0 && comma >= 0) {
            // The rightmost separator is the decimal mark; the other is grouping.
            char decimal = lastDot > lastComma ? '.' : ',';
            char group = decimal == '.' ? ',' : '.';
            t = t.replace(String.valueOf(group), "");
            t = t.replace(decimal, '.');
        } else if (comma >= 0) {
            int commas = countChar(t, ',');
            String tail = t.substring(lastComma + 1);
            if (commas > 1 || tail.length() == 3) {
                t = t.replace(",", ""); // grouping
            } else {
                t = t.replace(',', '.'); // decimal comma
            }
        } else if (dot >= 0) {
            if (countChar(t, '.') > 1) {
                t = t.replace(".", ""); // European grouping dots
            }
            // single dot stays as the decimal mark
        }
        if (!t.matches("\\d+(\\.\\d+)?")) {
            return null;
        }
        return t;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static boolean isCurrencyChar(char c) {
        return c == '$' || c == '€' /* EUR */ || c == '£' /* GBP */
                || c == '¥' /* JPY */ || c == '₽' /* RUB */ || c == '₹' /* INR */;
    }
}
