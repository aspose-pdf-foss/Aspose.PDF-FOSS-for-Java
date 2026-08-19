package org.aspose.pdf.html;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses HTML (possibly malformed) into a DOM Document.
 * Strategy: wrap in XHTML envelope, try strict XML parse,
 * if fails, clean up common issues and retry.
 */
public class HtmlTagParser {
    private static final Logger LOG = Logger.getLogger(HtmlTagParser.class.getName());

    private static final Set<String> VOID_ELEMENTS = Set.of(
        "area","base","br","col","embed","hr","img","input",
        "link","meta","param","source","track","wbr");

    /** HTML5 boolean attributes that need value for XML: disabled → disabled="disabled" */
    private static final Set<String> BOOLEAN_ATTRS = Set.of(
        "allowfullscreen","async","autofocus","autoplay","checked","controls",
        "default","defer","disabled","formnovalidate","hidden","ismap","loop",
        "multiple","muted","nomodule","novalidate","open","playsinline",
        "readonly","required","reversed","selected");

    // ---------------------------------------------------------------------
    // Precompiled patterns. ISO note: none here; this is HTML-cleanup tooling.
    // Compiling these once (rather than on every cleanHtml() call) removes a
    // large fixed cost and, combined with the alternation patterns below,
    // turns ~70 full string passes into ~6. See Sprint 27 Part A.
    // ---------------------------------------------------------------------
    private static final Pattern IE_CONDITIONAL_PATTERN =
        Pattern.compile("<!--\\[if[^]]*\\]>[\\s\\S]*?<!\\[endif\\]-->");
    private static final Pattern IE_OPEN_PATTERN =
        Pattern.compile("<!\\[if[^]]*\\]>");
    private static final Pattern IE_ENDIF_PATTERN =
        Pattern.compile("<!\\[endif\\]>");
    private static final Pattern CDATA_PATTERN =
        Pattern.compile("<!\\[CDATA\\[[\\s\\S]*?\\]\\]>");
    private static final Pattern SCRIPT_PATTERN =
        Pattern.compile("(?i)<script[^>]*>[\\s\\S]*?</script>");
    private static final Pattern STYLE_PATTERN =
        Pattern.compile("(?i)<style[^>]*>[\\s\\S]*?</style>");
    /** Groups: (1) open tag, (2) CSS body, (3) close tag — for {@link #escapeStyleBodies}. */
    private static final Pattern STYLE_BLOCK_PATTERN =
        Pattern.compile("(?i)(<style[^>]*>)([\\s\\S]*?)(</style>)");
    private static final Pattern COMMENT_PATTERN =
        Pattern.compile("<!--[\\s\\S]*?-->");
    private static final Pattern XMLNS_DQ_PATTERN =
        Pattern.compile("\\s+xmlns\\s*=\\s*\"[^\"]*\"");
    private static final Pattern XMLNS_SQ_PATTERN =
        Pattern.compile("\\s+xmlns\\s*=\\s*'[^']*'");
    /**
     * Missing space between an attribute value's closing quote and the next
     * attr: {@code ="v"attr="v2"} &rarr; {@code ="v" attr="v2"}. The next-attr
     * shape ({@code name=} followed by a quote) must be required — a bare
     * quote-then-letter match also fires on the OPENING quote of every value
     * ({@code name="x"} &rarr; {@code name=" x"}) and on quotes in plain text.
     */
    private static final Pattern ATTR_GAP_PATTERN =
        Pattern.compile("([\"'])([a-zA-Z][\\w-]*=[\"'])");
    /** Unquoted attribute values: name=value -> name="value". */
    private static final Pattern UNQUOTED_ATTR_PATTERN =
        Pattern.compile("(?<=\\s)(\\w+)=([a-zA-Z][a-zA-Z0-9_.:-]*)(?=\\s|/?>)");
    /** Inline event-handler attributes (onclick, onchange, …). Their JavaScript
     *  bodies frequently contain {@code <}, {@code &} and quotes that break XML;
     *  they are never rendered, so strip them wholesale (both quote styles). */
    private static final Pattern ON_HANDLER_PATTERN =
        Pattern.compile("(?i)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*')");
    /** {@code <textarea …>} / {@code </textarea>} tags (kept content, dropped tag). */
    private static final Pattern TEXTAREA_TAG_PATTERN =
        Pattern.compile("(?i)</?textarea[^>]*>");

