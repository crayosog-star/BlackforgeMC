package net.blackforge.core.survival;

import net.blackforge.core.build.Canvas;
import net.blackforge.core.build.Walkability;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Gilded Grove's build rules (no server needed). */
class GildedGroveTest {
    private static List<String> all(Canvas c) {
        List<String> out = new ArrayList<>(c.blocks().values());
        out.addAll(c.lateBlocks().values());
        return out;
    }

    @Test void buildIsDeterministicSoUndoRemovesExactlyWhatApplyPlaced() {
        Canvas a = GildedGrove.build(), b = GildedGrove.build();
        assertEquals(a.blocks(), b.blocks());
        assertEquals(a.lateBlocks(), b.lateBlocks());
    }

    @Test void everyLeafIsPersistent() {
        for (String s : all(GildedGrove.build())) if (s.contains("leaves")) assertTrue(s.contains("persistent=true"), s);
    }

    @Test void nothingBurnsOrKeepsGrowing() {
        for (String s : all(GildedGrove.build())) {
            String id = s.replace("minecraft:", "");
            assertFalse(id.startsWith("lava") || id.equals("fire") || id.contains("campfire") || id.contains("vine") || id.equals("magma_block"), s);
        }
    }

    @Test void warpLandsOnFloorWithHeadroom() {
        Canvas c = GildedGrove.build();
        int x = (int) Math.floor(KingdomLayout.GROVE_WARP_X), y = (int) KingdomLayout.GROVE_WARP_Y, z = KingdomLayout.GROVE_WARP_Z;
        assertNotNull(c.get(x, y - 1, z));
        assertNull(c.get(x, y, z));
        assertNull(c.get(x, y + 1, z));
    }

    @Test void walkingAroundIsSafe() {
        Walkability.Report r = new Walkability(GildedGrove.build()).run(KingdomLayout.GROVE_WARP_X, KingdomLayout.GROVE_WARP_Y, KingdomLayout.GROVE_WARP_Z + 0.5);
        assertTrue(r.ok(), r.summary("gilded grove"));
    }

    @Test void everyFrameHangsInAirOnASolidBlock() {
        Canvas c = GildedGrove.build();
        List<SellCatalog.FrameSlot> slots = SellCatalog.gallery();
        assertEquals(SellCatalog.ITEMS.size(), slots.size());
        java.util.Set<SellCatalog.FrameSlot> unique = new java.util.HashSet<>(slots);
        assertEquals(slots.size(), unique.size());
        for (SellCatalog.FrameSlot s : slots) {
            int x = KingdomLayout.GROVE_X + s.dx(), y = KingdomLayout.GROVE_Y + s.dy(), z = KingdomLayout.GROVE_Z + s.dz();
            assertNull(c.get(x, y, z), s.toString());
            int bx = x, bz = z;
            switch (s.facing()) { case "NORTH" -> bz++; case "SOUTH" -> bz--; case "EAST" -> bx--; default -> bx++; }
            assertNotNull(c.get(bx, y, bz), s.toString());
        }
    }

    @Test void groveRegionIsWhereItWasPlanned() {
        double y = KingdomLayout.GROVE_Y + 1;
        assertTrue(KingdomLayout.inGrove(KingdomLayout.GROVE_X, y, KingdomLayout.GROVE_Z));
        assertTrue(KingdomLayout.inGrove(-60, y, KingdomLayout.GROVE_BRIDGE_Z));
        assertFalse(KingdomLayout.inGrove(300, y, 300));
    }
}
