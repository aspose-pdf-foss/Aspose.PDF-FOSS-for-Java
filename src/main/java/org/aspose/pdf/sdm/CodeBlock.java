package org.aspose.pdf.sdm;

/**
 * Preformatted code block (IR spec §1.3).
 */
public final class CodeBlock extends SdmBlock {

    private final String text;
    private final String language;

    /**
     * Creates a code block.
     *
     * @param text     the verbatim code text
     * @param language the language tag, or null
     */
    public CodeBlock(String text, String language) {
        super(SdmNodeType.CODE_BLOCK);
        this.text = text == null ? "" : text;
        this.language = language;
    }

    /**
     * Returns the verbatim code text.
     *
     * @return the text
     */
    public String getText() {
        return text;
    }

    /**
     * Returns the language tag.
     *
     * @return the language, or null
     */
    public String getLanguage() {
        return language;
    }
}
