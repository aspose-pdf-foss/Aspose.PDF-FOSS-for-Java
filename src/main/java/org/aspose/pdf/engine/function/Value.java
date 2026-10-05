package org.aspose.pdf.engine.function;

/**
 * A single value on a PostScript calculator stack (see {@link Evaluator}).
 *
 * <p>API-compatible with Aspose.Pdf {@code Aspose.Pdf.Engine.Functions.PostScript.Value}.
 * The calculator stack machine operates on doubles (booleans are 1.0/0.0), so a
 * {@code Value} wraps a double and exposes typed accessors.</p>
 */
public final class Value {

    private final double value;

    public Value(double value) {
        this.value = value;
    }

    /** Returns the value truncated to an int (C# {@code (int)value}). */
    public int intValue() {
        return (int) value;
    }

    /** Returns the value as a double (C# {@code (double)value}). */
    public double doubleValue() {
        return value;
    }

    /** Returns the value as a float. */
    public float floatValue() {
        return (float) value;
    }

    /** Returns the value as a boolean (non-zero is {@code true}). */
    public boolean booleanValue() {
        return value != 0.0;
    }

    /**
     * Returns whether this value is an unexecuted procedure literal. The calculator
     * always reduces to scalar operands, so this is always {@code false}
     * (API-compatible with Aspose's {@code Value.IsExpression}).
     */
    public boolean isExpression() {
        return false;
    }

    @Override
    public String toString() {
        return value == Math.rint(value) && !Double.isInfinite(value)
                ? Long.toString((long) value)
                : Double.toString(value);
    }
}
