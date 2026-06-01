package org.aspose.pdf;

import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSFloat;
import org.aspose.pdf.engine.cos.COSInteger;
import org.aspose.pdf.engine.cos.COSName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Represents a PDF content stream operator (e.g., "BT", "Tf", "Td", "Tj", "q", "Q", "cm", "re").
 * <p>
 * A content stream is a sequence of operators, each preceded by zero or more operands.
 * This class captures both the operator keyword and its operands as COS objects.
 * See ISO 32000-1:2008, §7.8.2.
 * </p>
 * <p>
 * Typed subclasses in {@code org.aspose.pdf.operators} provide convenience
 * constructors and typed getters for each PDF operator.
 * </p>
 */
public class Operator {

    private static final Logger LOGGER = Logger.getLogger(Operator.class.getName());

    private final String name;
    private final List<COSBase> operands;
    private int index = -1;

    /**
     * Creates an operator with the given name and operands.
     *
     * @param name     the operator keyword (e.g., "BT", "Tf", "cm")
     * @param operands the operands preceding this operator in the content stream
     * @throws IllegalArgumentException if name is null or empty
     */
    public Operator(String name, List<COSBase> operands) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Operator name must not be null or empty");
        }
        this.name = name;
        this.operands = operands != null
                ? Collections.unmodifiableList(new ArrayList<>(operands))
                : Collections.emptyList();
    }

    /**
     * Creates an operator with no operands.
     *
     * @param name the operator keyword (e.g., "BT", "ET", "q", "Q")
     * @throws IllegalArgumentException if name is null or empty
     */
    public Operator(String name) {
        this(name, Collections.emptyList());
    }

    /**
     * Returns the operator keyword.
     *
     * @return the operator name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the index of this operator within its parent {@link OperatorCollection},
     * or -1 if not set.
     *
     * @return the zero-based index, or -1 if unknown
     */
    public int getIndex() {
        return index;
    }

    /**
     * Sets the index of this operator within its parent collection.
     * Typically called by {@link OperatorCollection}.
     *
     * @param index the zero-based index
     */
    public void setIndex(int index) {
        this.index = index;
    }

    /**
     * Returns the operands as an unmodifiable list.
     *
     * @return the operands
     */
    public List<COSBase> getOperands() {
        return operands;
    }

    /**
     * Returns a string representation of this operator in content stream syntax.
     * For example: "BT", "12 0 0 12 100 700 cm", "/F1 12 Tf".
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        if (operands.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        for (COSBase operand : operands) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(formatOperand(operand));
        }
        sb.append(' ');
        sb.append(name);
        return sb.toString();
    }

    /**
     * Writes this operator's content-stream serialization to {@code os} preserving
     * exact operand bytes. Unlike {@link #toString()} (which routes operands through
     * a {@code US-ASCII} String and replaces any byte &ge; 0x80), this delegates to
     * each operand's {@link COSBase#writeTo(OutputStream)} directly — so COSString
     * operands carrying high bytes (CID/Identity-H glyph codes, MacRoman, raw binary)
     * survive a serialize→reparse round-trip intact. ISO 32000-1:2008 §7.8.2.
     *
     * @param os the output stream to write to
     * @throws IOException if writing fails
     */
    public void writeTo(OutputStream os) throws IOException {
        boolean first = true;
        for (COSBase operand : operands) {
            if (!first) {
                os.write(' ');
            }
            operand.writeTo(os);
            first = false;
        }
        if (!operands.isEmpty()) {
            os.write(' ');
        }
        // Operator keywords are always ASCII (ISO 32000-1 §7.8.2).
        os.write(name.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Operator)) return false;
        Operator other = (Operator) o;
        return name.equals(other.name) && operands.equals(other.operands);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, operands);
    }

    // ---- Static helper methods for subclasses ----

    /**
     * Extracts a numeric value from a COS object.
     *
     * @param val the COS object
     * @return the numeric value, or 0 if not a number
     */
    protected static double getNumber(COSBase val) {
        if (val instanceof COSInteger) return ((COSInteger) val).intValue();
        if (val instanceof COSFloat) return ((COSFloat) val).doubleValue();
        return 0;
    }

    /**
     * Creates a COSBase number — COSInteger for whole numbers, COSFloat otherwise.
     *
     * @param v the numeric value
     * @return the COS number object
     */
    protected static COSBase num(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < Long.MAX_VALUE) {
            return COSInteger.valueOf((long) v);
        }
        return new COSFloat(v);
    }

    /**
     * Creates an operand list from two coordinate values.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @return a two-element list
     */
    protected static List<COSBase> coords(double x, double y) {
        return Arrays.asList(num(x), num(y));
    }

    /**
     * Creates an operand list from a Matrix's six values.
     *
     * @param m the matrix
     * @return a six-element list
     */
    protected static List<COSBase> matrixToOperands(Matrix m) {
        double[] v = m.getValues();
        return Arrays.asList(num(v[0]), num(v[1]), num(v[2]), num(v[3]), num(v[4]), num(v[5]));
    }

    /**
     * Formats a single operand for content stream output.
     */
    private String formatOperand(COSBase operand) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            operand.writeTo(baos);
            return baos.toString("US-ASCII");
        } catch (IOException e) {
            return operand.toString();
        }
    }
}
