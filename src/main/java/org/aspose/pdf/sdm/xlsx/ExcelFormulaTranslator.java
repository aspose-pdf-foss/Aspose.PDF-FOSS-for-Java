package org.aspose.pdf.sdm.xlsx;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Translates a subset of the Excel formula language (A1 references) into
 * Acrobat JavaScript for the "live spreadsheet" XLSX &rarr; PDF feature. The
 * produced script is meant to run as a field's {@code /AA /C} (calculate)
 * action; it sets {@code event.value} and refers to sibling cell fields through
 * a small runtime object {@code AXL} installed once as document-level
 * JavaScript (see {@link #runtimeLibrary()}).
 *
 * <p>Cell references resolve to PDF field names via a {@link CellRefResolver}
 * (so the reader controls the naming scheme, e.g. {@code S1_B4}). References are
 * collected in {@link Result#refs} so the caller can build the AcroForm
 * calculation order ({@code /CO}) by topological sort.</p>
 *
 * <p><b>Graceful fallback:</b> an unknown function or unparseable construct
 * raises {@link UnsupportedFormulaException}; the caller then keeps the cached
 * value as static text instead of a live field. Zero third-party dependencies.</p>
 */
public final class ExcelFormulaTranslator {

    /** Resolves a cell reference (sheet may be {@code null} = current sheet) to a PDF field name, or {@code null}. */
    public interface CellRefResolver {
        /**
         * @param sheet the sheet name, or {@code null} for the current sheet
         * @param row   0-based row
         * @param col   0-based column
         * @return the PDF field name for that cell, or {@code null} if none
         */
        String resolve(String sheet, int row, int col);
    }

    /** The outcome of a translation: the JS expression and the set of referenced field names. */
    public static final class Result {
        /** The JavaScript expression (right-hand side; excludes the {@code event.value =} assignment). */
        public final String js;
        /** Field names this formula reads, in first-seen order (for the dependency graph). */
        public final LinkedHashSet<String> refs;

        Result(String js, LinkedHashSet<String> refs) {
            this.js = js;
            this.refs = refs;
        }
    }

    /** Raised when a formula uses a construct or function outside the supported subset. */
    public static final class UnsupportedFormulaException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * @param message the reason the formula could not be translated
         */
        public UnsupportedFormulaException(String message) {
            super(message);
        }
    }

    private final String currentSheet;
    private final CellRefResolver resolver;
    private final LinkedHashSet<String> refs = new LinkedHashSet<>();

    private List<Tok> toks;
    private int pos;

    /**
     * Creates a translator bound to a worksheet and a reference resolver.
     *
     * @param currentSheet the sheet the formula lives on (for unqualified references)
     * @param resolver      maps cell references to PDF field names
     */
    public ExcelFormulaTranslator(String currentSheet, CellRefResolver resolver) {
        this.currentSheet = currentSheet;
        this.resolver = resolver;
    }

    /**
     * Translates one Excel formula (with or without a leading {@code =}).
     *
     * @param formula the Excel formula text
     * @return the translation result
     * @throws UnsupportedFormulaException if the formula cannot be translated
     */
    public Result translate(String formula) {
        if (formula == null) {
            throw new UnsupportedFormulaException("null formula");
        }
        String f = formula.trim();
        if (f.startsWith("=")) {
            f = f.substring(1);
        }
        if (f.isEmpty()) {
            throw new UnsupportedFormulaException("empty formula");
        }
        refs.clear();
        this.toks = tokenize(f);
        this.pos = 0;
        String js = parseExpr(false);
        if (pos != toks.size()) {
            throw new UnsupportedFormulaException("trailing tokens near: " + peek());
        }
        return new Result(js, new LinkedHashSet<>(refs));
    }

    /**
     * The {@code AXL} runtime library, installed once per document as
     * document-level JavaScript. Field calculate scripts call into it.
     *
     * @return the JavaScript source of the runtime
     */
    public static String runtimeLibrary() {
        // NB: inside these methods `this` is the AXL object, not the Doc — so the
        // field accessor calls the bare global getField(), which Acrobat resolves
        // against the document. (this.getField would be undefined and throw.)
        return "var AXL = AXL || {"
            + "f:function(n){var d=getField(n);if(!d)return 0;var x=parseFloat(d.value);return isNaN(x)?0:x;},"
            + "s:function(n){var d=getField(n);return d?String(d.value):\"\";},"
            + "sum:function(){var t=0;for(var i=0;i<arguments.length;i++)t+=arguments[i];return t;},"
            + "avg:function(){return arguments.length?AXL.sum.apply(null,arguments)/arguments.length:0;},"
            + "min:function(){return Math.min.apply(null,arguments);},"
            + "max:function(){return Math.max.apply(null,arguments);},"
            + "count:function(){return arguments.length;},"
            + "round:function(v,d){var m=Math.pow(10,d||0);return Math.round(v*m)/m;},"
            + "iff:function(c,a,b){return c?a:b;},"
            + "and:function(){for(var i=0;i<arguments.length;i++)if(!arguments[i])return false;return true;},"
            + "or:function(){for(var i=0;i<arguments.length;i++)if(arguments[i])return true;return false;},"
            + "not:function(v){return !v;},"
            + "abs:function(v){return Math.abs(v);},"
            + "intg:function(v){return Math.floor(v);},"
            + "mod:function(a,b){return a-b*Math.floor(a/b);},"
            + "concat:function(){var s=\"\";for(var i=0;i<arguments.length;i++)s+=arguments[i];return s;},"
            + "left:function(s,n){return String(s).substring(0,n);},"
            + "right:function(s,n){s=String(s);return s.substring(Math.max(0,s.length-n));},"
            + "mid:function(s,a,n){return String(s).substr(a-1,n);},"
            + "len:function(s){return String(s).length;},"
            + "pow:function(a,b){return Math.pow(a,b);},"
            + "sqrt:function(v){return Math.sqrt(v);}"
            + "};";
    }

    // ------------------------------------------------------------------
    // Parser (recursive descent). strCtx = a cell ref should read as string.
    // ------------------------------------------------------------------

    private String parseExpr(boolean strCtx) {
        return parseCompare(strCtx);
    }

    private String parseCompare(boolean strCtx) {
        String left = parseConcat(strCtx);
        while (hasOp()) {
            String op = peek().text;
            String js;
            switch (op) {
                case "=":  js = "=="; break;
                case "<>": js = "!="; break;
                case "<":  js = "<";  break;
                case ">":  js = ">";  break;
                case "<=": js = "<="; break;
                case ">=": js = ">="; break;
                default: return left;
            }
            next();
            String right = parseConcat(strCtx);
            left = "(" + left + js + right + ")";
        }
        return left;
    }

    private String parseConcat(boolean strCtx) {
        int start = pos;
        String left = parseAdd(strCtx);
        if (!hasOp("&")) {
            return left;
        }
        // Concatenation present: both operands are strings, so a bare cell ref must
        // read via AXL.s (a text cell reads as "" not 0). Reparse the left operand
        // in string context (refs is a set, so the throwaway parse is harmless).
        pos = start;
        left = parseAdd(true);
        while (hasOp("&")) {
            next();
            String right = parseAdd(true);
            // Excel & = string concatenation; coerce both sides to string in JS.
            left = "(String(" + left + ")+String(" + right + "))";
        }
        return left;
    }

    private String parseAdd(boolean strCtx) {
        String left = parseMul(strCtx);
        while (hasOp("+") || hasOp("-")) {
            String op = peek().text;
            next();
            String right = parseMul(strCtx);
            left = "(" + left + op + right + ")";
        }
        return left;
    }

    private String parseMul(boolean strCtx) {
        String left = parsePow(strCtx);
        while (hasOp("*") || hasOp("/")) {
            String op = peek().text;
            next();
            String right = parsePow(strCtx);
            left = "(" + left + op + right + ")";
        }
        return left;
    }

    private String parsePow(boolean strCtx) {
        String left = parseUnary(strCtx);
        while (hasOp("^")) {
            next();
            String right = parseUnary(strCtx);
            left = "AXL.pow(" + left + "," + right + ")";
        }
        return left;
    }

    private String parseUnary(boolean strCtx) {
        if (hasOp("-")) {
            next();
            return "(-" + parseUnary(strCtx) + ")";
        }
        if (hasOp("+")) {
            next();
            return parseUnary(strCtx);
        }
        return parsePostfix(strCtx);
    }

    private String parsePostfix(boolean strCtx) {
        String v = parsePrimary(strCtx);
        while (hasOp("%")) {
            next();
            v = "(" + v + "/100)";
        }
        return v;
    }

    private String parsePrimary(boolean strCtx) {
        Tok t = peek();
        if (t == null) {
            throw new UnsupportedFormulaException("unexpected end of formula");
        }
        switch (t.kind) {
            case NUMBER:
                next();
                return t.text;
            case STRING:
                next();
                return jsString(t.text);
            case LPAREN: {
                next();
                String inner = parseExpr(strCtx);
                expect(Kind.RPAREN);
                return "(" + inner + ")";
            }
            case IDENT:
                return parseIdent(strCtx);
            default:
                throw new UnsupportedFormulaException("unexpected token: " + t.text);
        }
    }

    /** An identifier is a function call, a boolean literal, or a cell reference. */
    private String parseIdent(boolean strCtx) {
        Tok t = next();
        String name = t.text;
        // Function call: IDENT immediately followed by '('.
        if (peek() != null && peek().kind == Kind.LPAREN) {
            return parseCall(name.toUpperCase(Locale.ROOT));
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("TRUE")) {
            return "true";
        }
        if (upper.equals("FALSE")) {
            return "false";
        }
        // Otherwise it must be a cell reference (possibly sheet-qualified above).
        int[] rc = parseA1(name);
        if (rc == null) {
            throw new UnsupportedFormulaException("unsupported name/reference: " + name);
        }
        String field = resolver.resolve(t.sheet, rc[0], rc[1]);
        if (field == null) {
            // A reference to a cell with no field (e.g. empty) — reads as 0/"".
            return strCtx ? "\"\"" : "0";
        }
        refs.add(field);
        return (strCtx ? "AXL.s(" : "AXL.f(") + jsString(field) + ")";
    }

    private String parseCall(String fn) {
        expect(Kind.LPAREN);
        List<String> args = new ArrayList<>();
        List<String> stringArgs;
        if (peek() != null && peek().kind != Kind.RPAREN) {
            do {
                boolean argStr = stringArgContext(fn, args.size());
                // A lone range A1:B3 as an argument expands into several scalars.
                String[] range = tryParseRange(argStr);
                if (range != null) {
                    for (String r : range) {
                        args.add(r);
                    }
                } else {
                    args.add(parseExpr(argStr));
                }
            } while (consumeComma());
        }
        expect(Kind.RPAREN);
        return emitCall(fn, args);
    }

    /** Emits the JS for a supported function, or throws for an unknown one. */
    private String emitCall(String fn, List<String> args) {
        String joined = String.join(",", args);
        switch (fn) {
            case "SUM":         return "AXL.sum(" + joined + ")";
            case "AVERAGE":
            case "AVG":         return "AXL.avg(" + joined + ")";
            case "MIN":         return "AXL.min(" + joined + ")";
            case "MAX":         return "AXL.max(" + joined + ")";
            case "COUNT":       return "AXL.count(" + joined + ")";
            case "ROUND":       return "AXL.round(" + joined + ")";
            case "IF":
                if (args.size() < 2) {
                    throw new UnsupportedFormulaException("IF needs 2-3 arguments");
                }
                return "AXL.iff(" + args.get(0) + "," + args.get(1) + ","
                        + (args.size() >= 3 ? args.get(2) : "\"\"") + ")";
            case "AND":         return "AXL.and(" + joined + ")";
            case "OR":          return "AXL.or(" + joined + ")";
            case "NOT":         return "AXL.not(" + joined + ")";
            case "ABS":         return "AXL.abs(" + joined + ")";
            case "INT":         return "AXL.intg(" + joined + ")";
            case "MOD":         return "AXL.mod(" + joined + ")";
            case "CONCATENATE":
            case "CONCAT":      return "AXL.concat(" + joined + ")";
            case "LEFT":        return "AXL.left(" + joined + ")";
            case "RIGHT":       return "AXL.right(" + joined + ")";
            case "MID":         return "AXL.mid(" + joined + ")";
            case "LEN":         return "AXL.len(" + joined + ")";
            case "POWER":       return "AXL.pow(" + joined + ")";
            case "SQRT":        return "AXL.sqrt(" + joined + ")";
            default:
                throw new UnsupportedFormulaException("unsupported function: " + fn + "()");
        }
    }

    /** Which argument positions of a function take string operands. */
    private static boolean stringArgContext(String fn, int argIndex) {
        switch (fn) {
            case "CONCATENATE":
            case "CONCAT":
                return true;
            case "LEFT":
            case "RIGHT":
            case "MID":
            case "LEN":
                return argIndex == 0;
            default:
                return false;
        }
    }

    /**
     * If the upcoming tokens form a range {@code A1:B3}, consumes them and returns
     * the expanded list of scalar accessors; otherwise returns {@code null} and
     * leaves the position unchanged.
     */
    private String[] tryParseRange(boolean strCtx) {
        int save = pos;
        Tok a = peek();
        if (a == null || a.kind != Kind.IDENT) {
            return null;
        }
        int[] rcA = parseA1(a.text);
        if (rcA == null) {
            return null;
        }
        if (pos + 1 >= toks.size() || toks.get(pos + 1).kind != Kind.COLON) {
            return null;
        }
        Tok b = toks.get(pos + 2);
        if (b == null || b.kind != Kind.IDENT) {
            return null;
        }
        int[] rcB = parseA1(b.text);
        if (rcB == null) {
            pos = save;
            return null;
        }
        // Consume: a, ':', b
        next(); next(); next();
        int r0 = Math.min(rcA[0], rcB[0]);
        int r1 = Math.max(rcA[0], rcB[0]);
        int c0 = Math.min(rcA[1], rcB[1]);
        int c1 = Math.max(rcA[1], rcB[1]);
        List<String> out = new ArrayList<>();
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                String field = resolver.resolve(a.sheet, r, c);
                if (field == null) {
                    continue; // skip empty cells in a range
                }
                refs.add(field);
                out.add((strCtx ? "AXL.s(" : "AXL.f(") + jsString(field) + ")");
            }
        }
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------------
    // Token helpers
    // ------------------------------------------------------------------

    private Tok peek() {
        return pos < toks.size() ? toks.get(pos) : null;
    }

    private Tok next() {
        return toks.get(pos++);
    }

    private boolean hasOp() {
        Tok t = peek();
        return t != null && t.kind == Kind.OP;
    }

    private boolean hasOp(String op) {
        Tok t = peek();
        return t != null && t.kind == Kind.OP && t.text.equals(op);
    }

    private boolean consumeComma() {
        if (peek() != null && peek().kind == Kind.COMMA) {
            next();
            return true;
        }
        return false;
    }

    private void expect(Kind k) {
        Tok t = peek();
        if (t == null || t.kind != k) {
            throw new UnsupportedFormulaException("expected " + k + " but got " + (t == null ? "end" : t.text));
        }
        next();
    }

    // ------------------------------------------------------------------
    // Tokenizer
    // ------------------------------------------------------------------

    private enum Kind { NUMBER, STRING, IDENT, OP, LPAREN, RPAREN, COMMA, COLON }

    private static final class Tok {
        final Kind kind;
        final String text;
        final String sheet; // for IDENT cell refs: qualifying sheet name, or null

        Tok(Kind kind, String text, String sheet) {
            this.kind = kind;
            this.text = text;
            this.sheet = sheet;
        }
    }

    private List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '"') { // string literal with "" escape
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    if (d == '"') {
                        if (i + 1 < n && s.charAt(i + 1) == '"') {
                            sb.append('"');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    sb.append(d);
                    i++;
                }
                out.add(new Tok(Kind.STRING, sb.toString(), null));
                continue;
            }
            if (c == '\'') { // quoted sheet name: 'My Sheet'!A1
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < n && s.charAt(j) != '\'') {
                    sb.append(s.charAt(j));
                    j++;
                }
                // require closing quote + '!'
                if (j >= n || j + 1 >= n || s.charAt(j + 1) != '!') {
                    throw new UnsupportedFormulaException("malformed sheet reference");
                }
                String sheet = sb.toString();
                j += 2; // skip ' and !
                int k = j;
                while (k < n && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '$')) {
                    k++;
                }
                out.add(new Tok(Kind.IDENT, s.substring(j, k), sheet));
                i = k;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int j = i;
                while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                // scientific notation
                if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
                    j++;
                    if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
                        j++;
                    }
                    while (j < n && Character.isDigit(s.charAt(j))) {
                        j++;
                    }
                }
                out.add(new Tok(Kind.NUMBER, s.substring(i, j), null));
                i = j;
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '$')) {
                    j++;
                }
                String word = s.substring(i, j);
                // Sheet-qualified reference: Name!A1
                if (j < n && s.charAt(j) == '!') {
                    int k = j + 1;
                    while (k < n && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '$')) {
                        k++;
                    }
                    out.add(new Tok(Kind.IDENT, s.substring(j + 1, k), word));
                    i = k;
                    continue;
                }
                out.add(new Tok(Kind.IDENT, word, null));
                i = j;
                continue;
            }
            switch (c) {
                case '(': out.add(new Tok(Kind.LPAREN, "(", null)); i++; continue;
                case ')': out.add(new Tok(Kind.RPAREN, ")", null)); i++; continue;
                case ',': out.add(new Tok(Kind.COMMA, ",", null)); i++; continue;
                case ':': out.add(new Tok(Kind.COLON, ":", null)); i++; continue;
                case ';': out.add(new Tok(Kind.COMMA, ",", null)); i++; continue; // some locales use ';'
                default: break;
            }
            // multi-char operators first
            if (c == '<' && i + 1 < n && s.charAt(i + 1) == '>') { out.add(new Tok(Kind.OP, "<>", null)); i += 2; continue; }
            if (c == '<' && i + 1 < n && s.charAt(i + 1) == '=') { out.add(new Tok(Kind.OP, "<=", null)); i += 2; continue; }
            if (c == '>' && i + 1 < n && s.charAt(i + 1) == '=') { out.add(new Tok(Kind.OP, ">=", null)); i += 2; continue; }
            if ("+-*/^&%=<>".indexOf(c) >= 0) {
                out.add(new Tok(Kind.OP, String.valueOf(c), null));
                i++;
                continue;
            }
            throw new UnsupportedFormulaException("unexpected character '" + c + "'");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // A1 helpers
    // ------------------------------------------------------------------

    /** Parses an A1 (or {@code $A$1}) reference to {row, col} (0-based), or {@code null} if not a reference. */
    static int[] parseA1(String ref) {
        int i = 0;
        int n = ref.length();
        if (i < n && ref.charAt(i) == '$') {
            i++;
        }
        int col = 0;
        int letters = 0;
        while (i < n && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
            letters++;
        }
        if (letters == 0 || letters > 3) {
            return null;
        }
        if (i < n && ref.charAt(i) == '$') {
            i++;
        }
        int row = 0;
        int digits = 0;
        while (i < n && Character.isDigit(ref.charAt(i))) {
            row = row * 10 + (ref.charAt(i) - '0');
            i++;
            digits++;
        }
        if (digits == 0 || i != n) {
            return null;
        }
        return new int[]{row - 1, col - 1};
    }

    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
