package org.aspose.pdf.sdm;

/**
 * One entry of a {@link TocBlock}.
 */
public final class TocEntry {

    private final int level;
    private final String text;
    private final String targetId;

    /**
     * Creates a TOC entry.
     *
     * @param level    the nesting level (1-based)
     * @param text     the entry text
     * @param targetId the GUID of the target node, or null if unresolved
     */
    public TocEntry(int level, String text, String targetId) {
        this.level = Math.max(1, level);
        this.text = text == null ? "" : text;
        this.targetId = targetId;
    }

    /**
     * Returns the nesting level.
     *
     * @return the level
     */
    public int getLevel() {
        return level;
    }

    /**
     * Returns the entry text.
     *
     * @return the text
     */
    public String getText() {
        return text;
    }

    /**
     * Returns the target node GUID.
     *
     * @return the target id, or null
     */
    public String getTargetId() {
        return targetId;
    }
}
