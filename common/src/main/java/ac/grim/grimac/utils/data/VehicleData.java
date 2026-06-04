package ac.grim.grimac.utils.data;

import ac.grim.grimac.utils.data.packetentity.JumpableEntity;
import ac.grim.grimac.utils.enums.BoatEntityStatus;
import com.github.retrooper.packetevents.util.Vector3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedQueue;

public class VehicleData {
    public boolean boatUnderwater = false;
    public double lastYd;
    public double midTickY;
    public float landFriction;
    public BoatEntityStatus status;
    public BoatEntityStatus oldStatus;
    public double waterLevel;
    public float deltaRotation;
    public float nextVehicleHorizontal = 0f;
    public float nextVehicleForward = 0f;
    public float vehicleHorizontal = 0f;
    public float vehicleForward = 0f;
    public boolean lastDummy = false;
    public boolean wasVehicleSwitch = false;
    public float playerPitch = 0f;
    public float playerYaw = 0f;
    public double lastVehiclePacketY = 0;
    public boolean highSpeedBoatOnBlocks = false;
    public boolean hasLastLegitBoatPosition = false;
    public double lastLegitX;
    public double lastLegitY;
    public double lastLegitZ;
    public float lastLegitYaw;
    public float lastLegitPitch;
    public final Deque<IntToObjectPair<JumpableEntity>> pendingJumps = new ArrayDeque<>();
    public final ConcurrentLinkedQueue<IntToObjectPair<Vector3d>> vehicleTeleports = new ConcurrentLinkedQueue<>();
    public SprintingState camelSprintingState = SprintingState.STOPPED;
}
