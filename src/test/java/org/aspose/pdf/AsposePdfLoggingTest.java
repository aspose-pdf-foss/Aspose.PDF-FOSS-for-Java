package org.aspose.pdf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 24 Part B — {@link AsposePdfLogging} library-logging discipline.
 * The library is silent ({@link Level#OFF}) by default and opt-in via the
 * {@code aspose.pdf.log} system property or {@link AsposePdfLogging#setLevel}.
 */
class AsposePdfLoggingTest {

    @AfterEach
    void resetToSilent() {
        // Leave the shared JUL state silent so other test classes in the same
        // fork are unaffected.
        System.clearProperty(AsposePdfLogging.LOG_PROPERTY);
        AsposePdfLogging.configureFromSystemProperty();
    }

    @Test
    void default_isOff() {
        System.clearProperty(AsposePdfLogging.LOG_PROPERTY);
        AsposePdfLogging.configureFromSystemProperty();
        assertEquals(Level.OFF, AsposePdfLogging.getLevel());
    }

    @Test
    void propertyOn_enablesWarning() {
        System.setProperty(AsposePdfLogging.LOG_PROPERTY, "on");
        AsposePdfLogging.configureFromSystemProperty();
        assertEquals(Level.WARNING, AsposePdfLogging.getLevel());
    }

    @Test
    void propertyWarning_enablesWarning() {
        System.setProperty(AsposePdfLogging.LOG_PROPERTY, "warning");
        AsposePdfLogging.configureFromSystemProperty();
        assertEquals(Level.WARNING, AsposePdfLogging.getLevel());
    }

    @Test
    void propertyVerbose_enablesFine() {
        System.setProperty(AsposePdfLogging.LOG_PROPERTY, "verbose");
        AsposePdfLogging.configureFromSystemProperty();
        assertEquals(Level.FINE, AsposePdfLogging.getLevel());
    }

    @Test
    void propertyDebug_enablesAll() {
        System.setProperty(AsposePdfLogging.LOG_PROPERTY, "debug");
        AsposePdfLogging.configureFromSystemProperty();
        assertEquals(Level.ALL, AsposePdfLogging.getLevel());
    }

    @Test
    void propertyArbitraryJulLevel_works() {
        System.setProperty(AsposePdfLogging.LOG_PROPERTY, "SEVERE");
        AsposePdfLogging.configureFromSystemProperty();
        assertEquals(Level.SEVERE, AsposePdfLogging.getLevel());
    }

    @Test
    void propertyInvalid_fallsBackToOff() {
        System.setProperty(AsposePdfLogging.LOG_PROPERTY, "garbage");
        AsposePdfLogging.configureFromSystemProperty();
        assertEquals(Level.OFF, AsposePdfLogging.getLevel());
    }

    @Test
    void setLevelNull_silencesLibrary() {
        AsposePdfLogging.setLevel(Level.WARNING);
        AsposePdfLogging.setLevel(null);
        assertEquals(Level.OFF, AsposePdfLogging.getLevel());
    }

    @Test
    void off_suppressesChildLoggerWarning() {
        AsposePdfLogging.setLevel(Level.OFF);
        Logger child = Logger.getLogger("org.aspose.pdf.engine.parser.PDFParser");
        assertFalse(child.isLoggable(Level.WARNING),
                "With library logging OFF, an engine WARNING must be suppressed");
        assertFalse(child.isLoggable(Level.SEVERE),
                "With library logging OFF, even SEVERE is suppressed");
    }

    @Test
    void warning_allowsChildWarningButNotFine() {
        AsposePdfLogging.setLevel(Level.WARNING);
        Logger child = Logger.getLogger("org.aspose.pdf.engine.parser.PDFParser");
        assertTrue(child.isLoggable(Level.WARNING),
                "At WARNING level, engine warnings should pass");
        assertFalse(child.isLoggable(Level.FINE),
                "At WARNING level, recovery FINE detail should still be suppressed");
    }

    @Test
    void verbose_allowsChildFine() {
        AsposePdfLogging.setLevel(Level.FINE);
        Logger child = Logger.getLogger("org.aspose.pdf.engine.parser.PDFParser");
        assertTrue(child.isLoggable(Level.FINE),
                "At verbose/FINE level, recovery details should pass");
    }

    @Test
    void doesNotTouchRootLogger() {
        AsposePdfLogging.setLevel(Level.FINE);
        // The library must not alter the JUL root logger's level.
        Logger root = Logger.getLogger("");
        assertNotSame(Logger.getLogger("org.aspose.pdf"), root);
        // org.aspose.pdf must not propagate to root handlers.
        assertFalse(Logger.getLogger("org.aspose.pdf").getUseParentHandlers(),
                "Library logger must not use parent (root) handlers");
    }
}
