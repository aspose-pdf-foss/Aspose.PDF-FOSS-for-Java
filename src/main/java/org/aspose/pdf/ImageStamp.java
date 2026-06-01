package org.aspose.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Represents an image stamp that can be overlaid on a PDF page.
 * <p>
 * An image stamp renders an image at a specified position on the page,
 * with configurable dimensions, rotation, and z-order (foreground or background).
 * </p>
 */
public class ImageStamp extends Stamp {

    private static final Logger LOG = Logger.getLogger(ImageStamp.class.getName());

    private String file;
    private InputStream imageStream;

    /**
     * Creates a new ImageStamp from a file path.
     *
     * @param file the path to the image file; must not be {@code null}
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public ImageStamp(String file) {
        Objects.requireNonNull(file, "file must not be null");
        this.file = file;
    }

    /**
     * Creates a new ImageStamp from an input stream of encoded image bytes
     * (JPEG, PNG, BMP or GIF). The stream is read when the stamp is applied
     * to a page via {@link Page#addStamp(ImageStamp)}.
     *
     * @param imageStream the image data stream; must not be {@code null}
     * @throws NullPointerException if {@code imageStream} is {@code null}
     */
    public ImageStamp(InputStream imageStream) {
        this.imageStream = Objects.requireNonNull(imageStream, "imageStream must not be null");
    }

    /**
     * Returns the file path of the image.
     *
     * @return the file path
     */
    public String getFile() {
        return file;
    }

    /**
     * Sets the file path of the image.
     *
     * @param file the file path
     */
    public void setFile(String file) {
        this.file = file;
    }

    /**
     * Returns the input stream providing the image data.
     *
     * @return the image stream, or {@code null}
     */
    public InputStream getImageStream() {
        return imageStream;
    }

    /**
     * Sets the input stream providing the image data.
     *
     * @param imageStream the image stream
     */
    public void setImageStream(InputStream imageStream) {
        this.imageStream = imageStream;
    }

    /**
     * Applies this image stamp to the given page.
     * <p>
     * The rendering is delegated to {@link Page#addStamp(ImageStamp)}.
     * </p>
     *
     * @param page the page to stamp; must not be {@code null}
     * @throws IOException if image loading or content stream generation fails
     */
    @Override
    public void put(Page page) throws IOException {
        if (page == null) throw new IllegalArgumentException("page must not be null");
        page.addStamp(this);
    }
}
