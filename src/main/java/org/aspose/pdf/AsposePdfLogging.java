package org.aspose.pdf;

import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Centralised logging configuration for the {@code org.aspose.pdf} library.
 *
 * <p>By default the library is <strong>silent</strong> — its loggers are
 * configured to {@link Level#OFF}, so no output is emitted to host
 * applications. End-users should not see parser-internal recovery messages
 * unless they explicitly ask for them.</p>
 *
 * <h2>Enable logging</h2>
 * <pre>
 *   # Via system property:
 *   java -Daspose.pdf.log=warning ...   # real warnings only
 *   java -Daspose.pdf.log=verbose ...   # + recovery details (FINE)
 *   java -Daspose.pdf.log=debug ...     # everything (ALL)
 *
 *   # Or any standard java.util.logging.Level name:
 *   java -Daspose.pdf.log=INFO ...
 *
 *   # Programmatically:
 *   AsposePdfLogging.setLevel(Level.WARNING);
 * </pre>
 *
 * <p>This class never touches the JUL root logger — only the
 * {@code org.aspose.pdf} subtree — so it will not interfere with the host
 * application's existing logging configuration.</p>
 */
public final class AsposePdfLogging {

    /** The library's root logger; all {@code org.aspose.pdf.*} classes log through it. */
    private static final Logger ROOT = Logger.getLogger("org.aspose.pdf");

    /** System property used to enable/configure library logging. */
    public static final String LOG_PROPERTY = "aspose.pdf.log";

    static {
        configureFromSystemProperty();
    }

    private AsposePdfLogging() {}

    /**
     * Re-reads {@link #LOG_PROPERTY} and configures the library logger.
     * Called automatically on class load. Safe to call again at runtime.
     */
    public static synchronized void configureFromSystemProperty() {
        String value = System.getProperty(LOG_PROPERTY, "off");
        applyLevel(parseLevel(value));
    }

    /**
     * Programmatically sets the library log level. A {@code null} level silences
     * the library ({@link Level#OFF}).
     *
     * @param level the level to apply, or {@code null} for {@link Level#OFF}
     */
    public static synchronized void setLevel(Level level) {
        applyLevel(level != null ? level : Level.OFF);
    }

    /**
     * Returns the effective level on the {@code org.aspose.pdf} logger.
     *
     * @return the current effective level (never {@code null})
     */
    public static Level getLevel() {
        Level l = ROOT.getLevel();
        if (l != null) return l;
        Logger parent = ROOT.getParent();
        return parent != null && parent.getLevel() != null ? parent.getLevel() : Level.INFO;
    }

    private static void applyLevel(Level level) {
        ROOT.setLevel(level);
        // Do not propagate to the JUL root logger's handlers — this keeps the
        // host application's own logging configuration untouched.
        ROOT.setUseParentHandlers(false);

        // When the user opts in but has not installed a handler on our subtree,
        // give them a minimal console handler so the records are actually shown.
        if (level != Level.OFF && ROOT.getHandlers().length == 0) {
            ConsoleHandler h = new ConsoleHandler();
            h.setLevel(level);
            h.setFormatter(new SimpleFormatter());
            ROOT.addHandler(h);
        }

        // Existing handlers should honour the new level too.
        for (Handler h : ROOT.getHandlers()) {
            h.setLevel(level);
        }
    }

    private static Level parseLevel(String raw) {
        if (raw == null) return Level.OFF;
        String v = raw.trim().toLowerCase();
        switch (v) {
            case "":
            case "off": case "false": case "no": case "none": case "silent":
                return Level.OFF;
            case "on": case "true": case "yes":
            case "warning": case "warn":
                return Level.WARNING;
            case "verbose": case "fine":
                return Level.FINE;
            case "debug": case "all": case "trace":
                return Level.ALL;
            default:
                try {
                    return Level.parse(raw.trim().toUpperCase());
                } catch (IllegalArgumentException ex) {
                    return Level.OFF;
                }
        }
    }
}
