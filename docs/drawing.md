# Drawing API — graphs and vector shapes

The `org.aspose.pdf.drawing` package provides a lightweight object model for
building vector drawings: a `Graph` canvas that holds a collection of geometric
`Shape` objects — `Line`, `Rectangle`, `Circle`, `Arc`, `Curve`, `Ellipse` and a
composite `Path`. Each shape carries its own styling (`GraphInfo`: stroke color,
fill color, line width, dash pattern), and the canvas enforces an optional
container-bounds constraint that throws `BoundsOutOfRangeException` when a shape
does not fit.

This page targets Java 11+ and version 26.7. The library has zero third-party
dependencies. All public types live under `org.aspose.pdf` and its subpackages.

> Scope note (read first). In this release the drawing package is a geometry and
> styling model with bounds validation. `Graph` extends `BaseParagraph` and can
> be added to a page's paragraph collection, but the page layout/save pipeline in
> this build does not yet emit content-stream operators for `Graph` shapes — see
> **Notes & limitations**. The API shown below is real and compiles; use it to
> assemble and validate drawings today, and treat rendering as forward-looking.

## Coordinate system

All coordinates are in PDF user-space units (points, 1/72 inch) and follow the
PDF convention: the origin is at the **bottom-left**, x increases to the right,
y increases upward. Coordinates on a shape are relative to the `Graph` canvas —
a shape at `(0, 0)` sits at the canvas's lower-left corner. Because `Circle`,
`Arc`, `Rectangle` and `Ellipse` are all anchored by lower-left / center points
in this same upward-y space, "top" always means the larger y value.

## Create a Graph and add it to a page

A `Graph` is a drawing canvas of a fixed width and height. Add shapes to its
`ShapeCollection` (via `getShapes()`), then add the graph to a page's paragraph
collection.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.drawing.Graph;
import org.aspose.pdf.drawing.Rectangle;

public class GraphExample {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();      // pages are 1-based

            // A 200 x 100 pt canvas.
            Graph graph = new Graph(200, 100);

            // A rectangle: lower-left (10,10), 180 wide, 80 tall.
            Rectangle rect = new Rectangle(10, 10, 180, 80);
            graph.getShapes().add(rect);

            // Attach the canvas to the page.
            page.getParagraphs().add(graph);

            doc.save("graph.pdf");
        }
    }
}
```

`Graph` also exposes positioning and styling: `setLeft`/`setTop`,
`setBorder(BorderInfo)`, `setTitle(String)`, and a canvas-level
`getGraphInfo()`/`setGraphInfo(GraphInfo)`.

## Styling: stroke color, fill color, line width, dashes

Every `Shape` (and the `Graph` itself) has a `GraphInfo`. Set the stroke color
with `setColor`, the fill color with `setFillColor`, the outline thickness with
`setLineWidth`, and a dash pattern with `setDashArray` (alternating dash/gap
lengths) plus `setDashPhase`.

```java
import org.aspose.pdf.Color;
import org.aspose.pdf.drawing.GraphInfo;
import org.aspose.pdf.drawing.Rectangle;

Rectangle box = new Rectangle(10, 10, 180, 80);
GraphInfo gi = box.getGraphInfo();
gi.setColor(Color.getBlue());              // stroke (outline)
gi.setFillColor(Color.fromRgb(0.9, 0.9, 1.0)); // fill (closed shapes)
gi.setLineWidth(2.0f);                     // outline width in points
gi.setDashArray(new float[]{4f, 2f});      // 4 on, 2 off
gi.setDashPhase(0f);
```

`Color` factory methods include `fromRgb(double, double, double)` (components in
0..1), `fromRgbBytes(int, int, int)` (0..255), `fromGray`, `fromCmyk`,
`fromArgb`, `fromHtml(String)`, and named constants such as `Color.getBlue()`,
`Color.getRed()`, `Color.getBlack()`. Leaving `fillColor` unset (`null`) means
the shape is stroked only; leaving `color` unset means no explicit outline color.

`GraphInfo` additionally carries transform-style fields — `setRotationAngle`,
`setScalingRateX`/`setScalingRateY`, `setSkewAngleX`/`setSkewAngleY`, and
`setDoubled` — that describe how a shape should be transformed when rendered.

## The shapes

### Line (and polyline)

A `Line` is defined by an array of alternating x,y pairs. Four values make a
single segment `(x1,y1,x2,y2)`; more pairs make a polyline. The array must have
an even length of at least four elements, or the constructor throws
`IllegalArgumentException`.

```java
import org.aspose.pdf.Color;
import org.aspose.pdf.drawing.Line;

