package group.gnometrading.websockets.enums;

public enum HandshakeState {
    MATCHED(null),
    INVALID_WRITE("IO error while sending handshake to server"),
    INVALID_READ("IO error while reading handshake from the server"),
    INCOMPLETE("Handshake is being sent in multiple packets"),
    SOCKET_CLOSED("The socket's IO stream prematurely closed"),
    TOO_LARGE("The handshake sent from the server was too large for the buffer"),
    INVALID_PROTOCOL("An invalid protocol was sent by the server"),
    MISSING_HEADERS("There were missing headers in the handshake response"),
    TIMEOUT("The handshake attempt expired"),
    UNKNOWN("Unknown error occurred during the handshake");

    public final String description;

    HandshakeState(String description) {
        this.description = description;
    }
}
