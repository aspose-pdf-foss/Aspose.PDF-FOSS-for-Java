package org.aspose.pdf.operators;

import org.aspose.pdf.Operator;

/**
 * Visitor interface for traversing an {@code OperatorCollection}.
 * <p>
 * Implementations are passed to
 * {@link org.aspose.pdf.OperatorCollection#accept(IOperatorSelector)}, which
 * invokes {@link #visit(Operator)} once for every operator in the collection
 * (ISO 32000-1:2008, §8.2). Mirrors the Aspose.PDF for .NET
 * {@code IOperatorSelector} visitor.
 * </p>
 */
public interface IOperatorSelector {

    /**
     * Called by {@code OperatorCollection.accept} for each operator in turn.
     *
     * @param operator the operator being visited
     */
    void visit(Operator operator);
}
