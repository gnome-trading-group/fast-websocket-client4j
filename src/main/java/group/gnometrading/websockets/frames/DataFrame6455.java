package group.gnometrading.websockets.frames;

import group.gnometrading.websockets.enums.Opcode;

import java.nio.ByteBuffer;
import java.util.Random;

public class DataFrame6455 implements DataFrame {

    private static final ByteBuffer MASK = ByteBuffer.allocate(4);

    static {
        MASK.putInt(new Random().nextInt());
    }

    private ByteBuffer buffer;
    private int limit;
    private int offset;

    @Override
    public DataFrame wrap(ByteBuffer buffer, int offset, int n) {
        this.buffer = buffer;
        this.limit = n;
        this.offset = offset;
        return this;
    }

    @Override
    public Opcode getOpcode() {
        return Opcode.fromByte(this.buffer.get(offset));
    }

    @Override
    public void encode(Opcode opcode, byte[] payload) {
        // No support for fragmented frames
        this.buffer.put((byte) (0b10000000 | opcode.code));

        // Since we're the client, we will always mask
        byte mask = (byte) 0b10000000;

        int payloadLength = payload.length;
        if (payloadLength > (2 << 15)) {
            this.buffer.put((byte) (mask | 127));
            this.buffer.putLong(payloadLength);
        } else if (payloadLength > 125) {
            this.buffer.put((byte) (mask | 126));
            this.buffer.putShort((short) payloadLength);
        } else {
            this.buffer.put((byte) (mask | payloadLength));
        }

        this.buffer.putInt(MASK.getInt(0));

        for (int i = 0; i < payload.length; i++) {
            this.buffer.put((byte) (payload[i] ^ MASK.get(i % 4)));
        }
    }

    private boolean fin() {
        return (this.buffer.get(offset) & 0b10000000) == 0b10000000;
    }

    private boolean masked() {
        return (this.buffer.get(offset + 1) & 0b10000000) == 0b10000000;
    }

    public int getPayloadLengthOctets() {
        int length = this.buffer.get(offset + 1) & 0b01111111;
        if (length == 126) {
            return 3;
        } else if (length == 127) {
            return 9;
        } else {
            return 1;
        }
    }

    public int getPayloadLength() {
        int length = this.buffer.get(offset + 1) & 0b01111111;
        if (length == 126) {
            return this.buffer.getShort(offset + 2);
        } else if (length == 127) {
            // if this is lossy, it deserves to be. if you're sending me a packet > 2^32 bytes... no
            // but if anyone ends up ever debugging this code and the issue turns out to be from downcasting this, sorry.
            return (int) this.buffer.getLong(offset + 2);
        }
        return length;
    }

    private int getMaskingKey() {
        int index = offset + 1 + this.getPayloadLengthOctets();
        return this.buffer.getInt(index);
    }

    @Override
    public ByteBuffer getPayloadData() {
        int index = offset + 1 + this.getPayloadLengthOctets() + (this.masked() ? 4 : 0);
        int payloadLength = this.getPayloadLength();

        if (this.masked()) {
            int key = this.getMaskingKey();
            for (int i = 0; i < payloadLength; i++) {
                byte mask = (byte) ((key >> (8 * (i % 4))) & 0xFF); // not ideal
                this.buffer.put(i + index, (byte) (this.buffer.get(i + index) ^ mask));
            }
        }

        this.buffer.position(index);
        this.buffer.limit(index + payloadLength);
        this.buffer.mark();

        return this.buffer;
    }

    @Override
    public int length() {
        int length = 1 + this.getPayloadLengthOctets();

        if (this.masked()) {
            length += 4;
        }

        return length + this.getPayloadLength();
    }

    @Override
    public boolean isFragment() {
        return !this.fin();
    }

    @Override
    public boolean isIncomplete() {
        int requiredOctets = 1;
        if (this.limit <= requiredOctets) {
            return true;
        }

        requiredOctets += this.getPayloadLengthOctets();
        if (this.limit < requiredOctets) {
            return true;
        }

        requiredOctets += this.masked() ? 4 : 0;
        if (this.limit < requiredOctets) {
            return true;
        }

        requiredOctets += this.getPayloadLength();
        return this.limit < requiredOctets;
    }
}
