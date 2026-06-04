package ac.grim.grimac.modifications;

public enum BoatMoveVerdict {
    /** Speed <= 60 km/h on solid blocks — sync position for vanilla. */
    ALLOW,
    /** Horizontal speed above 0.83 blocks/tick. */
    SPEED_EXCEEDED,
    /** Boat in air with illegal vertical or horizontal movement. */
    FLY_VIOLATION
}
