package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;

/**
 * Heading block, level 1..6 (IR spec §1.3).
 */
public final class Heading extends SdmBlock {

    private final int level;
    private final List<SdmInline> inline = new ArrayList<>();

    /**
     * Creates a heading.
     *
     * @param level the heading level, clamped to 1..6
     */
    public Heading(int level) {
        super(SdmNodeType.HEADING);
        this.level = Math.max(1, Math.min(6, level));
    }

    /**
     * Returns the heading level (1..6).
     *
     * @return the level
     */
    public int getLevel() {
        return level;
    }

    /**
     * Returns the mutable inline content.
     *
     * @return the inline nodes
     */
    public List<SdmInline> getInline() {
        return inline;
    }
}
