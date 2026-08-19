package org.aspose.pdf.engine.pattern;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.image.BufferedImage;
import java.util.logging.Logger;

/**
 * Renders shading fills onto a Graphics2D context.
 * Handles the {@code sh} operator and Pattern color spaces with shading patterns.
 *
 * <p>For axial, radial, and function-based shadings, the renderer samples the function
 * at each pixel within the clipping bounds. For mesh shadings (types 4–7), a fallback
 * color is used.</p>
 */
public final class ShadingRenderer {

    private static final Logger LOG = Logger.getLogger(ShadingRenderer.class.getName());

    private ShadingRenderer() {}

    /**
     * Renders a shading fill onto the Graphics2D context.
     * Fills the current clipping region with the shading colors.
     *
     * @param g2d       the graphics context
     * @param shading   the shading to render
     * @param ctm       current transformation matrix (user space → device space)
     * @param clipBounds the clipping bounds in device space (may be {@code null})
     */
    public static void render(Graphics2D g2d, Shading shading,
                               AffineTransform ctm, java.awt.Rectangle clipBounds) {
        if (shading == null) return;
        if (clipBounds == null || clipBounds.width <= 0 || clipBounds.height <= 0) {
            clipBounds = g2d.getClipBounds();
            if (clipBounds == null) return;
        }

        if (shading instanceof AxialShading || shading instanceof RadialShading
                || shading instanceof FunctionBasedShading) {
            renderPixelBased(g2d, shading, ctm, clipBounds);
        } else if (shading instanceof FreeFormGouraudShading) {
            renderGouraudMesh(g2d, (FreeFormGouraudShading) shading, ctm);
        } else {
            renderFallback(g2d, shading, clipBounds);
        }
    }

    /**
     * Rasterizes a free-form Gouraud triangle mesh (ShadingType 4) in device
     * space: each triangle's vertices are mapped through the shading CTM and
     * filled with barycentric-interpolated colours. A mesh with no valid
     * triangles (malformed vertex stream) paints nothing — Acrobat drops such
     * shadings silently instead of substituting a fallback colour.
     */
    private static void renderGouraudMesh(Graphics2D g2d, FreeFormGouraudShading mesh,
                                          AffineTransform ctm) {
        java.util.List<FreeFormGouraudShading.Vertex[]> tris = mesh.getTriangles();
        if (tris.isEmpty()) return;

        AffineTransform base = g2d.getTransform();
        java.awt.Shape clipShape = g2d.getClip();
        if (clipShape == null) return;
        java.awt.Rectangle clip = base.createTransformedShape(clipShape).getBounds();
        if (clip.width <= 0 || clip.height <= 0
                || (long) clip.width * clip.height > MAX_SHADING_PIXELS) {
            return;
        }
        BufferedImage img = new BufferedImage(clip.width, clip.height,
                BufferedImage.TYPE_INT_ARGB);

        double[] pts = new double[6];
        for (FreeFormGouraudShading.Vertex[] t : tris) {
            if (Thread.currentThread().isInterrupted()) return;
            pts[0] = t[0].x; pts[1] = t[0].y;
            pts[2] = t[1].x; pts[3] = t[1].y;
            pts[4] = t[2].x; pts[5] = t[2].y;
            ctm.transform(pts, 0, pts, 0, 3);
            FreeFormGouraudShading.Vertex[] dev = new FreeFormGouraudShading.Vertex[]{
                    new FreeFormGouraudShading.Vertex(pts[0], pts[1], t[0].comps),
                    new FreeFormGouraudShading.Vertex(pts[2], pts[3], t[1].comps),
                    new FreeFormGouraudShading.Vertex(pts[4], pts[5], t[2].comps)};
            int minX = (int) Math.max(clip.x,
                    Math.floor(Math.min(pts[0], Math.min(pts[2], pts[4]))));
            int maxX = (int) Math.min(clip.x + clip.width - 1,
                    Math.ceil(Math.max(pts[0], Math.max(pts[2], pts[4]))));
            int minY = (int) Math.max(clip.y,
                    Math.floor(Math.min(pts[1], Math.min(pts[3], pts[5]))));
            int maxY = (int) Math.min(clip.y + clip.height - 1,
                    Math.ceil(Math.max(pts[1], Math.max(pts[3], pts[5]))));
            int n = t[0].comps.length;
            double[] c = new double[n];
            for (int py = minY; py <= maxY; py++) {
                for (int px = minX; px <= maxX; px++) {
                    double[] bary = FreeFormGouraudShading.barycentric(dev, px + 0.5, py + 0.5);
                    if (bary == null) continue;
                    for (int i = 0; i < n; i++) {
                        c[i] = bary[0] * t[0].comps[i] + bary[1] * t[1].comps[i]
                                + bary[2] * t[2].comps[i];
                    }
                    img.setRGB(px - clip.x, py - clip.y,
                            colorToARGB(c, mesh.getColorSpace()));
                }
            }
        }

        g2d.setTransform(new AffineTransform());
        try {
            g2d.drawImage(img, clip.x, clip.y, null);
        } finally {
            g2d.setTransform(base);
        }
    }

