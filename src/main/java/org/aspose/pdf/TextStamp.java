package org.aspose.pdf;

import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.facades.FormattedText;
import org.aspose.pdf.text.TextFormattingOptions;
import org.aspose.pdf.text.TextState;

import java.io.IOException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Represents a text stamp that can be overlaid on a PDF page.
 * <p>
 * A text stamp renders a text string at a specified position on the page,
 * with configurable styling via {@link TextState}, rotation, alignment,
 * and z-order (foreground or background).
 * </p>
 */
public class TextStamp extends Stamp {

    private static final Logger LOG = Logger.getLogger(TextStamp.class.getName());

    /** The font a text stamp uses when its {@link TextState} specifies none. */
    private static final String DEFAULT_FONT_NAME = "Helvetica";
    /** The font size a text stamp uses when its {@link TextState} specifies none. */
    private static final double DEFAULT_FONT_SIZE = 12.0;

    /**
     * Returns the default stamp font (Aspose {@code TextStamp.DefaultFont}) — the
     * font applied when a {@link FormattedText}/{@link TextState} specifies none.
     *
     * @return the default font
     */
    public static org.aspose.pdf.text.Font getDefaultFont() {
        return org.aspose.pdf.text.FontRepository.findFont(DEFAULT_FONT_NAME);
    }

    /**
     * Returns the default stamp font size in points (Aspose {@code TextStamp.DefaultFontSize}).
     *
     * @return the default font size
     */
    public static double getDefaultFontSize() {
        return DEFAULT_FONT_SIZE;
    }

    private String value;
    private TextState textState;
    private TextFormattingOptions.WordWrapMode wordWrapMode = TextFormattingOptions.WordWrapMode.Undefined;
    private HorizontalAlignment textAlignment = HorizontalAlignment.None;
    private transient PdfObjectReference cachedFormReference;
    private transient Document cachedTargetDocument;

    /**
     * Creates a new TextStamp with the given text value.
     *
     * @param value the text content of the stamp; must not be {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public TextStamp(String value) {
        Objects.requireNonNull(value, "value must not be null");
        this.value = value;
    }

    /**
     * Creates a new TextStamp from a {@link FormattedText} instance.
     * <p>
     * The text, font name, font size, and foreground color from the
     * formatted text are applied to this stamp's {@link TextState}.
     * </p>
     *
     * @param formattedText the formatted text to use; must not be {@code null}
     * @throws NullPointerException if {@code formattedText} is {@code null}
     */
    public TextStamp(FormattedText formattedText) {
        Objects.requireNonNull(formattedText, "formattedText must not be null");
        this.value = formattedText.getText();
        TextState ts = getTextState();
        if (formattedText.getFontName() != null) {
            ts.setFontName(formattedText.getFontName());
        }
        if (formattedText.getFontSize() > 0) {
            ts.setFontSize(formattedText.getFontSize());
        }
        if (formattedText.getTextColor() != null) {
            ts.setForegroundColor(formattedText.getTextColor());
        }
    }

    /**
     * Returns the text content of this stamp.
     *
     * @return the text value
     */
    public String getValue() {
        return value;
    }

    /**
     * Sets the text content of this stamp.
     *
     * @param value the text value; must not be {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setValue(String value) {
        Objects.requireNonNull(value, "value must not be null");
        this.value = value;
    }

    /**
     * Returns the text state for this stamp, creating it lazily if needed.
     * <p>
     * The text state controls font, size, color, and other text rendering properties.
     * </p>
     *
     * @return the text state; never {@code null}
     */
    public TextState getTextState() {
        if (textState == null) {
            textState = new TextState();
        }
        return textState;
    }

    /**
     * Sets the text state for this stamp.
     *
     * @param textState the text state
     */
    public void setTextState(TextState textState) {
        this.textState = textState;
    }

    /**
     * Returns the word wrap mode for this stamp's text.
     *
     * @return the word wrap mode
     */
    public TextFormattingOptions.WordWrapMode getWordWrapMode() {
        return wordWrapMode;
    }

    /**
     * Sets the word wrap mode for this stamp's text.
     *
     * @param wordWrapMode the word wrap mode
     */
    public void setWordWrapMode(TextFormattingOptions.WordWrapMode wordWrapMode) {
        this.wordWrapMode = wordWrapMode != null ? wordWrapMode : TextFormattingOptions.WordWrapMode.Undefined;
    }

    /**
     * Returns the text alignment within the stamp area.
     * This controls how multi-line text is aligned (left, center, right).
     *
     * @return the text alignment
     */
    public HorizontalAlignment getTextAlignment() {
        return textAlignment;
    }

    /**
     * Sets the text alignment within the stamp area.
     * This controls how multi-line text is aligned (left, center, right).
     *
     * @param textAlignment the text alignment
     */
    public void setTextAlignment(HorizontalAlignment textAlignment) {
        this.textAlignment = textAlignment != null ? textAlignment : HorizontalAlignment.None;
    }

    PdfObjectReference getCachedFormReference() {
        return cachedFormReference;
    }

    Document getCachedTargetDocument() {
        return cachedTargetDocument;
    }

    void cacheFormReference(Document targetDocument, PdfObjectReference formReference) {
        this.cachedTargetDocument = targetDocument;
        this.cachedFormReference = formReference;
    }

    /**
     * Applies this text stamp to the given page.
     * <p>
     * The rendering is delegated to {@link Page#addStamp(TextStamp)}.
     * </p>
     *
     * @param page the page to stamp; must not be {@code null}
     * @throws IOException if content stream generation fails
     */
    @Override
    public void put(Page page) throws IOException {
        if (page == null) throw new IllegalArgumentException("page must not be null");
        page.addStamp(this);
    }
}