    /** Single alternation over all void elements: 1 pass instead of 14. */
    private static final Pattern VOID_ELEMENTS_PATTERN =
        Pattern.compile("(?i)<(" + String.join("|", VOID_ELEMENTS)
            + ")(\\s[^>]*?)?\\s*(?<!/)>");
    /** Stray CLOSING tags of void elements (e.g. {@code </br>}, {@code </img>}) —
     *  invalid in XML (no matching open) and a common legacy-HTML defect. */
    private static final Pattern VOID_CLOSE_PATTERN =
        Pattern.compile("(?i)</(" + String.join("|", VOID_ELEMENTS) + ")\\s*>");
    /** Single alternation over all boolean attributes: 1 pass instead of 21. */
    private static final Pattern BOOLEAN_ATTRS_PATTERN =
        Pattern.compile("(?i)(<[a-zA-Z][^>]*\\s)(" + String.join("|", BOOLEAN_ATTRS)
            + ")(?=\\s|/?>)");

    /**
     * Named HTML entities mapped to their replacement characters. Used by the
     * single-pass entity scanner. XML built-ins (amp/lt/gt/quot/apos) and
     * numeric entities are intentionally absent — they are passed through.
     */
    private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
        Map.entry("nbsp", " "),    Map.entry("mdash", "—"),
        Map.entry("ndash", "–"),   Map.entry("laquo", "«"),
        Map.entry("raquo", "»"),   Map.entry("copy", "©"),
        Map.entry("reg", "®"),     Map.entry("trade", "™"),
        Map.entry("bull", "•"),    Map.entry("hellip", "…"),
        Map.entry("ldquo", "“"),   Map.entry("rdquo", "”"),
        Map.entry("lsquo", "‘"),   Map.entry("rsquo", "’"),
        Map.entry("euro", "€"),    Map.entry("pound", "£"),
        Map.entry("yen", "¥"),     Map.entry("cent", "¢"),
        Map.entry("times", "×"),   Map.entry("divide", "÷"),
        Map.entry("deg", "°"),     Map.entry("micro", "µ"),
        Map.entry("para", "¶"),    Map.entry("sect", "§"),
        Map.entry("acute", "´"),   Map.entry("cedil", "¸"),
        Map.entry("ordf", "ª"),    Map.entry("ordm", "º"),
        Map.entry("iquest", "¿"),  Map.entry("iexcl", "¡"),
        Map.entry("lsaquo", "‹"),  Map.entry("rsaquo", "›"));

    private HtmlTagParser() {} // utility class

    /**
     * Parses the given HTML string into a DOM {@link org.w3c.dom.Document}.
     *
     * <p>The parser first attempts a strict XML parse after wrapping the input
     * in a minimal XHTML envelope. If that fails (e.g. due to unclosed tags or
     * HTML entities), it applies common clean-up heuristics and retries.</p>
     *
     * @param html the HTML string to parse; may be a fragment or a full document
     * @return a DOM Document representing the parsed HTML
     * @throws IOException if the HTML cannot be parsed even after clean-up
     */
    public static org.w3c.dom.Document parse(String html) throws IOException {
        // Try strict parse first
        try {
            return parseAsXml(ensureXmlStructure(html));
        } catch (Exception e1) {
            // Fall back: clean up common HTML issues
            String cleaned = cleanHtml(html);
            try {
                return parseAsXml(ensureXmlStructure(cleaned));
            } catch (Exception e2) {
                // Retry with auto-closing of unbalanced p/li/td/tr — a more
                // aggressive repair kept OUT of the first cleaned attempt because
                // it is nesting-unaware and would mangle valid nested tables.
                try {
                    return parseAsXml(ensureXmlStructure(autoCloseAll(cleaned)));
                } catch (Exception e2b) {
                    // ignore; fall through
                }
                // Targeted repair: unwrap <textarea> to its text. Kept OUT of
                // cleanHtml so a well-formed document keeps textarea as a real
                // multiline form field; only markup that already failed reaches
                // here, where legacy forms' many stray </textarea> closes abort
                // the parse. Try with and without auto-closing.
                String noTextarea = TEXTAREA_TAG_PATTERN.matcher(cleaned).replaceAll("");
                try {
                    return parseAsXml(ensureXmlStructure(noTextarea));
                } catch (Exception e2c) {
                    // ignore; fall through
                }
                try {
                    return parseAsXml(ensureXmlStructure(autoCloseAll(noTextarea)));
                } catch (Exception e2d) {
                    // ignore; fall through to aggressive clean
                }
                // Last resort: aggressive clean
                try {
                    return parseAsXml(ensureXmlStructure(aggressiveClean(cleaned)));
                } catch (Exception e3) {
                    LOG.warning("HTML parse failed after all cleanup attempts: " + e3.getMessage());
                    // Fallback: preserve body markup if the head section is what
                    // breaks XML parsing (common for legacy HTML with malformed meta/style).
                    try {
                        String bodyOnly = extractBodyHtml(aggressiveClean(cleaned));
                        return parseAsXml(ensureXmlStructure(cleanHtml(bodyOnly)));
                    } catch (Exception e4) {
                        LOG.warning("HTML body-only fallback failed: " + e4.getMessage());
                    }
                    // Ultra-fallback: strip ALL tags and wrap plain text. Remove
                    // <style>/<script> BODIES first so their CSS/JS text is not
                    // dumped as visible content when only tags are stripped.
                    try {
                        String text = STYLE_PATTERN.matcher(cleaned).replaceAll(" ");
                        text = SCRIPT_PATTERN.matcher(text).replaceAll(" ");
                        text = text.replaceAll("<[^>]*>", " ")
                            .replaceAll("\\s+", " ").trim();
                        return parseAsXml("<html><body><p>" + escapeXml(text) + "</p></body></html>");
                    } catch (Exception e5) {
                        throw new IOException("Failed to parse HTML: " + e2.getMessage(), e2);
                    }
                }
            }
        }
    }

    /**
     * Cleans common HTML constructs that are not valid XML.
     */
    static String cleanHtml(String html) {
        // Remove IE conditional comments: <!--[if ...]>...<![endif]-->
        html = IE_CONDITIONAL_PATTERN.matcher(html).replaceAll("");
        // Remove non-comment IE conditionals: <![if ...]>...<![endif]>
        html = IE_OPEN_PATTERN.matcher(html).replaceAll("");
        html = IE_ENDIF_PATTERN.matcher(html).replaceAll("");
        // Remove CDATA sections
        html = CDATA_PATTERN.matcher(html).replaceAll("");

        // Remove <script> content (not rendered; often bare < > that break XML).
        html = SCRIPT_PATTERN.matcher(html).replaceAll("");
        // Remove inline event handlers (onclick=… etc.) — their JS bodies carry
        // <, & and quotes that break XML attribute parsing, and are never rendered.
        html = ON_HANDLER_PATTERN.matcher(html).replaceAll("");
        // PRESERVE <style> blocks: the CSS is needed to style the output. Escaping
        // the XML-significant chars inside each block lets the <style> element (and
        // its text) survive XML parsing, instead of deleting the whole stylesheet
        // (which dropped ALL styling for any HTML that wasn't already valid XML).
        html = escapeStyleBodies(html);

        // Remove HTML comments (after IE conditionals are handled)
        html = COMMENT_PATTERN.matcher(html).replaceAll("");

        // Strip xmlns attributes (cause namespace issues in non-namespace-aware mode)
        html = XMLNS_DQ_PATTERN.matcher(html).replaceAll("");
        html = XMLNS_SQ_PATTERN.matcher(html).replaceAll("");

        // Fix missing space between attributes: ="value"attr= → ="value" attr=
        // (handles both " and ' in a single pass)
        html = ATTR_GAP_PATTERN.matcher(html).replaceAll("$1 $2");

        // Close void elements (single alternation pass): <br> -> <br/>, <BR> -> <BR/>
        html = VOID_ELEMENTS_PATTERN.matcher(html).replaceAll("<$1$2/>");
        // Drop stray CLOSING void tags (</br>, </img>, …) — no matching open,
        // so XML rejects them ("markup must be well-formed"); legacy forms emit
        // </br> after tables, which otherwise collapses the whole parse.
        html = VOID_CLOSE_PATTERN.matcher(html).replaceAll("");

        // Fix boolean attributes (single alternation pass): controls → controls="controls".
        // A tag may carry several (<input checked disabled>); each pass fixes the first
        // boolean attr after a '<', so repeat until stable (capped for safety).
        for (int i = 0; i < 8; i++) {
            String next = BOOLEAN_ATTRS_PATTERN.matcher(html).replaceAll("$1$2=\"$2\"");
            if (next.equals(html)) break;
            html = next;
        }

        // Fix unquoted attribute values: name=value → name="value"
        // But skip already-quoted values and numeric entities
        html = UNQUOTED_ATTR_PATTERN.matcher(html).replaceAll("$1=\"$2\"");

        // Replace named HTML entities, escape bare ampersands, and map unknown
        // entities — all in a single forward scan (was 32+ separate passes).
        html = replaceHtmlEntitiesAndEscapeAmps(html);

        return html;
    }

    /**
     * Auto-closes unbalanced {@code p}/{@code li}/{@code td}/{@code th}/{@code tr}/
     * {@code dt}/{@code dd} tags. This is a LAST-RESORT repair: the heuristic is
     * nesting-unaware (a nested table's inner {@code <td>} looks like a sibling of
     * the outer one), so it can mangle already-balanced nested tables. It is only
     * applied when a plain clean-up parse fails, never on already-valid markup.
     */
    static String autoCloseAll(String html) {
        // O(n) per tag, so safe on large documents.
        if (html.length() >= 5_000_000) {
            return html;
        }
        html = autoCloseTag(html, "p");
        html = autoCloseTag(html, "li");
        html = autoCloseTag(html, "td");
        html = autoCloseTag(html, "th");
        html = autoCloseTag(html, "tr");
        html = autoCloseTag(html, "dt");
        html = autoCloseTag(html, "dd");
        return html;
    }

    /**
     * Escapes the XML-significant characters inside every {@code <style>} body so
     * the stylesheet survives XML parsing as the element's text content. CSS never
     * contains a literal {@code <}; {@code &} would otherwise start an entity, and
     * {@code ]]>} would close a section — both are neutralised. The child
     * combinator {@code >} is valid in XML text and left as-is.
     */
    private static String escapeStyleBodies(String html) {
        java.util.regex.Matcher m = STYLE_BLOCK_PATTERN.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String body = m.group(2)
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace("]]>", "]]&gt;");
            m.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement(m.group(1) + body + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Single forward scan that, in one pass over the input:
     * <ul>
     *   <li>passes through XML built-in entities ({@code &amp; &lt; &gt; &quot; &apos;});</li>
     *   <li>passes through numeric entities ({@code &#123;}, {@code &#x1A;});</li>
     *   <li>replaces known named entities (e.g. {@code &nbsp;}) with their character;</li>
     *   <li>maps unknown named entities to U+FFFD;</li>
     *   <li>escapes a bare {@code &} (not starting an entity) to {@code &amp;}.</li>
     * </ul>
     * This replaces the previous ~31 sequential {@link String#replace} calls plus
     * two catch-all regex passes, which on a 5&nbsp;MB document meant dozens of full
     * copies of the string.
     */
    private static String replaceHtmlEntitiesAndEscapeAmps(String html) {
        int len = html.length();
        StringBuilder sb = new StringBuilder(len + len / 16); // small slack
        int i = 0;
        while (i < len) {
            char c = html.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            // Find ';' within a reasonable distance (entity names are short).
            int semi = -1;
            int maxScan = Math.min(i + 12, len);
            for (int j = i + 1; j < maxScan; j++) {
                char cj = html.charAt(j);
                if (cj == ';') { semi = j; break; }
                // An entity body is [A-Za-z0-9#] only; anything else means it is
                // a bare ampersand, not an entity.
                if (!Character.isLetterOrDigit(cj) && cj != '#') break;
            }
            if (semi < 0) {
                sb.append("&amp;"); // bare ampersand
                i++;
                continue;
            }
            String inner = html.substring(i + 1, semi);
            // XML built-ins and numeric entities: pass through verbatim.
            if (inner.equals("amp") || inner.equals("lt") || inner.equals("gt")
                    || inner.equals("quot") || inner.equals("apos")
                    || (!inner.isEmpty() && inner.charAt(0) == '#')) {
                sb.append('&').append(inner).append(';');
                i = semi + 1;
                continue;
            }
            String repl = NAMED_ENTITIES.get(inner);
            if (repl != null) {
                sb.append(repl);
            } else {
                sb.append('�'); // unknown named entity
            }
            i = semi + 1;
        }
        return sb.toString();
    }

    /**
     * Aggressive clean: remove unknown/problematic tags, fix structural issues.
     */
    private static String aggressiveClean(String html) {
        // Remove tags that commonly cause XML issues
        html = html.replaceAll("(?i)<(audio|video|iframe|object|embed|canvas|svg|math|noscript)" +
            "[^>]*>[\\s\\S]*?</\\1>", "");
        html = html.replaceAll("(?i)<(audio|video|iframe|object|embed|canvas|svg|math|noscript)" +
            "[^>]*/?>", "");
        // Remove processing instructions
        html = html.replaceAll("<\\?[^?]*\\?>", "");
        // Remove any remaining <![...]> constructs
        html = html.replaceAll("<!\\[[^]]*\\]>", "");
        // Remove attributes with newlines in values (malformed)
        html = html.replaceAll("\\w+=\\s*\"[^\"]*\\n[^\"]*\"", "");
        // Fix unquoted attributes more aggressively
        html = html.replaceAll("(\\w+)=\\s*([^\"'\\s>][^\\s>]*)(?=\\s|/?>)", "$1=\"$2\"");
        return html;
    }

    private static String extractBodyHtml(String html) {
        Matcher matcher = Pattern.compile("(?is)<body[^>]*>(.*)</body>").matcher(html);
        if (matcher.find()) {
            return "<html><body>" + matcher.group(1) + "</body></html>";
        }
        return "<html><body>" + html + "</body></html>";
    }

    /**
     * Auto-closes unclosed tags of the given name by inserting closing tags
     * before the next opening tag of the same name or before certain parent closes.
     */
    private static String autoCloseTag(String html, String tag) {
        // O(n): scan once with charAt/regionMatches. The previous implementation
        // called lower.substring(pos) inside the loop — O(n²), which forced a size
        // cap that left large legacy documents (unbalanced <td>/<tr>/<p>) to fail
        // the structured parse and fall back to plain-text-stripping.
        StringBuilder sb = new StringBuilder(html.length() + 64);
        int n = html.length();
        int tagLen = tag.length();
        int openCount = 0;
        for (int pos = 0; pos < n; pos++) {
            char ch = html.charAt(pos);
            if (ch == '<') {
                boolean isClose = pos + 1 < n && html.charAt(pos + 1) == '/';
                int nameStart = isClose ? pos + 2 : pos + 1;
                if (html.regionMatches(true, nameStart, tag, 0, tagLen)) {
                    int after = nameStart + tagLen;
                    char delim = after < n ? html.charAt(after) : ' ';
                    if (!Character.isLetterOrDigit(delim)) {
                        if (isClose) {
                            if (openCount > 0) {
                                openCount--;
                            }
                        } else {
                            if (openCount > 0) {
                                sb.append("</").append(tag).append(">");
                            }
                            openCount++;
                        }
                    }
                }
            }
            sb.append(ch);
        }
        if (openCount > 0) {
            sb.append("</").append(tag).append(">");
        }
        return sb.toString();
    }

    /**
     * Ensures the HTML string has a minimal XML-compatible structure.
     */
    static String ensureXmlStructure(String html) {
        html = html.replaceAll("(?i)<\\?xml[^?]*\\?>", "").trim();
        html = html.replaceAll("(?i)<!DOCTYPE[^>]*>", "").trim();

        String lower = html.toLowerCase();
        // Wrap when the content does not START with <html>: either there is no
        // <html> at all, or (common in legacy exports) markup precedes it — e.g.
        // "<table>…</table><br><html>…</html>". Leaving leading markup outside the
        // root produces "markup following the root element must be well-formed" and
        // collapses the whole document to the plain-text fallback.
        if (!lower.startsWith("<html")) {
            html = "<html><body>" + html + "</body></html>";
        } else {
            // Ensure </body> exists before </html>
            if (!lower.contains("</body>") && lower.contains("</html>")) {
                html = html.replaceAll("(?i)(</html\\s*>)", "</body>$1");
            }
            // Ensure <body> exists
            if (!lower.contains("<body")) {
                // Insert <body> after </head> or after <html...>
                if (lower.contains("</head>")) {
                    html = html.replaceAll("(?i)(</head\\s*>)", "$1<body>");
                    if (!html.toLowerCase().contains("</body>")) {
                        html = html.replaceAll("(?i)(</html\\s*>)", "</body>$1");
                    }
                }
            }
            // Ensure </body> and </html> exist at the end
            lower = html.toLowerCase();
            if (lower.contains("<body") && !lower.contains("</body>")) {
                html = html + "</body>";
            }
            if (lower.contains("<html") && !lower.contains("</html>")) {
                html = html + "</html>";
            }
        }
        return html;
    }

    /**
     * Escapes text for safe inclusion in XML.
     */
    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    /**
     * Parses the given XML string into a DOM Document using JAXP.
     */
    private static org.w3c.dom.Document parseAsXml(String xml) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory factory =
            javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
        catch (Exception ignored) {}
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); }
        catch (Exception ignored) {}
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        // Suppress stderr output from parser
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
