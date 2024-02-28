package group.gnometrading.websockets.drafts;

import group.gnometrading.websockets.HandshakeInput;
import group.gnometrading.websockets.enums.HandshakeState;
import group.gnometrading.websockets.frames.DataFrame6455;
import group.gnometrading.websockets.utils.CharBufferUtils;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class RFC6455 extends Draft {
    private static final String TEMPLATE;
    private static final String PROTOCOL = "HTTP/1.1 101 Switching Protocols";
    private static final String DEFAULT_PATH = "/";
    private static final ThreadLocal<ByteBuffer> ENCODING = ThreadLocal.withInitial(() -> ByteBuffer.allocate(16));

    static {
        StringBuilder builder = new StringBuilder();

        builder.append("GET %s HTTP/1.1\r\n");
        builder.append("Host: %s\r\n");
        builder.append("Upgrade: websocket\r\n");
        builder.append("Connection: Upgrade\r\n");
        builder.append("Sec-WebSocket-Key: %s\r\n");
        builder.append("Sec-WebSocket-Version: 13\r\n");
        builder.append("\r\n");

        TEMPLATE = builder.toString();
    }

    public RFC6455() {
        super(new DataFrame6455());
    }

    @Override
    public byte[] createHandshake(HandshakeInput input) {
        secureRandom.nextBytes(ENCODING.get().array());

        String websocketKey = Base64.getEncoder().encodeToString(ENCODING.get().array());
        String path = DEFAULT_PATH;
        if (!input.uri.getPath().isEmpty()) {
            path = input.uri.getPath();
        }
        return String.format(TEMPLATE, path, input.uri.getHost(), websocketKey).getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public HandshakeState parseHandshake(ByteBuffer buffer) {
        CharBuffer decoded = StandardCharsets.US_ASCII.decode(buffer); // makes a copy...
        if (!CharBufferUtils.endsWith(decoded, "\r\n\r\n")) {
            return HandshakeState.INCOMPLETE;
        }

        int index = 0;

        // Checking for protocol first
        while (index < Math.min(decoded.length(), PROTOCOL.length())) {
            if (PROTOCOL.charAt(index) != decoded.charAt(index)) {
                return HandshakeState.INVALID_PROTOCOL;
            }

            index++;
        }

        if (index != PROTOCOL.length()) {
            return HandshakeState.INVALID_PROTOCOL;
        }

        index += 2; // pass \r\n
        int headers = 0; // header bitmap, should be 0b111
        StringBuilder builder = new StringBuilder();

        while (index < decoded.length()) {
            if (decoded.charAt(index) != '\r') {
                builder.append(Character.toLowerCase(decoded.charAt(index)));
                index++;
                continue;
            }

            // We are at a \r, check if there's a header
            if (builder.indexOf("upgrade: websocket") != -1) {
                headers |= 0b1;
                builder.setLength(0);
            } else if (builder.indexOf("connection: upgrade") != -1) {
                headers |= 0b10;
                builder.setLength(0);
            } else if (builder.indexOf("sec-websocket-accept") != -1) { // Hmm.. should we verify this? Nah
                headers |= 0b100;
                builder.setLength(0);
            }

            index += 2; // skip the \n
        }

        if (headers != 0b111) {
            return HandshakeState.MISSING_HEADERS;
        }

        return HandshakeState.MATCHED;
    }
}
