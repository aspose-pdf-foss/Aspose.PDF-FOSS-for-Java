package org.aspose.pdf.sdm.xlsx;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExcelFormulaTranslator} — Excel formula &rarr; Acrobat JS.
 * Pure in-memory logic; reads no files.
 */
class ExcelFormulaTranslatorTest {

    /** A resolver that names a cell "R{row}C{col}" for the current sheet, "S:{sheet}:{row}:{col}" for a qualified one. */
    private static ExcelFormulaTranslator t() {
        return new ExcelFormulaTranslator("Sheet1", (sheet, row, col) ->
                sheet == null ? "R" + row + "C" + col : "Q_" + sheet + "_" + row + "_" + col);
    }

    @Test
    void arithmeticAndRefs() {
        ExcelFormulaTranslator.Result r = t().translate("B4*C4");
        // B4 -> row3,col1 ; C4 -> row3,col2
        assertEquals("(AXL.f(\"R3C1\")*AXL.f(\"R3C2\"))", r.js);
        assertTrue(r.refs.contains("R3C1"));
        assertTrue(r.refs.contains("R3C2"));
        assertEquals(2, r.refs.size());
    }

    @Test
    void leadingEqualsStripped() {
        assertEquals("(AXL.f(\"R0C0\")+1)", t().translate("=A1+1").js);
    }

    @Test
    void sumRangeExpands() {
        ExcelFormulaTranslator.Result r = t().translate("SUM(D4:D7)");
        // D4..D7 -> rows 3..6, col 3
        assertEquals("AXL.sum(AXL.f(\"R3C3\"),AXL.f(\"R4C3\"),AXL.f(\"R5C3\"),AXL.f(\"R6C3\"))", r.js);
        assertEquals(4, r.refs.size());
    }

    @Test
    void ifWithStringBranches() {
        String js = t().translate("IF(E2>=60,\"Pass\",\"Fail\")").js;
        assertEquals("AXL.iff((AXL.f(\"R1C4\")>=60),\"Pass\",\"Fail\")", js);
    }

    @Test
    void ifComparisonEquals() {
        String js = t().translate("IF(A1=0,1,2)").js;
        assertEquals("AXL.iff((AXL.f(\"R0C0\")==0),1,2)", js);
    }

    @Test
    void roundNested() {
        assertEquals("AXL.round((AXL.f(\"R8C1\")/AXL.f(\"R6C1\")),2)",
                t().translate("ROUND(B9/B7,2)").js);
    }

    @Test
    void absoluteRefsSameAsRelative() {
        assertEquals(t().translate("B3*B4*B5").js, t().translate("$B$3*$B$4*$B$5").js);
    }

    @Test
    void percentPostfix() {
        assertEquals("(AXL.f(\"R0C0\")*(20/100))", t().translate("A1*20%").js);
    }

    @Test
    void powerOperator() {
        assertEquals("AXL.pow(AXL.f(\"R0C0\"),2)", t().translate("A1^2").js);
    }

    @Test
    void stringConcatAmp() {
        assertEquals("(String(AXL.s(\"R0C0\"))+String(\"!\"))", t().translate("A1&\"!\"").js);
    }

    @Test
    void concatenateFunctionUsesStringAccessor() {
        assertEquals("AXL.concat(AXL.s(\"R0C0\"),\" \",AXL.s(\"R0C1\"))",
                t().translate("CONCATENATE(A1,\" \",B1)").js);
    }

    @Test
    void crossSheetReference() {
        ExcelFormulaTranslator.Result r = t().translate("Sheet2!B2+1");
        assertEquals("(AXL.f(\"Q_Sheet2_1_1\")+1)", r.js);
    }

    @Test
    void unknownFunctionThrows() {
        assertThrows(ExcelFormulaTranslator.UnsupportedFormulaException.class,
                () -> t().translate("VLOOKUP(A1,B1:C9,2,FALSE)"));
    }

    @Test
    void garbageThrows() {
        assertThrows(ExcelFormulaTranslator.UnsupportedFormulaException.class,
                () -> t().translate("A1 B1 C1 @@"));
    }

    @Test
    void runtimeLibraryLooksSane() {
        String lib = ExcelFormulaTranslator.runtimeLibrary();
        assertTrue(lib.contains("AXL"));
        assertTrue(lib.contains("getField"));
        assertTrue(lib.contains("iff:function"));
    }
}
