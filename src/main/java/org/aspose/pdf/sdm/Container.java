package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;

/**
 * Grouping container (Div/Sect; IR spec §1.3).
 */
public final class Container extends SdmBlock {

    private final String role;
    private final List<SdmBlock> children = new ArrayList<>();

    /**
     * Creates a container.
     *
     * @param role the semantic role hint (e.g. "Div", "Sect"), or null
     */
    public Container(String role) {
        super(SdmNodeType.CONTAINER);
        this.role = role;
    }

    /**
     * Returns the semantic role hint.
     *
     * @return the role, or null
     */
    public String getRole() {
        return role;
    }

    /**
     * Returns the mutable child blocks.
     *
     * @return the children
     */
    public List<SdmBlock> getChildren() {
        return children;
    }
}
