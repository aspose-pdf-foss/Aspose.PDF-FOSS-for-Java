package org.aspose.pdf.sdm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Document-wide resource store (IR spec §1.9): payload bytes live here, nodes
 * carry {@link ResourceRef}s.
 */
public final class ResourceTable {

    private final Map<String, Resource> resources = new LinkedHashMap<>();

    /**
     * Stores a resource and returns the reference to it. Re-putting the same id
     * replaces the entry.
     *
     * @param id       the resource id
     * @param resource the resource
     * @return a reference to the stored resource
     */
    public ResourceRef put(String id, Resource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("resource must not be null");
        }
        resources.put(id, resource);
        return new ResourceRef(id);
    }

    /**
     * Resolves a reference to its resource.
     *
     * @param ref the reference
     * @return the resource, or null if absent
     */
    public Resource get(ResourceRef ref) {
        return ref == null ? null : resources.get(ref.getId());
    }

    /**
     * Resolves a resource id directly.
     *
     * @param id the resource id
     * @return the resource, or null if absent
     */
    public Resource get(String id) {
        return resources.get(id);
    }

    /**
     * Returns an unmodifiable view of all entries.
     *
     * @return the entries by id
     */
    public Map<String, Resource> entries() {
        return Collections.unmodifiableMap(resources);
    }

    /**
     * Returns the number of stored resources.
     *
     * @return the size
     */
    public int size() {
        return resources.size();
    }
}
