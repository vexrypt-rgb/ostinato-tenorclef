/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.road;

/**
 * Pure client-visible terrain probe for road corridor survey and physical verification.
 * No Minecraft classes, so planning and verification are deterministic and unit-testable.
 */
public interface RoadProbe {

    CellKind get(int x, int y, int z);

    /**
     * Terrain classification relevant to level full-block road decks, headroom clearance, and ordinary edits.
     */
    enum CellKind {
        /** Empty passable space (air). */
        AIR,
        /** Non-colliding replaceable plant/cover (short grass, flowers, thin snow) — walkable and replaceable. */
        PASSABLE_PLANT,
        /** Ordinary full-cube solid block (stone, dirt, grass block, cobblestone, planks) — valid walkable deck. */
        SOLID_FULL,
        /** Breakable non-full or obstacle solid block (slab, stair, farmland, fence, leaves, dirt_path) — clearable. */
        SOLID_NON_FULL_CLEARABLE,
        /** Water or stream fluid — replaceable by placing a full deck block, impassable as a no-jump walking floor. */
        FLUID,
        /** Lava, fire, magma, cactus, berry bush, powder snow — never walkable or safe to build into. */
        HAZARD,
        /** Unbreakable full solid block (bedrock, barrier, end portal frame) — walkable as floor, impassable obstacle above. */
        UNBREAKABLE,
        /** Unloaded chunk or outside vertical world bounds — cannot assume safe or walkable. */
        UNKNOWN;

        /** True when a standing player can walk through this cell at feet or head level without clearing it. */
        public boolean isPassableHeadroom() {
            return this == AIR || this == PASSABLE_PLANT;
        }

        /** True when this cell is already a valid level full-block walking deck at {@code deckY}. */
        public boolean isWalkableFullDeck() {
            return this == SOLID_FULL || this == UNBREAKABLE;
        }

        /** True when an ordinary player can place a full deck block directly into this cell without breaking first. */
        public boolean isReplaceableForDeck() {
            return this == AIR || this == PASSABLE_PLANT || this == FLUID;
        }

        /** True when an ordinary player can break/clear this block (outside protected zones). */
        public boolean isClearableObstacle() {
            return this == SOLID_FULL || this == SOLID_NON_FULL_CLEARABLE;
        }

        /** True when an ordinary player can click a face of this block to place an adjacent deck block. */
        public boolean canPlaceAgainst() {
            return this == SOLID_FULL || this == SOLID_NON_FULL_CLEARABLE || this == UNBREAKABLE;
        }
    }
}
