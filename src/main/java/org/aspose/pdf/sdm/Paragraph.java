package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;

/**
 * Paragraph block (IR spec §1.3): a sequence of inline nodes.
 */
public final class Paragraph extends SdmBlock {

    private final List<SdmInline> inline = new ArrayList<>();

    /**
     * Creates an empty paragraph.
     */
    public Paragraph() {
        super(SdmNodeType.PARAGRAPH);
    }

    /**
     * Returns the mutable inline content.
     *
     * @return the inline nodes
     */
    public List<SdmInline> getInline() {
        return inline;
    }

    /**
     * Returns the concatenated text of all {@link Run} children (convenience).
     *
     * @return the plain text
     */
    public String getText() {
        StringBuilder sb = new StringBuilder();
        for (SdmInline in : inline) {
            if (in instanceof Run) {
                sb.append(((Run) in).getText());
            } else if (in instanceof LineBreak) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