Line line = new Line(new float[]{0, 0, 200, 100});
line.getGraphInfo().setColor(Color.getBlack());
line.getGraphInfo().setLineWidth(1.5f);
graph.getShapes().add(line);

// A polyline (three points):
Line poly = new Line(new float[]{0, 0, 100, 80, 200, 20});
graph.getShapes().add(poly);
```

### Rectangle

Anchored by its lower-left corner `(left, bottom)` with `width` and `height`.
Set `setRoundedCornerRadius` for rounded corners (0 = sharp).

```java
import org.aspose.pdf.drawing.Rectangle;

Rectangle r = new Rectangle(10, 10, 120, 60);
r.setRoundedCornerRadius(8f);
graph.getShapes().add(r);
```

### Circle

Defined by its center `(posX, posY)` and `radius`.

```java
import org.aspose.pdf.Color;
import org.aspose.pdf.drawing.Circle;

Circle c = new Circle(100, 50, 40);
c.getGraphInfo().setFillColor(Color.fromRgb(1.0, 0.85, 0.4));
c.getGraphInfo().setColor(Color.getBlack());
graph.getShapes().add(c);
```

### Arc

Defined by center `(posX, posY)`, `radius`, and an angular range in **degrees**
from `startAngle` to `endAngle`. Angles are measured in the standard math sense
(0° along +x, increasing counter-clockwise). The arc's bounding box is computed
from its actual extent, so bounds checking accounts for the swept range rather
than the full circle.

```java
import org.aspose.pdf.drawing.Arc;

Arc quarter = new Arc(100, 20, 60, 0, 90);   // quarter arc, 0°..90°
graph.getShapes().add(quarter);
```

### Curve (cubic Bezier)

Defined by an array of alternating x,y control-point coordinates. A cubic Bezier
uses 8 values: start, two control points, end
`(x1,y1, cx1,cy1, cx2,cy2, x2,y2)`. The array must have an even length or the
constructor throws `IllegalArgumentException`.

```java
import org.aspose.pdf.drawing.Curve;

Curve curve = new Curve(new float[]{0, 0, 50, 100, 150, 100, 200, 0});
graph.getShapes().add(curve);
```

### Ellipse

Defined by its bounding box: lower-left corner `(left, bottom)` plus `width` and
`height`. When width equals height it degenerates to a circle.

```java
import org.aspose.pdf.drawing.Ellipse;

Ellipse e = new Ellipse(10, 10, 180, 60);
graph.getShapes().add(e);
```

### Path (composite)

`Path` is a `Shape` that groups child shapes. Add children through
`getShapes()` (a mutable `java.util.List<Shape>`); bounds checking delegates to
each child.

```java
import org.aspose.pdf.drawing.Path;
import org.aspose.pdf.drawing.Line;

Path p = new Path();
p.getShapes().add(new Line(new float[]{0, 0, 40, 40}));
p.getShapes().add(new Line(new float[]{40, 40, 80, 0}));
graph.getShapes().add(p);
```

## Container bounds and BoundsOutOfRangeException

A `Graph`'s `ShapeCollection` can validate that each shape fits inside the
canvas. Bounds checking is **off by default** (`BoundsCheckMode.Default`). Turn
it on with `updateBoundsCheckMode(BoundsCheckMode.ThrowExceptionIfDoesNotFit)`;
after that, `add(...)` calls `checkBounds(canvasWidth, canvasHeight)` and throws
`BoundsOutOfRangeException` (an unchecked `RuntimeException`) for any shape whose
geometry extends past the canvas edges or into negative coordinates.

```java
import org.aspose.pdf.drawing.BoundsCheckMode;
import org.aspose.pdf.drawing.BoundsOutOfRangeException;
import org.aspose.pdf.drawing.Circle;
import org.aspose.pdf.drawing.Graph;

Graph graph = new Graph(200, 100);
graph.getShapes().updateBoundsCheckMode(
        BoundsCheckMode.ThrowExceptionIfDoesNotFit);

