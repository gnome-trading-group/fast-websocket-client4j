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
import java.net.Socket;
import java.net.URI;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * A very specific WebSocket client. See the README for details on why.
 */
public class WebSocketClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketClient.class);
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

    public static final int DEFAULT_PORT = 80;
    public static final int DEFAULT_WSS_PORT = 443;
    // TODO: Should we look this up dynamically in each system?
    public static final int RECV_BUF = 32768;  // 32kb
    public static final int SEND_BUF = 2048; // 2kb

    private final URI uri;
    private final SocketFactory socketFactory;
    private SocketState socketState;
    private Socket socket;
    private final boolean automaticReconnect;
    private final CircularFlyweightQueue<ByteBuffer> writeQueue;
    private final long timeoutNanos;
    private final long lastMessageNanos = -1;
    private final WebSocketListener listener;
    private final Draft draft;
    private Thread writerThread;
    private final ByteBuffer readBuffer;
    private int readOffset = 0, frameOffset = 0;

    private WebSocketClient(URI uri, SocketFactory socketFactory, boolean automaticReconnect,
                            int writeQueueCapacity, int timeoutInMs, WebSocketListener listener, Draft draft) {
        // Don't use SocketChannels for now. Socket has a more general API which we can potentially abstract
        // into kernel bypass later.
        this.uri = uri;
        this.socketFactory = socketFactory;
        this.socketState = SocketState.CLOSED;
        this.automaticReconnect = automaticReconnect;
        // Allocate the ByteBuffers on the heap rather than in off-heap memory because we cannot use the addresses
        // of the buffers into send syscall directly, so copying is faster on the heap.
        this.writeQueue = new CircularFlyweightQueue<>(writeQueueCapacity, () -> ByteBuffer.allocate(SEND_BUF));
        this.timeoutNanos = TimeUnit.NANOSECONDS.convert(timeoutInMs, TimeUnit.MILLISECONDS);
        this.listener = listener;
        this.draft = draft;
        this.readBuffer = ByteBuffer.allocate(RECV_BUF);
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

        HandshakeInput input = new HandshakeInput(this.uri);
        HandshakeHandler.attemptHandshake(this.socket, this.draft, input);

        this.writerThread = new Thread(new WebSocketWriterThread());
        this.writerThread.setDaemon(true);
        this.writerThread.start();

        this.socketState = SocketState.OPEN;
        if (listener != null) this.listener.onStart();
    }

    private void write(Opcode opcode, byte[] bytes) {
        this.writeQueue.enqueue(buffer -> {
            if (bytes.length > (buffer.capacity() - 12)) { // 12 seems like a reasonable guess for the header size
                throw new IllegalArgumentException("Write input exceeds max length");
            }

            DataFrame encoder = draft.getDataFrame().wrap(buffer, 0);
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
     * as possible. This will be blocking depending on the implementation of the socket's
     * input stream's `read` method. This method will only process at most one WebSocket frame
     * at a time. If there's bytes left over in the Socket stream, the next call to poll will
     * instead process these bytes rather than read from the wire.
     *
     * @return a raw ByteBuffer containing the payload from the server
     * @throws IOException if the socket's IO throws an exception
     */
    public ByteBuffer poll() throws IOException {
        if (this.socketState != SocketState.OPEN) {
            throw new IllegalStateException("Poll can only be called with an open socket");
        }
        // TODO: How to handle a full buffer?
        this.readBuffer.clear(); // The call to #getPayloadData() modifies the position and limit
        if (frameOffset != 0) { // There's bytes left over

        } else {
            int remaining = this.readBuffer.remaining() - readOffset;
            if (remaining == 0) {
                throw new BufferOverflowException();
            }
            // Would be nice to use ByteBuffer's automatic positioning but with how websockets are fragmented, it makes it diffult
            int readBytes = this.socket.getInputStream().read(this.readBuffer.array(), readOffset, remaining);
            if (readBytes < 0) {
                return EMPTY; // Should not reach this due to top-level if statement (but there's a chance it closes from there to here)
            }
            readOffset += readBytes;
        }

        DataFrame frame = draft.getDataFrame().wrap(this.readBuffer, frameOffset);

        // If we got an incomplete frame, we need to exit and wait for the rest from the socket
        if (frame.isIncomplete()) {
            logger.trace("Incomplete frame received. Returning from poll");
            return EMPTY;
        }

        if (frame.isFragment()) {
            throw new IllegalStateException("Sorry, I haven't implemented fragments yet.");
        }

        // If we read all the bytes from the wire, reset read and frame offsets for the next loop
        if (frame.length() == (readOffset - frameOffset)) {
            readOffset = 0;
            frameOffset = 0;
        } else {
            // We have some extras, what do we do with it?
            // The next time we read, the readOffset should be readOffset += frame.length()
            frameOffset += frame.length();
            // If we are find we're overflowing from this, we can mimic ByteBuffer#compact here
        }

        if (frame.getOpcode() == Opcode.CLOSING) {
            if (listener != null) listener.onClose();
            logger.trace("Close received from server");
            this.close();
        } else if (frame.getOpcode() == Opcode.PONG) {
            // NO-OP
            logger.trace("Pong received from server");
            return EMPTY;
        } else if (frame.getOpcode() == Opcode.PING) {
            pong();
        } else if (frame.getOpcode() == Opcode.CONTINUOUS) {
            throw new IllegalStateException("Uh oh! It's continuous!");
        }

        // Binary or text at this point
        return frame.getPayloadData();
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
        if (this.socket != null) {
            this.socket.close();
        }

        if (this.writerThread != null) {
            this.writerThread.interrupt();
            try {
                this.writerThread.join();
            } catch (InterruptedException ign) {
            }
        }

        this.socketState = SocketState.CLOSED;
        this.writeQueue.clear();
        this.readOffset = this.frameOffset = 0;

        this.socket = null;
        this.writerThread = null;
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
                if (listener != null) listener.onError(e);
            }
            buffer.clear();
        }
    }

    public static class Builder { // Lombok would be nice... but we're lightweight
        private URI uri;
        private SocketFactory socketFactory;
        private boolean automaticReconnect = true;
        private int writeQueueCapacity = 10;
        private int timeoutInMs = 5_000;
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

        public Builder withTimeoutInMs(int timeoutInMs) {
            this.timeoutInMs = timeoutInMs;
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

            return new WebSocketClient(uri, socketFactory, automaticReconnect, writeQueueCapacity, timeoutInMs,
                    listener, draft);
        }
    }
}
