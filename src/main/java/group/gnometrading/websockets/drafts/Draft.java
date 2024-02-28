package group.gnometrading.websockets.drafts;

import group.gnometrading.websockets.HandshakeInput;
import group.gnometrading.websockets.enums.HandshakeState;
import group.gnometrading.websockets.frames.DataFrame;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * An abstract class used for completing handshakes and parsing data frames.
 */
public abstract class Draft {

    // TODO: What's the latency on this?
    protected final RandomGenerator secureRandom = new SecureRandom();
    private final DataFrame dataFrame;

    public Draft(DataFrame dataFrame) {
        this.dataFrame = dataFrame;
    }

    public DataFrame getDataFrame() {
        return this.dataFrame;
    }


    /**
     * Construct a byte array of the input of a handshake sent to a server.
     * @param input the input to encode
     * @return the encoded byte array
     */
    public abstract byte[] createHandshake(HandshakeInput input);

    /**
     * Given the ByteBuffer response from the server, parse the handshake response.
     * @param buffer the buffer to parse
     * @return the resulting HandshakeState. HandshakeState.MATCHED if successful
     */
    public abstract HandshakeState parseHandshake(ByteBuffer buffer);
}
