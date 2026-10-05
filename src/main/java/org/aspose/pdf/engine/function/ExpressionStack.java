package org.aspose.pdf.engine.function;

import java.util.ArrayList;
import java.util.List;

/**
 * A pre-seedable operand stack for {@link Evaluator#eval(String, ExpressionStack)}.
 *
 * <p>API-compatible with Aspose.Pdf
 * {@code Aspose.Pdf.Engine.Functions.PostScript.ExpressionStack}. Values pushed here
 * become the initial stack (bottom first) before the program runs.</p>
 */
public final class ExpressionStack {

    private final List<Double> values = new ArrayList<>();

    /** Pushes a value onto the stack. */
    public void push(double value) {
        values.add(value);
    }

    /** Pushes a boolean (true = 1, false = 0) onto the stack. */
    public void push(boolean value) {
        values.add(value ? 1.0 : 0.0);
    }

    /** Returns the seeded values, bottom element first. */
    double[] toArray() {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
