package group.gnometrading.websockets;

public interface WebSocketListener {
    /**
     * Sends when the socket is connected and active. This will send before `SocketState == OPEN`.
     */
    default void onConnect() {}

    /**
     * Sends from an unexpected, non-fatal error from writing to the websocket.
     * @param e exception thrown
     */
    default void onWriteError(Exception e) {}

    /**
     * Sends when the server manually closes the connection. This will not trigger an automatic reconnect.
     */
    default void onClose() {}

    /**
     * Sends when the socket is timed out due to no message in a certain amount of milliseconds. This will
     * be followed by an automatic reconnect.
     */
    default void onTimeout() {}
}
