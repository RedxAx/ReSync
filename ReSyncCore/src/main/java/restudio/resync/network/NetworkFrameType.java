package restudio.resync.network;

public enum NetworkFrameType {
    ENROLL(1),
    ENROLL_ACK(2),
    HEARTBEAT(3),
    NODE_STATUS(4),
    PRESENCE_SNAPSHOT(10),
    PRESENCE_DELTA(11),
    VARIABLE_GET(20),
    VARIABLE_SET(21),
    VARIABLE_CHANGED(22),
    EVENT_PUBLISH(30),
    EVENT_DELIVERY(31),
    EVENT_ACK(32),
    TRANSFER_INTENT(40),
    LEASE_GRANTED(41),
    SNAPSHOT_COMMIT(42),
    TARGET_READY(43),
    PLAYER_CONNECTED(44),
    STATE_APPLIED(45),
    TRANSFER_COMMIT(46),
    TRANSFER_ABORT(47),
    TRANSFER_RECOVER(48),
    ROUTE_RECONCILE(50),
    NODE_MODE_SET(51),
    PROXY_ACTION(52),
    PLAYER_ROUTE(53),
    OWNER_CLAIM(60),
    OWNER_SNAPSHOT(61),
    SNAPSHOT_LIST(62),
    SNAPSHOT_READ(63),
    SNAPSHOT_PIN(64),
    SNAPSHOT_RESTORE(65),
    STATE_RECONCILE(66),
    RESPONSE(100),
    ERROR(101);

    private final int code;

    NetworkFrameType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static NetworkFrameType fromCode(int code) {
        for (NetworkFrameType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown Network Frame Type " + code);
    }
}
