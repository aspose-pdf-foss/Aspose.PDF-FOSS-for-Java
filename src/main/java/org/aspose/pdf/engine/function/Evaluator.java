package org.aspose.pdf.engine.function;

/**
 * Evaluator for standalone PostScript calculator expressions (ISO 32000-1:2008
 * §7.10.5.2 — Type 4 function operators), exposed as a stack machine over a string.
 *
 * <p>API-compatible with Aspose.Pdf {@code Aspose.Pdf.Engine.Functions.PostScript.Evaluator}.
 * Delegates to the shared {@link PostScriptFunction} stack machine and returns the full
 * result stack, bottom element first.</p>
 *
 * <pre>{@code
 * Value[] r = Evaluator.eval("10 20 add");   // r[0].intValue() == 30
 * }</pre>
 */
public final class Evaluator {

    private Evaluator() {
    }

    /** Evaluates {@code expression} on an empty stack. */
    public static Value[] eval(String expression) {
        return eval(expression, null);
    }

    /**
     * Evaluates {@code expression} on top of {@code seed} (may be {@code null}).
     *
     * @return the resulting stack as values, index 0 = bottom of the stack
     */
    public static Value[] eval(String expression, ExpressionStack seed) {
        double[] seedValues = seed != null ? seed.toArray() : null;
        double[] stack = PostScriptFunction.evalToStack(expression, seedValues);
        Value[] result = new Value[stack.length];
        for (int i = 0; i < stack.length; i++) {
            result[i] = new Value(stack[i]);
        }
        return result;
    }
}
