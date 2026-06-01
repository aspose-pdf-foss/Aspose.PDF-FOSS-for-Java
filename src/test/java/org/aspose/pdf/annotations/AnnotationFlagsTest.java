package org.aspose.pdf.annotations;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class AnnotationFlagsTest {

    @Test
    void enumValues_matchSpecBits() {
        assertEquals(1,   AnnotationFlags.Invisible.getBit());
        assertEquals(2,   AnnotationFlags.Hidden.getBit());
        assertEquals(4,   AnnotationFlags.Print.getBit());
        assertEquals(8,   AnnotationFlags.NoZoom.getBit());
        assertEquals(16,  AnnotationFlags.NoRotate.getBit());
        assertEquals(32,  AnnotationFlags.NoView.getBit());
        assertEquals(64,  AnnotationFlags.ReadOnly.getBit());
        assertEquals(128, AnnotationFlags.Locked.getBit());
        assertEquals(256, AnnotationFlags.ToggleNoView.getBit());
        assertEquals(512, AnnotationFlags.LockedContents.getBit());
    }

    @Test
    void fromBits_returnsCorrectFlags() {
        EnumSet<AnnotationFlags> empty = AnnotationFlags.fromBits(0);
        assertTrue(empty.isEmpty());

        EnumSet<AnnotationFlags> printOnly = AnnotationFlags.fromBits(4);
        assertEquals(EnumSet.of(AnnotationFlags.Print), printOnly);

        EnumSet<AnnotationFlags> mixed = AnnotationFlags.fromBits(4 | 64); // Print + ReadOnly
        assertEquals(EnumSet.of(AnnotationFlags.Print, AnnotationFlags.ReadOnly), mixed);

        EnumSet<AnnotationFlags> all = AnnotationFlags.fromBits(0x3FF);
        assertEquals(10, all.size());
    }

    @Test
    void toBits_encodes() {
        assertEquals(0, AnnotationFlags.toBits(EnumSet.noneOf(AnnotationFlags.class)));
        assertEquals(4, AnnotationFlags.toBits(EnumSet.of(AnnotationFlags.Print)));
        assertEquals(4 | 64,
                AnnotationFlags.toBits(EnumSet.of(AnnotationFlags.Print, AnnotationFlags.ReadOnly)));
    }

    @Test
    void roundTrip_bitsEnumBits() {
        int original = 1 | 4 | 64 | 256; // Invisible + Print + ReadOnly + ToggleNoView
        EnumSet<AnnotationFlags> set = AnnotationFlags.fromBits(original);
        assertEquals(original, AnnotationFlags.toBits(set));
    }

    @Test
    void unknownBits_ignored() {
        EnumSet<AnnotationFlags> high = AnnotationFlags.fromBits(0x40000); // bit 19, unmapped
        assertTrue(high.isEmpty());
    }
}
