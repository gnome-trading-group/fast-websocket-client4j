package group.gnometrading.websockets;

import java.net.URI;

// Lombok would be nice
public class HandshakeInput {
    public URI uri;
    // TODO: Add protocols, extensions here?

    public HandshakeInput(URI uri) {
        this.uri = uri;
    }
}
