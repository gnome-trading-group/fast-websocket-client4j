import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

class Server extends WebSocketServer  {
    private int NUM_MESSAGES = 20_000_000;

    public static void main(String[] args) {
        Server s = new Server();
        s.setDaemon(false);
        s.start();
    }

    public Server() {
        super(new InetSocketAddress(443));
    }

    @Override
    public void onOpen(WebSocket webSocket, ClientHandshake clientHandshake) {
//        try {
//            Thread.sleep(500);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
        ByteBuffer buffer = ByteBuffer.allocate(4);
        for (int i = 0; i < NUM_MESSAGES; i++) {
            buffer.clear();
            buffer.putInt(i);
            webSocket.send(buffer.array());
        }
    }

    @Override
    public void onClose(WebSocket webSocket, int i, String s, boolean b) {
        System.out.println("Someone left :(");
    }

    @Override
    public void onMessage(WebSocket webSocket, String s) {

    }

    @Override
    public void onError(WebSocket webSocket, Exception e) {

    }

    @Override
    public void onStart() {

    }
}