    /**
     * Renders a shading by sampling the function at each pixel.
     * Works for axial, radial, and function-based shadings.
     */
    private static void renderPixelBased(Graphics2D g2d, Shading shading,
                                          AffineTransform ctm, java.awt.Rectangle clipUser) {
        // ctm maps shading space → DEVICE pixels (base g2d transform × state
        // CTM), so sampling must walk device pixels. clipUser however is
        // expressed in g2d USER space — convert the clip (the precise clip
        // shape when one is set) to device space first. Feeding user-space
        // coords into the device-space inverse samples the wrong spot and,
        // with /Extend [false false], returns null everywhere — nothing
        // painted (corpus 28762: all chart gradient bars missing).
        AffineTransform base = g2d.getTransform();
        java.awt.Shape clipShape = g2d.getClip();
        java.awt.Rectangle clip = base.createTransformedShape(
                clipShape != null ? clipShape : clipUser).getBounds();
        int w = clip.width;
        int h = clip.height;
        if (w <= 0 || h <= 0) return;
        if ((long) w * h > MAX_SHADING_PIXELS) {
            LOG.fine(() -> "Shading area too large, skipping: " + clip);
            return;
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        AffineTransform inverse;
        try {
            inverse = ctm.createInverse();
        } catch (NoninvertibleTransformException e) {
            LOG.fine(() -> "Non-invertible CTM, using identity for shading");
            inverse = new AffineTransform();
        }

        double[] pt = new double[2];
        for (int py = 0; py < h; py++) {
            // Cancellation check per row: function-based shadings evaluate a
            // (possibly PostScript) function per pixel — minutes on big clips.
            if (Thread.currentThread().isInterrupted()) return;
            for (int px = 0; px < w; px++) {
                // Transform device pixel to shading coordinate space
                pt[0] = clip.x + px;
                pt[1] = clip.y + py;
                inverse.transform(pt, 0, pt, 0, 1);

                double[] color = shading.getColorAt(pt[0], pt[1]);
                if (color == null) {
                    continue; // outside the gradient, /Extend false → unpainted
                }
                int argb = colorToARGB(color, shading.getColorSpace());
                img.setRGB(px, py, argb);
            }
        }
        // Paint in device space: reset the transform for the blit. The g2d
        // clip still applies — Java2D tracks it in device space — so pixels
        // outside a non-rectangular clip path remain masked.
        g2d.setTransform(new AffineTransform());
        try {
            g2d.drawImage(img, clip.x, clip.y, null);
        } finally {
            g2d.setTransform(base);
        }
    }

    /** Upper bound on the sampled shading raster (≈ a few full pages at 300 dpi). */
    private static final long MAX_SHADING_PIXELS = 64L * 1024 * 1024;

    /**
     * Renders a fallback for mesh shadings (types 4–7): fills with background or gray.
     */
    private static void renderFallback(Graphics2D g2d, Shading shading,
                                         java.awt.Rectangle clip) {
        double[] bg = shading.getBackground();
        int argb;
        if (bg != null) {
            argb = colorToARGB(bg, shading.getColorSpace());
        } else {
            argb = 0xFF808080; // mid-gray fallback
        }
        java.awt.Color color = new java.awt.Color(argb, true);
        g2d.setColor(color);
        g2d.fillRect(clip.x, clip.y, clip.width, clip.height);
    }

    /**
     * Converts shading-function output to packed ARGB via the shading's
     * ColorSpace. The components are in the SHADING's color space — reading
     * them as RGB renders a DeviceCMYK blue {@code [1 .6 0 0]} as orange
     * (corpus 29077/10734); Separation/DeviceN need their tint transform.
     */
    private static int colorToARGB(double[] color,
                                   org.aspose.pdf.engine.colorspace.ColorSpaceBase cs) {
        if (color == null || color.length == 0) return 0xFF000000;
        if (cs != null) {
            try {
                return 0xFF000000 | cs.toRGBInt(color);
            } catch (Exception e) {
                LOG.fine(() -> "Shading colorspace conversion failed: " + e.getMessage());
            }
        }
        // No colorspace — fall back to mapping by component count.
        int r = clamp255(color[0]);
        int g = clamp255(color.length > 1 ? color[1] : color[0]);
        int b = clamp255(color.length > 2 ? color[2] : color[0]);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp255(double v) {
        return Math.max(0, Math.min(255, (int) (v * 255)));
    }
}
