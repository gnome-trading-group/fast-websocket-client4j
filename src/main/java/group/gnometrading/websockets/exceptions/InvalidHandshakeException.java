package group.gnometrading.websockets.exceptions;

import group.gnometrading.websockets.enums.HandshakeState;

public class InvalidHandshakeException extends RuntimeException {
    public InvalidHandshakeException(HandshakeState handshakeState) {
        super(handshakeState.description);
    }
}
