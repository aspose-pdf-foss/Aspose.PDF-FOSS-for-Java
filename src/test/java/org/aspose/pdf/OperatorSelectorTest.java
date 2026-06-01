package org.aspose.pdf;

import org.aspose.pdf.operators.ClosePathStroke;
import org.aspose.pdf.operators.Fill;
import org.aspose.pdf.operators.ShowText;
import org.aspose.pdf.operators.Stroke;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OperatorSelector} and the
 * {@link OperatorCollection#accept} / {@link OperatorCollection#delete(java.util.List)}
 * visitor API (Sprint 46).
 */
class OperatorSelectorTest {

    @Test
    void selectsMatchingTypeOnly() {
        OperatorCollection oc = new OperatorCollection();
        oc.add(new Stroke());
        oc.add(new Fill());
        oc.add(new Stroke());
        oc.add(new ShowText("x"));

        OperatorSelector sel = new OperatorSelector(new Stroke());
        oc.accept(sel);

        assertEquals(2, sel.getSelected().size());
        for (Operator op : sel.getSelected()) {
            assertTrue(op instanceof Stroke);
        }
    }

    @Test
    void selectsNothingWhenNoMatch() {
        OperatorCollection oc = new OperatorCollection();
        oc.add(new Fill());

        OperatorSelector sel = new OperatorSelector(new Stroke());
        oc.accept(sel);

        assertEquals(0, sel.getSelected().size());
    }

    @Test
    void deleteByListRemovesSelectedOperators() {
        OperatorCollection oc = new OperatorCollection();
        Stroke s1 = new Stroke();
        Fill f1 = new Fill();
        Stroke s2 = new Stroke();
        oc.add(s1);
        oc.add(f1);
        oc.add(s2);

        oc.delete(Arrays.asList(s1, s2));

        assertEquals(1, oc.size());
        assertSame(f1, oc.get(1));
    }

    @Test
    void deleteByListPreservesIdentitySemantics() {
        // Two equal-by-value Stroke instances must NOT both be deleted by reference
        OperatorCollection oc = new OperatorCollection();
        Stroke s1 = new Stroke();
        Stroke s2 = new Stroke();
        oc.add(s1);
        oc.add(s2);

        oc.delete(Arrays.asList(s1));

        assertEquals(1, oc.size());
        assertSame(s2, oc.get(1));
    }

    @Test
    void selectorNullPrototypeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new OperatorSelector(null));
    }

    @Test
    void collectViaSelectorThenDelete() {
        // End-to-end mirror of the C# PDFNET_47255 pattern.
        OperatorCollection oc = new OperatorCollection();
        oc.add(new Stroke());
        oc.add(new ClosePathStroke());
        oc.add(new Fill());
        oc.add(new ShowText("keep me"));

        java.util.List<Operator> list = new java.util.ArrayList<>();
        Operator[] prototypes = { new Stroke(), new ClosePathStroke(), new Fill() };
        for (Operator proto : prototypes) {
            OperatorSelector os = new OperatorSelector(proto);
            oc.accept(os);
            list.addAll(os.getSelected());
        }
        assertEquals(3, list.size());
        oc.delete(list);

        assertEquals(1, oc.size());
        assertTrue(oc.get(1) instanceof ShowText);
    }
}
