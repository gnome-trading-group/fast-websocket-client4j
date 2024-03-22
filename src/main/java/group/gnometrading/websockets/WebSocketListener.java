package group.gnometrading.websockets;

public interface WebSocketListener {
    /**
     * Sends when the socket is connected and active.
     */
    void onConnect();

    /**
     * Sends from an unexpected, non-fatal error from reading/writing to the websocket.
     * @param e exception thrown
     */
    void onError(Exception e);

    /**
     * Sends when the server manually closes the connection. This will not auto-reconnect.
     */
    void onClose();

    /**
     * Sends when the socket is timed out due to no message in a certain amount of milliseconds.
     */
    void onTimeout();
}
