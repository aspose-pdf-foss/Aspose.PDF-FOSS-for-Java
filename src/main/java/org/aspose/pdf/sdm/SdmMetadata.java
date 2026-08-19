package org.aspose.pdf.sdm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Document metadata (IR spec §1.2).
 */
public final class SdmMetadata {

    private String title;
    private String author;
    private String lang;
    private String created;
    private String modified;
    private final Map<String, String> custom = new LinkedHashMap<>();

    /**
     * Returns the document title.
     *
     * @return the title, or null
     */
    public String getTitle() {
        return title;
    }

    /**
     * Sets the document title.
     *
     * @param title the title
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * Returns the document author.
     *
     * @return the author, or null
     */
    public String getAuthor() {
        return author;
    }

    /**
     * Sets the document author.
     *
     * @param author the author
     */
    public void setAuthor(String author) {
        this.author = author;
    }

    /**
     * Returns the document language tag.
     *
     * @return the language, or null
     */
    public String getLang() {
        return lang;
    }

    /**
     * Sets the document language tag.
     *
     * @param lang the language
     */
    public void setLang(String lang) {
        this.lang = lang;
    }

    /**
     * Returns the creation timestamp (ISO-8601 or PDF date string).
     *
     * @return the created stamp, or null
     */
    public String getCreated() {
        return created;
    }

    /**
     * Sets the creation timestamp.
     *
     * @param created the created stamp
     */
    public void setCreated(String created) {
        this.created = created;
    }

    /**
     * Returns the modification timestamp.
     *
     * @return the modified stamp, or null
     */
    public String getModified() {
        return modified;
    }

    /**
     * Sets the modification timestamp.
     *
     * @param modified the modified stamp
     */
    public void setModified(String modified) {
        this.modified = modified;
    }

    /**
     * Returns the mutable custom key/value metadata.
     *
     * @return the custom map
     */
    public Map<String, String> getCustom() {
        return custom;
    }
}
