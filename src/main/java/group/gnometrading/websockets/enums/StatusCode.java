package group.gnometrading.websockets.enums;

/**
 * Status codes as defined <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-7.4.1">here</a>).
 */
public enum StatusCode {
    NORMAL_CLOSURE((short) 1000),
    GOING_AWAY((short) 1001),
    PROTOCOL_ERROR((short) 1002),
    REFUSE((short) 1003),
    NO_STATUS((short) 1005),
    ABNORMAL_CLOSE((short) 1006),
    INCONSISTENT_DATA((short) 1007),
    POLICY_VIOLATION((short) 1008),
    MESSAGE_TOO_BIG((short) 1009),
    EXTENSION((short) 1010),
    UNEXPECTED((short) 1011),
    INVALID_TLS_HANDSHAKE((short) 1015);

    public final short code;

    StatusCode(short code) {
        this.code = code;
    }
}
