# Fast websocket client for Java

I wrote this project when I needed a *fast* websocket client. 
Note, this project is very specific to my use-case and probably 
does not generalize well to your needs.

Unfortunately, a lot of the *fast*ness comes from the fact that
a lot of WebSocket features are skipped over. Here are some
idiosyncrasies of this project:


## Lack of write speed

I don't care about writes. I'm not sending that much data, 
and the data I do send is very latency insensitive. For some reason, 
the servers which I'm talking to also don't care about my writes
via WebSockets. They only seem to care about sending data via REST.
For that reason, I'm out (on caring about writes).

The writes I send usually only happen way outside of the hotpath
so I don't mind allocations here. I believe there's a few in there...

## Thread Affinity

This client assumes a single reader thread per client. This reader thread
should ideally be pinned to a core using something like Peter Lawrey's
awesome library [Java Thread Affinity](https://github.com/OpenHFT/Java-Thread-Affinity). The writer thread is spawned once
the socket connects and then lives in the background as a low priority thread.
I'm sure the writes can still work at a respectable speed but I haven't tested
it (nor am going to).


## Lack of draft support

I support RFC6455 only. I might support compression extensions in the future.

## Lack of sub-protocl support

I do not implement any custom application-layer protocols. None of the servers
which I use this code for implement them, so I have no reason to. If you want to
add the ability to support custom protocols, by all means, submit a PR! 

## I'm only a client

I'm only talking to other servers. There is no support for running a
WebSocket server within this library.

## I don't care about fragmentation

I always know the size of the messages I'm sending, so I don't worry
about fragmentation from the client perspective. However, I do not trust
any server which I talk to, so they very well may fragment their messages
on every single byte. I haven't implemented fragmentation parsing from the
server's messages yet, but maybe I will soon.

Read about fragmentation [here](https://datatracker.ietf.org/doc/html/rfc6455#section-5.4).

## Kernel Bypass

This library makes no assumptions about where a `Socket` comes from --
it merely calles `createSocket(host, port)` from a `SocketFactory`. If you
are interested enough, send a socket facade which runs through some
Kernel bypassing software like [DPDK's](https://www.dpdk.org/).

## Logging

Finally, something not overly specific (kinda)! I'm using `SLF4J` for logging.
If you want to speed up the logging, use `Chronicle-Logger`'s `SLF4J` library.

## You're required to do the polling

Most websocket libraries will spawn both a reader and a writer thread to 
constantly pool the IO buffers on the socket. However, this is not the
case in this library. The use case here is for the calling code of 
`poll` to be pinned to a core purely only for reading from the IO stream.
This library is best for WebSockets which 99% of the traffic is against
reading input rather than writing.

## Assumes decent I/O familiarity

The main exchange of data between your code and this library's code
is through `ByteBuffer`s. If you are uncomfortable with byte buffers,
I'd suggest first learning those and then coming back to the library.

# Features

Here are some things which aren't negative.

## It's fast

How fast you say? Well, here's a graph.

TODO

## Limited allocations

There are zero allocations on the hot path of this code. If you are
unfamiliar of a hot path or what it entails, this may not be relevant for you.
Even further, depending on your implementation of `SocketFactory`, there
are *zero* copies of the bytes which the NIC receives to when it is
delivered to your code (however, this is assuming you have the capability
of transferring NIC -> user space (kernel bypassing)). If you are running
JDK's default implementation of `SocketFactory`, the bytes will be copied
from kernel space -> user space -> the client's buffer. 

## Automatic reconnection

This library supports automatic detection of the connection being dropped
based off a timeout interval, and consequently automatic reconnection if
this is detected.

# Usage

Well, if you're still reading then all my implementation opinions seem
to not have deterred you. To the individual who is still thinking about
using this library, in one last opportunity to scare you away, I most
likely will *not* support any questions or issues you post. I only
open-sourced this because I like open-source. I don't do it to support others,
sorry.

Here's how to use the library:

## Default use case

```java
WebSocketClient webSocketClient = new WebSocketClient.Builder()
        .withURI(URI.create(/* some uri */))
        .build();
webSocketClient.connect(); // blocking

while (true) {
    ByteBuffer payload = webSocketClient.poll();
    String decode = StandardCharsets.UTF_8.decode(payload);
    System.out.println("Got a message: " + decode);
}
connect();
```
