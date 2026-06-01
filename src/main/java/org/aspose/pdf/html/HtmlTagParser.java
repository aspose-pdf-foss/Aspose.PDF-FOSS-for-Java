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
    private static final Pattern COMMENT_PATTERN =
        Pattern.compile("<!--[\\s\\S]*?-->");
    private static final Pattern XMLNS_DQ_PATTERN =
        Pattern.compile("\\s+xmlns\\s*=\\s*\"[^\"]*\"");
    private static final Pattern XMLNS_SQ_PATTERN =
        Pattern.compile("\\s+xmlns\\s*=\\s*'[^']*'");
    /** Missing space between an attribute value's closing quote and the next attr. */
    private static final Pattern ATTR_GAP_PATTERN =
        Pattern.compile("([\"'])([a-zA-Z])");
    /** Unquoted attribute values: name=value -> name="value". */
    private static final Pattern UNQUOTED_ATTR_PATTERN =
        Pattern.compile("(?<=\\s)(\\w+)=([a-zA-Z][a-zA-Z0-9_.:-]*)(?=\\s|/?>)");

    /** Single alternation over all void elements: 1 pass instead of 14. */
    private static final Pattern VOID_ELEMENTS_PATTERN =
        Pattern.compile("(?i)<(" + String.join("|", VOID_ELEMENTS)
            + ")(\\s[^>]*?)?\\s*(?<!/)>");
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
                    // Ultra-fallback: strip ALL tags and wrap plain text
                    try {
                        String text = cleaned.replaceAll("<[^>]*>", " ")
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

        // Remove <script> and <style> content (often contains bare < > that break XML)
        html = SCRIPT_PATTERN.matcher(html).replaceAll("");
        html = STYLE_PATTERN.matcher(html).replaceAll("");

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

        // Auto-close unclosed tags (skip for very large documents)
        if (html.length() < 500_000) {
            html = autoCloseTag(html, "p");
            html = autoCloseTag(html, "li");
            html = autoCloseTag(html, "td");
            html = autoCloseTag(html, "th");
            html = autoCloseTag(html, "tr");
            html = autoCloseTag(html, "dt");
            html = autoCloseTag(html, "dd");
        }

        return html;
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
        StringBuilder sb = new StringBuilder();
        String lower = html.toLowerCase();
        int pos = 0;
        int openCount = 0;

        while (pos < html.length()) {
            if (pos < lower.length() - tag.length() - 1
                    && lower.charAt(pos) == '<'
                    && lower.substring(pos + 1).startsWith(tag)
                    && (pos + 1 + tag.length() < lower.length())
                    && !Character.isLetterOrDigit(lower.charAt(pos + 1 + tag.length()))) {
                if (pos + 1 < lower.length() && lower.charAt(pos + 1) != '/') {
                    if (openCount > 0) {
                        sb.append("</").append(tag).append(">");
                    }
                    openCount++;
                }
            }
            if (pos < lower.length() - tag.length() - 2
                    && lower.substring(pos).startsWith("</" + tag)) {
                if (openCount > 0) openCount--;
            }

            sb.append(html.charAt(pos));
            pos++;
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
        if (!lower.contains("<html")) {
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
