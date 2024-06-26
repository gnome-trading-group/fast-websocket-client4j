import group.gnometrading.websockets.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

class Clients {
    private static int NUM_TRIES = 200;
    private static int NUM_MESSAGES = 1_000_000;
    public static void main(String[] args) throws IOException, InterruptedException {
        URI uri = URI.create("ws://localhost:443");

        long total = 0;
        long[] results = new long[NUM_TRIES];
//        LoadTester test = new FastWSBlockingLoadTester(uri);
        for (int i = 0; i < NUM_TRIES; i++) {
//            long dur = test.runTest();
            long dur = new JavaWSLoadTester(uri).runTest();
            System.out.println("Attempt nanos: " + i + ": " + dur);
            results[i] = dur;
            total += dur;
        }
        System.out.println("Avg nanos: " + (total / NUM_TRIES));
        System.out.println("Avg per read: " + (total / NUM_TRIES / NUM_MESSAGES));
        System.out.println(Arrays.toString(results));
    }

    private static interface LoadTester {

        default long runTest() throws IOException {
            this.connectToWS();
            long start = System.nanoTime();
            for (int i = 0; i < NUM_MESSAGES; i++) {
                waitForMessage(i);
            }
            long end = System.nanoTime();
            this.close();
            return end - start;
        }

        void close() throws IOException;
        void connectToWS() throws IOException;
        void waitForMessage(int num) throws IOException;
    }

    private static class JavaWSLoadTester extends org.java_websocket.client.WebSocketClient implements LoadTester {
        private Deque<Integer> messages;

        public JavaWSLoadTester(URI uri) throws InterruptedException {
            super(uri);
            this.messages = new ConcurrentLinkedDeque<>();
        }

        @Override
        public void connectToWS() {
            try {
                this.connectBlocking();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onOpen(ServerHandshake serverHandshake) {}

        @Override
        public void onMessage(ByteBuffer bytes) {
            this.messages.addLast(bytes.getInt());
        }

        public void onMessage(String s) {}

        @Override
        public void onClose(int i, String s, boolean b) {}

        @Override
        public void onError(Exception e) {
            System.out.println(e);
        }

        @Override
        public void waitForMessage(int num) {
            while (messages.isEmpty());
            int first = messages.pop();
            if (first != num) {
                throw new RuntimeException("Out of order");
            }
        }
    }

    private static class FastWSBlockingLoadTester implements LoadTester {
        private WebSocketClient webSocketClient;

        public FastWSBlockingLoadTester(URI uri) throws IOException {
            this.webSocketClient = new WebSocketClient.Builder()
                    .withURI(uri)
                    .withAutomaticReconnect(true)
                    .withTimeoutInMillis(2000)
                    .build();
        }

        public void connectToWS() throws IOException {
            this.webSocketClient.connect();
        }

        @Override
        public void waitForMessage(int num) throws IOException {
            ByteBuffer res = this.webSocketClient.poll();
            int parsed = res.getInt();
            if (parsed != num) {
                throw new RuntimeException("Out of order");
            }
        }

        public void close() throws IOException {
            this.webSocketClient.close();
        }
    }
}