package org.aspose.pdf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Represents a sequence of operators from a PDF content stream.
 * <p>
 * Provides indexed access, iteration, and mutation of the operator list.
 * See ISO 32000-1:2008, §7.8.2.
 * </p>
 */
public final class OperatorCollection implements Iterable<Operator> {

    private static final Logger LOGGER = Logger.getLogger(OperatorCollection.class.getName());

    private final List<Operator> operators;

    /**
     * Creates a collection from an existing list of operators.
     *
     * @param operators the initial operators
     * @throws IllegalArgumentException if operators is null
     */
    public OperatorCollection(List<Operator> operators) {
        if (operators == null) {
            throw new IllegalArgumentException("Operators list must not be null");
        }
        this.operators = new ArrayList<>(operators);
    }

    /**
     * Creates an empty operator collection.
     */
    public OperatorCollection() {
        this.operators = new ArrayList<>();
    }

    /**
     * Returns the number of operators in this collection.
     *
     * @return the size
     */
    public int size() {
        return operators.size();
    }

    /**
     * Returns the operator at the given <em>1-based</em> position
     * (Aspose-style indexing — {@code ops.get(1)} is the first operator).
     * <p>Internal engine call-sites that previously used 0-based indexing
     * should switch to {@link #getAt(int)} for explicit 0-based access or
     * use the iterator/{@link #getAll()} list.</p>
     *
     * @param index the 1-based index
     * @return the operator at that index
     * @throws IndexOutOfBoundsException if index is not in [1, size()]
     */
    public Operator get(int index) {
        if (index < 1 || index > operators.size()) {
            throw new IndexOutOfBoundsException(
                    "Operator index " + index + " out of range [1, " + operators.size() + "]");
        }
        return operators.get(index - 1);
    }

    /**
     * Returns the operator at the given <em>0-based</em> position. Provided
     * for engine code that walks the operator list in for-loops; public API
     * users should prefer {@link #get(int)} (1-based) for Aspose parity.
     *
     * @param index the 0-based index
     * @return the operator at that index
     */
    public Operator getAt(int index) {
        return operators.get(index);
    }

    /** Engine-side 0-based setter; pair with {@link #getAt(int)}. */
    public void setAt(int index, Operator op) {
        if (op == null) {
            throw new IllegalArgumentException("Operator must not be null");
        }
        operators.set(index, op);
    }

    /** Engine-side 0-based remove; pair with {@link #getAt(int)}. */
    public void removeAt(int index) {
        operators.remove(index);
    }

    /** Engine-side 0-based insert; pair with {@link #getAt(int)}. */
    public void addAt(int index, Operator op) {
        if (op == null) {
            throw new IllegalArgumentException("Operator must not be null");
        }
        operators.add(index, op);
    }

    /**
     * Appends an operator to the end of this collection.
     *
     * @param op the operator to add
     * @throws IllegalArgumentException if op is null
     */
    public void add(Operator op) {
        if (op == null) {
            throw new IllegalArgumentException("Operator must not be null");
        }
        operators.add(op);
    }

    /**
     * Appends every operator from {@code other} to this collection. Mirrors
     * the C# {@code Contents.Add(IEnumerable&lt;Operator&gt;)} overload.
     *
     * @param other operators to append
     */
    public void add(java.util.Collection<? extends Operator> other) {
        if (other == null) {
            return;
        }
        for (Operator op : other) {
            add(op);
        }
    }

    /**
     * Replaces the operator at the specified <em>1-based</em> index.
     *
     * @param index the 1-based index
     * @param op    the replacement operator
     * @throws IllegalArgumentException  if op is null
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public void set(int index, Operator op) {
        if (op == null) {
            throw new IllegalArgumentException("Operator must not be null");
        }
        if (index < 1 || index > operators.size()) {
            throw new IndexOutOfBoundsException(
                    "Operator index " + index + " out of range [1, " + operators.size() + "]");
        }
        operators.set(index - 1, op);
    }

    /**
     * Inserts an operator at the specified <em>1-based</em> index. Inserting
     * at position {@code size()+1} appends.
     *
     * @param index the 1-based index at which to insert
     * @param op    the operator to insert
     * @throws IllegalArgumentException  if op is null
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public void insert(int index, Operator op) {
        if (op == null) {
            throw new IllegalArgumentException("Operator must not be null");
        }
        if (index < 1 || index > operators.size() + 1) {
            throw new IndexOutOfBoundsException(
                    "Operator insertion index " + index + " out of range [1, " + (operators.size() + 1) + "]");
        }
        operators.add(index - 1, op);
    }

    /**
     * Removes the operator at the specified <em>1-based</em> index.
     *
     * @param index the 1-based index
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public void delete(int index) {
        if (index < 1 || index > operators.size()) {
            throw new IndexOutOfBoundsException(
                    "Operator index " + index + " out of range [1, " + operators.size() + "]");
        }
        operators.remove(index - 1);
    }

    /**
     * Removes every operator contained in {@code toDelete} from this
     * collection, matching by reference identity (not {@code equals}). Mirrors
     * the C# {@code OperatorCollection.Delete(List&lt;Operator&gt;)} overload,
     * which is typically fed the {@code Selected} list of one or more
     * {@link OperatorSelector}s.
     * <p>
     * Identity semantics matter: two distinct operator instances that happen to
     * be equal by value are treated as different — only the exact instances in
     * {@code toDelete} are removed.
     * </p>
     *
     * @param toDelete operators to remove; null or empty is a no-op
     */
    public void delete(java.util.List<Operator> toDelete) {
        if (toDelete == null || toDelete.isEmpty()) {
            return;
        }
        java.util.Set<Operator> deleteSet =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        deleteSet.addAll(toDelete);
        operators.removeIf(deleteSet::contains);
    }

    /**
     * Visitor entry point: invokes {@link IOperatorSelector#visit(Operator)}
     * for each operator in this collection, in order. Mirrors the C#
     * {@code OperatorCollection.Accept(IOperatorSelector)}.
     *
     * @param visitor the selector that processes each operator
     * @throws IllegalArgumentException if {@code visitor} is null
     */
    public void accept(org.aspose.pdf.operators.IOperatorSelector visitor) {
        if (visitor == null) {
            throw new IllegalArgumentException("Visitor must not be null");
        }
        for (Operator op : operators) {
            visitor.visit(op);
        }
    }

    /** Removes every operator from this collection. */
    public void clear() {
        operators.clear();
    }

    /**
     * Returns an iterator over the operators.
     *
     * @return an iterator
     */
    @Override
    public Iterator<Operator> iterator() {
        return operators.iterator();
    }

    /**
     * Returns an unmodifiable view of all operators.
     *
     * @return unmodifiable list of operators
     */
    public List<Operator> getAll() {
        return Collections.unmodifiableList(operators);
    }

    /**
     * Returns a string representation of all operators, one per line.
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("OperatorCollection[size=").append(operators.size()).append("]{");
        for (int i = 0; i < operators.size(); i++) {
            if (i > 0) sb.append(", ");
            // Internal list access; debug-only, must stay 0-based.
            sb.append(operators.get(i));
        }
        sb.append('}');
        return sb.toString();
    }
}
