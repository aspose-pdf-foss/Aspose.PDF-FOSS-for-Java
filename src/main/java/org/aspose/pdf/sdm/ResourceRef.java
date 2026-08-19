package org.aspose.pdf.sdm;

import java.util.Objects;

/**
 * Reference to an entry in the {@link ResourceTable} (IR spec §1.9). Nodes
 * carry refs; the bytes live in the table — the tree is never inflated with
 * payloads.
 */
public final class ResourceRef {

    private final String id;

    /**
     * Creates a resource reference.
     *
     * @param id the resource id within the table
     */
    public ResourceRef(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("resource id must not be null or empty");
        }
        this.id = id;
    }

    /**
     * Returns the resource id.
     *
     * @return the id
     */
    public String getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ResourceRef && id.equals(((ResourceRef) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "ResourceRef(" + id + ")";
    }
}
