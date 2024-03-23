package group.gnometrading.websockets;

import group.gnometrading.websockets.drafts.Draft;
import group.gnometrading.websockets.drafts.RFC6455;
import group.gnometrading.websockets.enums.SocketState;
import group.gnometrading.websockets.exceptions.InvalidHandshakeException;
import group.gnometrading.websockets.enums.Opcode;
import group.gnometrading.websockets.frames.DataFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A very specific WebSocket client. See the README for details on why.
 */
public class WebSocketClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketClient.class);
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    public static final int DEFAULT_PORT = 80;
    public static final int DEFAULT_WSS_PORT = 443;
    // Keep this less than 32mb so it fits entirely in the L1 cache
    public static final int RECV_BUF = 2^13;  // 8kb
    public static final int SEND_BUF = 2048; // 2kb

    private final URI uri;
    private final SocketFactory socketFactory;
    private SocketState socketState;
    private Socket socket;
    private final CircularFlyweightQueue<ByteBuffer> writeQueue;
    private final WebSocketListener listener;
    private final Draft draft;
    private Thread writerThread, timeoutThread;
    private final ByteBuffer readBuffer;
    private int readOffset = 0, frameOffset = 0;
    private long lastMessageMillis = 0;
    private final boolean automaticReconnect;
    private final long timeoutInMillis;
    private final DataFrame frame;
    private InputStream inputStream;

    private WebSocketClient(URI uri, SocketFactory socketFactory, int writeQueueCapacity, WebSocketListener listener,
                            Draft draft, boolean automaticReconnect, long timeoutInMillis) {
        // Don't use SocketChannels for now. Socket has a more general API which we can potentially abstract
        // into kernel bypass later.
        this.uri = uri;
        this.socketFactory = socketFactory;
        this.socketState = SocketState.CLOSED;
        // Allocate the ByteBuffers on the heap rather than in off-heap memory because we cannot use the addresses
        // of the buffers into send syscall directly, so copying is faster on the heap.
        this.writeQueue = new CircularFlyweightQueue<>(writeQueueCapacity, () -> ByteBuffer.allocate(SEND_BUF));
        this.listener = listener;
        this.draft = draft;
        this.frame = this.draft.getDataFrame();
        this.readBuffer = ByteBuffer.allocate(RECV_BUF);
        this.automaticReconnect = automaticReconnect;
        this.timeoutInMillis = timeoutInMillis;
    }

    public SocketState getSocketState() {
        return this.socketState;
    }

    /**
     * Connect to the WebSocket server. Handles the initial Socket setup and the handshake with the server.
     * If the connection is successful, kicks off the writer thread to start sending messages to the server.
     * Note, this writer thread is not pinned to a core (nor should it be). It is meant for infrequent, low-latency
     * writes.
     * <p />
     * This is blocking.
     */
    public void connect() throws IOException, InvalidHandshakeException {
        if (this.socket != null) {
            throw new IllegalStateException("Call close() before running connect again");
        } else if (this.socketState != SocketState.CLOSED) {
            throw new IllegalStateException("Can only connect with SocketState == CLOSED");
        }

        this.socketState = SocketState.CONNECTING;
        int port = this.uri.getPort() == -1 ? (this.uri.getScheme().equals("wss") ? DEFAULT_WSS_PORT : DEFAULT_PORT) : this.uri.getPort();
        this.socket = socketFactory.createSocket(this.uri.getHost(), port);
        this.inputStream = this.socket.getInputStream();

        HandshakeInput input = new HandshakeInput(this.uri);
        HandshakeHandler.attemptHandshake(this.socket, this.draft, input);

        this.writerThread = new Thread(new WebSocketWriterThread());
        this.writerThread.setDaemon(true);
        this.writerThread.start();

        if (this.automaticReconnect) {
            this.lastMessageMillis = System.currentTimeMillis();
            this.timeoutThread = new Thread(new WebSocketTimeoutThread());
            this.timeoutThread.setDaemon(true);
            this.timeoutThread.start();
        }

        if (listener != null) this.listener.onConnect();
        this.socketState = SocketState.OPEN;
    }

    private void write(Opcode opcode, byte[] bytes) {
        this.writeQueue.enqueue(buffer -> {
            if (bytes.length > (buffer.capacity() - 12)) { // 12 seems like a reasonable guess for the header size
                throw new IllegalArgumentException("Write input exceeds max length");
            }

            DataFrame encoder = draft.getDataFrame().wrap(buffer);
            encoder.encode(opcode, bytes);
            buffer.flip();
        });
    }

    /**
     * Send a binary message to the server.
     * @param bytes the bytes to send
     */
    public void write(byte[] bytes) {
        write(Opcode.BINARY, bytes);
    }

    /**
     * Send a text message to the server. Note, this is slow as the String is
     * first decoded into a new byte array and then copied into the write buffer (an allocation).
     * @param message the message to send
     */
    public void write(String message) {
        write(Opcode.TEXT, message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Send a ping to the server.
     */
    public void ping() {
        write(Opcode.PING, EMPTY.array());
    }

    private void pong() {
        write(Opcode.PONG, EMPTY.array());
    }

    /**
     * Poll is the main method in this WebSocket client. It is used to read from the socket's
     * input buffer and parse data frames sent from the server. This should be called as fast
     * as possible. This will block until an entire WebSocket frame is received. This method
     * will only process at most one WebSocket frame at a time.
     *
     * If a PING message is received, a PONG will be sent in return and continue polling for
     * a different frame. If PONG is received, polling will continue until a new frame.
     *
     * @return a raw ByteBuffer containing the payload from the server
     * @throws IOException if the socket's IO throws an exception
     */
    public ByteBuffer poll() throws IOException {
        if (socketState != SocketState.OPEN) {
            return EMPTY;
        }

        this.readBuffer.clear();
        if (frameOffset > (RECV_BUF >> 1)) {
            System.arraycopy(this.readBuffer.array(), frameOffset, this.readBuffer.array(), 0, readOffset - frameOffset);
            readOffset -= frameOffset;
            frameOffset = 0;
        }

        this.frame.wrap(this.readBuffer, frameOffset, readOffset - frameOffset);
        while (this.frame.isIncomplete()) {
            int remaining = RECV_BUF - readOffset;
            if (remaining <= 0) {
                throw new BufferOverflowException();
            }

            int readBytes = this.inputStream.read(this.readBuffer.array(), readOffset, remaining);
            if (readBytes < 0) {
                return EMPTY; // Closed while polling
            }

            if (automaticReconnect) {
                // Use wall-clock due to separate threads most likely on different cores
                // System#nanoTime is slower and is not meant for cross-core
                lastMessageMillis = System.currentTimeMillis();
            }

            readOffset += readBytes;
            this.frame.wrap(this.readBuffer, frameOffset, readOffset - frameOffset);
        }

        if (this.frame.isFragment()) {
            throw new IllegalStateException("Sorry, I haven't implemented fragments yet.");
        }
        frameOffset += this.frame.length();

        switch (this.frame.getOpcode()) {
            case TEXT:
            case BINARY:
                return this.frame.getPayloadData();
            case CLOSING: {
                if (listener != null) listener.onClose();
                logger.trace("Close received from server");
                this.close();
                return EMPTY;
            }
            case PING: {
                pong();
                return poll();
            }
            case PONG: {
                logger.trace("Pong received from server");
                return poll();
            }
            default:
                throw new IllegalStateException("Unhandled opcode: " + this.frame.getOpcode());
        }
    }

    public void reconnect() throws IOException {
        this.close();
        this.connect();
    }

    /**
     * Close the web socket connection and accompanying writer thread.
     * @throws IOException if any of the resources do. We don't throw one :)
     */
    @Override
    public void close() throws IOException {
        // Don't care about flushing write buffer if this is called.
        this.socketState = SocketState.CLOSED;
        if (this.socket != null) {
            this.socket.close();
            this.socket = null;
        }

        if (this.writerThread != null) {
            this.writerThread.interrupt();
            try {
                this.writerThread.join();
            } catch (InterruptedException ignored) {}
            this.writerThread = null;
        }

        if (this.timeoutThread != null) {
            this.timeoutThread.interrupt();
            try {
                this.timeoutThread.join();
            } catch (InterruptedException ignored) {}
            this.timeoutThread = null;
        }

        this.writeQueue.clear();
        this.readOffset = this.frameOffset = 0;
    }

    private class WebSocketTimeoutThread implements Runnable {
        @Override
        public void run() {
            while (!Thread.interrupted() && socketState == SocketState.OPEN) {
                long millis = System.currentTimeMillis() - lastMessageMillis;
                if (millis > timeoutInMillis) {
                    try {
                        logger.trace("Attempting to reconnect due to timeout");
                        if (listener != null) {
                            listener.onTimeout();
                        }
                        reconnect();
                    } catch (IOException ignore) {}
                }
            }
        }
    }

    private class WebSocketWriterThread implements Runnable {

        @Override
        public void run() {
            while (!Thread.interrupted() && socketState == SocketState.OPEN) {
                if (!writeQueue.isEmpty()) {
                    writeQueue.pop(this::runWrite);
                }
            }
        }

        private void runWrite(ByteBuffer buffer) {
            // We're responsible for resetting this buffer to be usable by the parent thread's write function
            try {
                socket.getOutputStream().write(buffer.array(), 0, buffer.limit());
            } catch (IOException e) {
                logger.error("Error received writing output", e);
                if (listener != null) listener.onWriteError(e);
            } finally {
                buffer.clear();
            }
        }
    }

    public static class Builder { // Lombok would be nice... but we're lightweight
        private URI uri;
        private SocketFactory socketFactory;
        private boolean automaticReconnect = false;
        private int writeQueueCapacity = 10;
        private int timeoutInMillis = 5_000;
        private WebSocketListener listener;
        private Draft draft;

        public Builder() {}

        public Builder withURI(URI uri) {
            this.uri = uri;
            return this;
        }

        public Builder withSocketFactory(SocketFactory socketFactory) {
            this.socketFactory = socketFactory;
            return this;
        }

        public Builder withAutomaticReconnect(boolean automaticReconnect) {
            this.automaticReconnect = automaticReconnect;
            return this;
        }

        public Builder withWriteQueueCapacity(int capacity) {
            this.writeQueueCapacity = capacity;
            return this;
        }

        public Builder withTimeoutInMillis(int timeoutInMillis) {
            this.timeoutInMillis = timeoutInMillis;
            return this;
        }

        public Builder withListener(WebSocketListener listener) {
            this.listener = listener;
            return this;
        }

        public Builder withDraft(Draft draft) {
            this.draft = draft;
            return this;
        }

        public WebSocketClient build() {
            if (uri == null) {
                throw new IllegalArgumentException("uri cannot be null");
            }

            if (socketFactory == null) {
                socketFactory = uri.getScheme().equals("wss") ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
            }

            if (draft == null) {
                draft = new RFC6455();
            }

            return new WebSocketClient(uri, socketFactory, writeQueueCapacity, listener, draft, automaticReconnect,
                    timeoutInMillis);
        }
    }
}
