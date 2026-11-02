package net.alloymc.core.arcade.games.bedwars;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Solace's model: size, part budget and that every animated group and claw is there. */
class SolaceModelTest {
    @Test void modelIsTitanSizedButWithinItsPartBudget() {
        assertTrue(SolaceModel.height() >= 8 && SolaceModel.height() <= 10.5, "height " + SolaceModel.height());
        assertTrue(SolaceModel.halfWidth() <= 5, "half-width " + SolaceModel.halfWidth());
        assertTrue(SolaceModel.PARTS.size() <= 100, "too many display entities: " + SolaceModel.PARTS.size());
    }

    @Test void everyGroupHasValidParts() {
        Set<SolaceModel.Group> groups = EnumSet.noneOf(SolaceModel.Group.class);
        for (SolaceModel.Part p : SolaceModel.PARTS) {
            groups.add(p.group());
            assertTrue(p.sx() > 0 && p.sy() > 0 && p.sz() > 0, "zero-size part " + p);
            assertTrue(p.block().startsWith("minecraft:"), "block id without namespace: " + p.block());
        }
        assertEquals(EnumSet.allOf(SolaceModel.Group.class), groups);
    }

    @Test void bothHandsHaveFourTwoPartClaws() {
        for (SolaceModel.Group arm : new SolaceModel.Group[]{SolaceModel.Group.ARM_L, SolaceModel.Group.ARM_R}) {
            long claws = SolaceModel.PARTS.stream().filter(p -> p.group() == arm && p.block().equals("minecraft:bone_block")).count();
            assertEquals(8, claws, arm + " claw segments");
        }
    }
}