try {
    // Circle at (180,50) r=40 → right edge at 220 > canvas width 200.
    graph.getShapes().add(new Circle(180, 50, 40));
} catch (BoundsOutOfRangeException ex) {
    System.out.println("Does not fit: " + ex.getMessage());
}
```

The container dimensions used for checks are the width/height passed to the
`Graph` constructor and are readable via `getShapes().getContainerWidth()` and
`getContainerHeight()`.

## Gradient shadings

`GradientAxialShading` models a linear (axial) gradient between two colors:

```java
import org.aspose.pdf.Color;
import org.aspose.pdf.drawing.GradientAxialShading;

GradientAxialShading shading =
        new GradientAxialShading(Color.getBlue(), Color.getWhite());
shading.setStartColor(Color.fromRgb(0.2, 0.4, 0.9));
shading.setEndColor(Color.fromRgb(1.0, 1.0, 1.0));
```

`PatternColorSpace` is a placeholder for pattern/shading color spaces and
currently exposes only a base `Color` (`getColor`/`setColor`).

> There is **no** `GradientRadialShading` type in this release. `GradientAxialShading`
> and `PatternColorSpace` hold the color endpoints/base color only; they are not
> yet attached to shapes or emitted into shading dictionaries — see below.

## A small chart-like example

Assembling a simple bar chart: a baseline, three filled bars of differing
heights, and a bounding rectangle. This builds and validates the model
end-to-end.

```java
import org.aspose.pdf.Color;
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.drawing.BoundsCheckMode;
import org.aspose.pdf.drawing.Graph;
import org.aspose.pdf.drawing.Line;
import org.aspose.pdf.drawing.Rectangle;

public class BarChart {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();

            Graph graph = new Graph(240, 140);
            graph.getShapes().updateBoundsCheckMode(
                    BoundsCheckMode.ThrowExceptionIfDoesNotFit);

            // Baseline axis at y = 10.
            Line axis = new Line(new float[]{10, 10, 230, 10});
            axis.getGraphInfo().setColor(Color.getBlack());
            axis.getGraphInfo().setLineWidth(1.5f);
            graph.getShapes().add(axis);

            // Three bars of different heights.
            int[] heights = {40, 90, 60};
            Color barFill = Color.fromRgb(0.2, 0.5, 0.85);
            for (int i = 0; i < heights.length; i++) {
                float x = 30 + i * 60;
                Rectangle bar = new Rectangle(x, 10, 40, heights[i]);
                bar.getGraphInfo().setFillColor(barFill);
                bar.getGraphInfo().setColor(Color.getBlack());
                bar.getGraphInfo().setLineWidth(1.0f);
                graph.getShapes().add(bar);
            }

            page.getParagraphs().add(graph);
            doc.save("bar-chart.pdf");
        }
    }
}
```

## Notes & limitations

- **Rendering is not wired up in this build.** `Graph` extends `BaseParagraph`
  and can be added via `page.getParagraphs().add(graph)`, but the page layout
  engine in this release does not emit PDF content-stream operators for drawing
  shapes. Use the drawing package now to construct and validate geometry and
  styling; do not rely on shapes appearing in the saved PDF yet. The `save(...)`
  call above produces a valid document, but the `Graph` content is not painted.
- **Coordinates are bottom-left origin** and expressed in points relative to the
  `Graph` canvas.
- **Bounds checking is opt-in.** Default mode (`BoundsCheckMode.Default`) performs
  no validation and lets shapes extend past the canvas. Enable
  `ThrowExceptionIfDoesNotFit` to have `add(...)` throw `BoundsOutOfRangeException`.
- **`Line` and `Curve` constructors validate their arrays** (non-null, even
  length; `Line` also requires at least 4 elements) and throw
  `IllegalArgumentException` otherwise.
- **Gradients are partial.** Only `GradientAxialShading` exists (start/end
  color); there is no radial gradient, and shadings/`PatternColorSpace` are not
  yet applied to shapes.
- **`GraphInfo` transform fields** (`rotationAngle`, `scalingRateX/Y`,
  `skewAngleX/Y`, `doubled`) are stored on the model but are not applied by a
  renderer in this build.
- **Naming.** `org.aspose.pdf.drawing.Rectangle` (a drawable shape) is distinct
  from `org.aspose.pdf.Rectangle` (a coordinate/bounding-box type). Import the
  correct one for your use.

## See also

- `org.aspose.pdf.Color` — color factories and named constants used for stroke
  and fill.
- `org.aspose.pdf.BorderInfo` — border styling that can be attached to a `Graph`
  via `setBorder`.
- `org.aspose.pdf.Page` / `org.aspose.pdf.Paragraphs` — the page paragraph
  collection a `Graph` is added to.
