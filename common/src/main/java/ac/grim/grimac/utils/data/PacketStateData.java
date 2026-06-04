package ac.grim.grimac.utils.data;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.util.Vector3d;
import lombok.Getter;

// This is to keep all the packet data out of the main player class
// Helps clean up the player class and makes devs aware they are sync'd to the netty thread
public class PacketStateData {
    public boolean packetPlayerOnGround = false;
    public boolean lastPacketWasTeleport = false;
    public boolean cancelDuplicatePacket, lastPacketWasOnePointSeventeenDuplicate = false;
    public boolean lastTransactionPacketWasValid = false;
    public int lastSlotSelected;
    public InteractionHand itemInUseHand = InteractionHand.MAIN_HAND;
    public long lastRiptide = 0;
    public boolean tryingToRiptide = false;
    public int slowedByUsingItemTransaction = Integer.MIN_VALUE;
    public boolean receivedSteerVehicle = false;
    // This works on 1.8 only
    public boolean didLastLastMovementIncludePosition = false;
    public boolean didLastMovementIncludePosition = false;
    // This works on 1.21.2+ only
    public boolean didSendMovementBeforeTickEnd = false;
    public KnownInput knownInput = KnownInput.DEFAULT;
    public Vector3d lastClaimedPosition = new Vector3d(0, 0, 0);
    public float lastHealth, lastSaturation;
    public int lastFood;
    public boolean lastServerTransWasValid = false;
    @Getter
    private int slowedByUsingItemSlot = Integer.MIN_VALUE;
    public boolean sendingBundlePacket;
    public boolean showsDeathScreen = true;

    // If true, the player's rotation was forced to the horse's rotation only on 1.13-
    public boolean horseInteractCausedForcedRotation = false;

    // 2b2t.org.ru fork movement / pearl / break state
    public int fallBufferTicks = 0;
    public int ticksSinceOnGround = 0;
    public int consecutiveAirTicks = 0;
    public int consecutiveHoverAirTicks = 0;
    public int consecutiveAscendAirTicks = 0;
    public int consecutiveStrictAirAscendTicks = 0;
    public boolean wasOnGroundLastStrafeTick = true;
    public double airMomentumHorizLimit = 0;
    public int pearlPhaseGraceTicks = 0;
    public long lastAirTickUpdateMovementPacket = -1;
    public boolean hasLastLegitOnFootPosition = false;
    public double lastLegitOnFootX;
    public double lastLegitOnFootY;
    public double lastLegitOnFootZ;
    public float lastLegitOnFootYaw;
    public float lastLegitOnFootPitch;
    public int elytraGlideEndCoastTicks = 0;
    public int elytraFireworkBoostTicks = 0;
    public boolean crystalPlaceThisTick = false;
    public int breakDigPacketsThisTick = 0;
    public int breakDigRateLimitTickId = Integer.MIN_VALUE;
    public boolean breakStartAcknowledged = false;
    public boolean breakFinishUsed = false;
    public com.github.retrooper.packetevents.util.Vector3i activeBreakPosition;
    public com.github.retrooper.packetevents.util.Vector3i cancelledBreakPosition;
    public com.github.retrooper.packetevents.util.Vector3i lastCompletedBreakPosition;
    public int lastCompletedBreakTick = Integer.MIN_VALUE;
    public int breakStartTick = Integer.MIN_VALUE;
    public long breakStartTimeMs = 0L;
    public int breakFinishCountThisTick = 0;
    public com.github.retrooper.packetevents.util.Vector3i breakFinishPositionThisTick;

    /** Per-server-tick hard strafe barrier (30 km/h + micro gap). */
    public int hardStrafeServerTickId = Integer.MIN_VALUE;
    public boolean hardStrafeBarrierLocked = false;
    public int hardStrafeMovementPacketsThisTick = 0;
    public boolean hasHardStrafeTickStart = false;
    public double hardStrafeTickStartX;
    public double hardStrafeTickStartY;
    public double hardStrafeTickStartZ;
    public float hardStrafeTickStartYaw;
    public float hardStrafeTickStartPitch;

    /** Speed2b2t — fly-style horizontal cap (30 km/h, anti lag-split). */
    public int speed2b2tServerTickId = Integer.MIN_VALUE;
    public boolean speed2b2tTickLocked = false;
    public boolean hasSpeed2b2tTickStart = false;
    public double speed2b2tTickStartX;
    public double speed2b2tTickStartY;
    public double speed2b2tTickStartZ;
    public float speed2b2tTickStartYaw;
    public float speed2b2tTickStartPitch;
    public int consecutiveGroundOverspeedTicks = 0;
    public int consecutiveAirOverspeedTicks = 0;

    /** Step2b2t: cumulative ascend within one step sequence (anti 2×1 block). */
    public boolean hasStepAscendAnchorY = false;
    public double stepAscendAnchorY;

    public void setSlowedByUsingItem(boolean slowedByUsingItem) {
        slowedByUsingItemSlot = slowedByUsingItem ? lastSlotSelected : Integer.MIN_VALUE;
    }

    public boolean isSlowedByUsingItem() {
        return slowedByUsingItemSlot != Integer.MIN_VALUE;
    }
}
