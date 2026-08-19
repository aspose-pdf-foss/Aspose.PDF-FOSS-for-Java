package org.aspose.pdf;

/**
 * Options for {@link Document#merge(java.util.List, MergeOptions)}.
 */
public final class MergeOptions {

    private boolean flowCompaction;
    private CompactionOptions compactionOptions = new CompactionOptions();

    /**
     * Creates options with flow compaction enabled: after concatenation the
     * merged document is compacted (holes closed, content flowing across page
     * boundaries like HTML, emptied pages removed).
     *
     * @return the options
     */
    public static MergeOptions withFlowCompaction() {
        MergeOptions options = new MergeOptions();
        options.flowCompaction = true;
        return options;
    }

    /**
     * Returns whether flow compaction runs after concatenation.
     *
     * @return true when enabled
     */
    public boolean isFlowCompaction() {
        return flowCompaction;
    }

    /**
     * Sets whether flow compaction runs after concatenation.
     *
     * @param flowCompaction true to enable
     * @return this
     */
    public MergeOptions setFlowCompaction(boolean flowCompaction) {
        this.flowCompaction = flowCompaction;
        return this;
    }

    /**
     * Returns the compaction options used when flow compaction is enabled.
     *
     * @return the options (never null)
     */
    public CompactionOptions getCompactionOptions() {
        return compactionOptions;
    }

    /**
     * Sets the compaction options.
     *
     * @param compactionOptions the options
     * @return this
     */
    public MergeOptions setCompactionOptions(CompactionOptions compactionOptions) {
        this.compactionOptions = compactionOptions == null
                ? new CompactionOptions() : compactionOptions;
        return this;
    }
}
