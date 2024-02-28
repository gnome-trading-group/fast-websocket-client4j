package group.gnometrading.websockets;

import group.gnometrading.websockets.drafts.Draft;
import group.gnometrading.websockets.enums.HandshakeState;
import group.gnometrading.websockets.exceptions.InvalidHandshakeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

public class HandshakeHandler {

    private static final Logger logger = LoggerFactory.getLogger(HandshakeHandler.class);
    private static final int TIMEOUT_IN_SECONDS = 30; // This should be reasonable for everyone. Right? Guys?
    private static final int HANDSHAKE_RECV_BUFFER = 4 * 1024; // 4kb

    /**
     * Attempt a handshake as the client to whomever we are connected to over the socket.
     * <p />
     * TODO: Add the ability to request extensions and protocols
     *
     * @param socket the connected socket
     * @param draft the draft which to encode and decode the handshake
     * @throws InvalidHandshakeException on an unsuccessful handshake
     */
    public static void attemptHandshake(Socket socket, Draft draft, HandshakeInput input) throws InvalidHandshakeException {
        Future<HandshakeState> attempt = CompletableFuture.supplyAsync(() -> {
            logger.trace("Attempting to send handshake to server...");
            sendHandshake(socket, draft, input);
            logger.trace("Handshake successfully sent. Waiting for response...");
            return acceptHandshake(socket, draft);
        });

        try {
            HandshakeState result = attempt.get(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);

            if (result != HandshakeState.MATCHED) {
                throw new InvalidHandshakeException(result);
            }
        } catch (InterruptedException e) {
            // NO-OP if interrupted; what are we gonna do? :P
        } catch (TimeoutException e) {
            throw new InvalidHandshakeException(HandshakeState.TIMEOUT);
        } catch (ExecutionException e) {
            throw new InvalidHandshakeException(HandshakeState.UNKNOWN);
        }
    }

    private static void sendHandshake(Socket socket, Draft draft, HandshakeInput input) {
        try {
            byte[] write = draft.createHandshake(input);
            socket.getOutputStream().write(write);
        } catch (IOException ignore) {
            throw new InvalidHandshakeException(HandshakeState.INVALID_WRITE);
        }
    }

    private static HandshakeState acceptHandshake(Socket socket, Draft draft) {
        ByteBuffer buffer = ByteBuffer.allocate(HANDSHAKE_RECV_BUFFER);
        int readBytes, totalBytes = 0;
        try {
            while ((readBytes = socket.getInputStream().read(buffer.array(), totalBytes, HANDSHAKE_RECV_BUFFER)) != -1) {
                totalBytes += readBytes;
                buffer.limit(totalBytes);
                HandshakeState result = draft.parseHandshake(buffer);

                if (result != HandshakeState.INCOMPLETE) {
                    return result;
                }

                if (readBytes == HANDSHAKE_RECV_BUFFER) {
                    return HandshakeState.TOO_LARGE;
                }

                logger.trace("Partial handshake received. Continuing...");
                buffer.clear();
            }

            return HandshakeState.SOCKET_CLOSED;
        } catch (IOException e) {
            throw new InvalidHandshakeException(HandshakeState.INVALID_READ);
        }
    }
}
