package ac.grim.grimac.utils.collisions.blocks;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.CollisionFactory;
import ac.grim.grimac.utils.collisions.datatypes.NoCollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.enums.Half;

public class TrapDoorHandler implements CollisionFactory {
    @Override
    public CollisionBox fetch(GrimPlayer player, ClientVersion version, WrappedBlockState block, int x, int y, int z) {
        double thickness = 0.1875;

        if (block.isOpen()) {
            BlockFace facing = block.getFacing();
            if (facing == BlockFace.SOUTH) {
                return new SimpleCollisionBox(0.0, 0.0, 0.0, 1.0, 1.0, thickness, false);
            }
            if (facing == BlockFace.NORTH) {
                return new SimpleCollisionBox(0.0, 0.0, 1.0 - thickness, 1.0, 1.0, 1.0, false);
            }
            if (facing == BlockFace.EAST) {
                return new SimpleCollisionBox(0.0, 0.0, 0.0, thickness, 1.0, 1.0, false);
            }
            if (facing == BlockFace.WEST) {
                return new SimpleCollisionBox(1.0 - thickness, 0.0, 0.0, 1.0, 1.0, 1.0, false);
            }
        } else if (block.getHalf() == Half.BOTTOM) {
            return new SimpleCollisionBox(0.0, 0.0, 0.0, 1.0, thickness, 1.0, false);
        } else {
            return new SimpleCollisionBox(0.0, 1.0 - thickness, 0.0, 1.0, 1.0, 1.0, false);
        }

        return NoCollisionBox.INSTANCE;
    }
}
