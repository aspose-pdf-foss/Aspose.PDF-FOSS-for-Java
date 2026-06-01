package org.aspose.pdf;

import org.aspose.pdf.operators.IOperatorSelector;

import java.util.ArrayList;
import java.util.List;

/**
 * Selects operators of a specific runtime type from an
 * {@link OperatorCollection}.
 * <p>
 * Implements the visitor pattern: construct a selector with a prototype
 * operator, pass it to {@link OperatorCollection#accept(IOperatorSelector)},
 * then read the matches via {@link #getSelected()}. An operator matches when it
 * is an instance of the prototype's runtime class. Because Aspose operator
 * types form a flat sibling hierarchy (e.g. {@code ShowText},
 * {@code SetGlyphsPositionShowText}, … all extend {@code TextShowOperator}
 * directly), this is equivalent to an exact-type match for the operator types
 * the API exposes.
 * </p>
 * <p>
 * Mirrors Aspose.PDF for .NET {@code OperatorSelector} (ISO 32000-1:2008, §8.2).
 * </p>
 */
public class OperatorSelector implements IOperatorSelector {

    private final Class<? extends Operator> prototypeType;
    private final List<Operator> selected = new ArrayList<>();

    /**
     * Creates a selector that matches operators of the same runtime type as the
     * given prototype.
     *
     * @param prototype operator whose runtime class is used as the filter
     * @throws IllegalArgumentException if {@code prototype} is null
     */
    public OperatorSelector(Operator prototype) {
        if (prototype == null) {
            throw new IllegalArgumentException("Prototype operator must not be null");
        }
        this.prototypeType = prototype.getClass();
    }

    /**
     * Visitor callback invoked by {@link OperatorCollection#accept}. Adds the
     * operator to the selection if it matches the prototype's type.
     *
     * @param op the operator being visited
     */
    @Override
    public void visit(Operator op) {
        if (op != null && prototypeType.isInstance(op)) {
            selected.add(op);
        }
    }

    /**
     * Returns the (mutable) list of operators selected so far. Mirrors the C#
     * {@code Selected} property; callers may iterate or {@code addAll} from it.
     *
     * @return the list of selected operators
     */
    public List<Operator> getSelected() {
        return selected;
    }
}
