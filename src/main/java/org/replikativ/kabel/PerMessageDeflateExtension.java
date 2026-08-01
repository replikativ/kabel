package org.replikativ.kabel;

import org.glassfish.tyrus.core.extension.ExtendedExtension;
import org.glassfish.tyrus.core.frame.Frame;

import javax.websocket.Extension;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Client-side "permessage-deflate" (RFC 7692) for the Tyrus WebSocket client.
 *
 * <p>Tyrus ships no implementation of this extension at any version — 1.13.1
 * and 1.21 both contain zero deflate classes, and there is no
 * {@code tyrus-extension-*} artifact on Central. What it does ship is
 * {@link ExtendedExtension}, an SPI whose stated purpose was to make
 * permessage-deflate implementable. This is that.
 *
 * <p>It matters because kabel is asymmetric: the server side is http-kit and
 * the JVM client side is Tyrus. A browser offers permessage-deflate
 * automatically, so browser-to-peer links compress as soon as the server
 * supports it; peer-to-peer links compress only if the client offers it, and
 * without this nothing does. That is the datahike replication path.
 *
 * <h3>Why the state is not in fields</h3>
 *
 * One {@code Extension} instance is registered on a
 * {@code ClientEndpointConfig} and shared by every connection opened with it,
 * so a {@link Deflater} in a field would be shared across connections whose
 * compression histories must stay separate — silent corruption, not an error.
 * Tyrus provides {@link ExtendedExtension.ExtensionContext#getProperties()} for
 * exactly this: per-connection state, created before
 * {@link #onHandshakeResponse} and torn down in {@link #destroy}.
 *
 * <p>{@code Deflater} and {@code Inflater} hold native memory that the GC does
 * not account for, so {@code destroy} ending them is load-bearing rather than
 * tidy: leaking one per connection is a native-memory leak in a process that
 * opens many peers.
 *
 * <h3>What is offered</h3>
 *
 * Context takeover in both directions — message N compressed against messages
 * 1..N-1 — which is the whole reason the extension is worth having for a stream
 * of many similar messages. No {@code *_max_window_bits} is offered:
 * {@code java.util.zip} does not expose zlib's windowBits, so this cannot
 * honour a smaller window on its own deflater and must not ask for one on the
 * peer's. If the server answers with {@code client_no_context_takeover} or
 * {@code server_no_context_takeover}, both are honoured.
 *
 * <p>Note the directions are mirrored relative to a server implementation:
 * here "client" is us, so {@code client_no_context_takeover} governs our
 * {@code Deflater} and {@code server_no_context_takeover} governs our
 * {@code Inflater}.
 */
public class PerMessageDeflateExtension implements ExtendedExtension {

    public static final String NAME = "permessage-deflate";

    /** RFC 7692 7.2.1: DEFLATE emits this tail on SYNC_FLUSH. It is dropped
     *  when compressing and appended back before inflating. */
    private static final byte[] TAIL = {0x00, 0x00, (byte) 0xFF, (byte) 0xFF};

    private static final String STATE = "kabel.permessage-deflate.state";

    private static final byte[] EMPTY = new byte[0];

    /** Bound on a message's size AFTER inflation. A small compressed frame can
     *  expand enormously, and the frame-length limit says nothing about that. */
    private final int maxSize;

    public PerMessageDeflateExtension() {
        this(64 * 1024 * 1024);
    }

    public PerMessageDeflateExtension(int maxSize) {
        this.maxSize = maxSize;
    }

    /** Per-connection zlib state, kept in the ExtensionContext. */
    private static final class State {
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        final Inflater inflater = new Inflater(true);
        boolean negotiated;              // did the server accept the extension?
        boolean noContextTakeoverOut;    // client_no_context_takeover
        boolean noContextTakeoverIn;     // server_no_context_takeover
        boolean ended;                   // destroy() ran; zlib released

        /** RSV1 rides only on the FIRST frame of a message, so continuations
         *  inherit the decision rather than re-reading a bit that is not
         *  there. */
        boolean continuationCompressed;
        boolean inContinuation;

        /** Inbound fragments of the CURRENT message, still compressed.
         *
         *  A fragmented compressed message is ONE deflate stream split at
         *  arbitrary octets -- the split need not land on a block boundary.
         *  Inflating each fragment separately, with the 00 00 FF FF tail
         *  appended to each, is therefore wrong and fails on real traffic:
         *  the RFC's own fragmented "Hello" example decodes its first
         *  fragment as "Hel" and then throws "invalid stored block lengths".
         *  Fragments are concatenated and inflated once, on FIN. */
        ByteArrayOutputStream inbound;
    }

    /** Synchronized with processIncoming/processOutgoing/destroy: Tyrus hands
     *  frames to IO threads and closes from whichever thread closed the
     *  session, and the properties map is a plain HashMap. */
    private State state(ExtensionContext ctx) {
        Map<String, Object> props = ctx.getProperties();
        State s = (State) props.get(STATE);
        if (s == null) {
            s = new State();
            props.put(STATE, s);
        }
        return s;
    }

    // ---------------------------------------------------------------- handshake

    @Override
    public String getName() {
        return NAME;
    }

    /** The offer. Empty: context takeover in both directions is the default,
     *  and it is what we want. */
    @Override
    public List<Extension.Parameter> getParameters() {
        return Collections.emptyList();
    }

    /**
     * Client side of the handshake. Tyrus calls this with the parameters the
     * server echoed; being called at all means the server accepted the
     * extension, so this is where compression is switched on.
     */
    @Override
    public void onHandshakeResponse(ExtensionContext ctx, List<Extension.Parameter> parameters) {
        State s = state(ctx);
        s.negotiated = true;
        if (parameters != null) {
            for (Extension.Parameter p : parameters) {
                String key = p.getName();
                if ("client_no_context_takeover".equalsIgnoreCase(key)) {
                    s.noContextTakeoverOut = true;
                } else if ("server_no_context_takeover".equalsIgnoreCase(key)) {
                    s.noContextTakeoverIn = true;
                }
                // *_max_window_bits is not offered, so a conforming server does
                // not send it. If one does anyway, ignoring it is safe in this
                // direction: Inflater copes with any legal window, and a
                // Deflater we cannot narrow simply produces a window the peer
                // is already required to handle.
            }
        }
    }

    /** Server side. This extension is client-only; declining keeps it inert if
     *  it is ever registered on a server endpoint by mistake. */
    @Override
    public List<Extension.Parameter> onExtensionNegotiation(ExtensionContext ctx,
                                                            List<Extension.Parameter> requestedParameters) {
        return null;
    }

    @Override
    public synchronized void destroy(ExtensionContext ctx) {
        State s = (State) ctx.getProperties().get(STATE);
        if (s != null && !s.ended) {
            // Marked rather than removed. Removing it let a frame still in
            // flight call state() and build a FRESH Deflater/Inflater pair
            // that would never see another destroy() -- turning a teardown
            // into a leak.
            s.ended = true;
            s.inbound = null;
            s.deflater.end();
            s.inflater.end();
        }
    }

    // ---------------------------------------------------------------- frames

    private static boolean isControl(byte opcode) {
        // 0x8 close, 0x9 ping, 0xA pong. Control frames are never compressed
        // and may be interleaved with a fragmented data message.
        return (opcode & 0x08) != 0;
    }

    private static boolean isContinuation(byte opcode) {
        return opcode == 0x00;
    }

    @Override
    public synchronized Frame processOutgoing(ExtensionContext ctx, Frame frame) {
        State s = state(ctx);
        if (!s.negotiated || s.ended || isControl(frame.getOpcode())) return frame;

        boolean first = !isContinuation(frame.getOpcode());
        // A fragmented message is ONE deflate stream split across frames, so
        // only the FINAL frame flushes. An earlier version returned
        // continuations unchanged, which put raw application bytes inside an
        // RSV1-marked compressed message -- the peer inflated garbage.
        byte[] compressed = compress(s, frame.getPayloadData(), frame.isFin());
        return Frame.builder(frame)
                .payloadData(compressed)
                .payloadLength(compressed.length)
                .rsv1(first)                 // RSV1 on the first frame only
                .build();
    }

    @Override
    public synchronized Frame processIncoming(ExtensionContext ctx, Frame frame) {
        State s = state(ctx);
        // Control frames are never compressed and may be interleaved into a
        // fragmented data message, so they must not disturb its state.
        if (!s.negotiated || s.ended || isControl(frame.getOpcode())) return frame;

        boolean compressed;
        if (isContinuation(frame.getOpcode())) {
            compressed = s.inContinuation && s.continuationCompressed;
        } else {
            compressed = frame.isRsv1();
            s.continuationCompressed = compressed;
            s.inContinuation = true;
            s.inbound = null;
        }
        if (!compressed) {
            if (frame.isFin()) { s.inContinuation = false; s.inbound = null; }
            return frame;
        }

        byte[] part = frame.getPayloadData();
        if (!frame.isFin()) {
            // Accumulate. Bounded across the WHOLE message: a per-frame bound
            // lets a peer split one message into many frames, each under the
            // limit, and inflate far past it in total.
            if (s.inbound == null) s.inbound = new ByteArrayOutputStream(Math.max(64, part.length));
            if (s.inbound.size() + part.length > maxSize) {
                throw new IllegalStateException(
                        "Max payload length " + maxSize + " exceeded while reassembling");
            }
            s.inbound.write(part, 0, part.length);
            // A zero-length, non-final frame keeps the message open.
            return Frame.builder(frame).payloadData(EMPTY).payloadLength(0).rsv1(false).build();
        }

        byte[] whole;
        if (s.inbound == null) {
            whole = part;
        } else {
            if (s.inbound.size() + part.length > maxSize) {
                throw new IllegalStateException(
                        "Max payload length " + maxSize + " exceeded while reassembling");
            }
            s.inbound.write(part, 0, part.length);
            whole = s.inbound.toByteArray();
        }
        s.inbound = null;
        s.inContinuation = false;

        byte[] inflated = decompress(s, whole);
        return Frame.builder(frame)
                .payloadData(inflated)
                .payloadLength(inflated.length)
                .rsv1(false)
                .build();
    }

    // ---------------------------------------------------------------- zlib

    /**
     * RFC 7692 7.2.1: deflate with SYNC_FLUSH, then drop the 4-octet
     * {@code 00 00 FF FF} tail the flush appends.
     */
    private byte[] compress(State s, byte[] data, boolean fin) {
        s.deflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length));
        byte[] buf = new byte[4096];
        int n;
        // SYNC_FLUSH only on the FINAL fragment. A fragmented message is one
        // deflate stream split across frames; flushing every fragment would
        // emit a tail mid-message, and finish() would end the stream entirely
        // and discard the history context takeover exists to keep.
        int flush = fin ? Deflater.SYNC_FLUSH : Deflater.NO_FLUSH;
        while ((n = s.deflater.deflate(buf, 0, buf.length, flush)) > 0) {
            out.write(buf, 0, n);
            if (n < buf.length && flush == Deflater.SYNC_FLUSH) break;
        }
        // Only after the message is complete, or the next fragment would be
        // compressed against a reset window the peer is not resetting.
        if (fin && s.noContextTakeoverOut) s.deflater.reset();

        byte[] bs = out.toByteArray();
        int len = bs.length;
        if (!fin) return bs;                 // no tail to strip mid-message
        if (len >= TAIL.length && endsWithTail(bs, len)) len -= TAIL.length;
        // RFC 7692 7.2.3.6: when the compressor produces nothing -- an empty
        // message, or one whose entire content was already flushed -- the
        // payload must be a single empty uncompressed DEFLATE block, 0x00. NOT
        // zero bytes.
        //
        // Found by testing against http-kit's independent implementation
        // rather than by reading: sending "" was fine on a fresh connection
        // and fine as the first message, but ["a" "" "b"] desynchronised the
        // stream and "b" never arrived. An empty payload only misbehaves once
        // there is compression history for it to corrupt, which is why a
        // single-implementation round-trip never caught it.
        if (len == 0) return new byte[]{0x00};
        byte[] result = new byte[len];
        System.arraycopy(bs, 0, result, 0, len);
        return result;
    }

    private static boolean endsWithTail(byte[] bs, int len) {
        for (int i = 0; i < TAIL.length; i++) {
            if (bs[len - TAIL.length + i] != TAIL[i]) return false;
        }
        return true;
    }

    /** RFC 7692 7.2.2: append the tail the sender removed, then inflate. */
    private byte[] decompress(State s, byte[] data) {
        s.inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 4));
        byte[] buf = new byte[4096];
        try {
            inflate(s, out, buf);
            s.inflater.setInput(TAIL);
            inflate(s, out, buf);
        } catch (DataFormatException e) {
            throw new IllegalStateException("Invalid permessage-deflate payload: "
                    + e.getMessage(), e);
        }
        // After the whole message, not after each fragment: a continuation
        // refers back to data in earlier fragments.
        if (s.noContextTakeoverIn) s.inflater.reset();
        return out.toByteArray();
    }

    private void inflate(State s, ByteArrayOutputStream out, byte[] buf)
            throws DataFormatException {
        int n;
        while (!s.inflater.needsInput() && (n = s.inflater.inflate(buf)) > 0) {
            if (out.size() + n > maxSize) {
                throw new IllegalStateException(
                        "Max payload length " + maxSize + " exceeded after decompression");
            }
            out.write(buf, 0, n);
        }
    }

    /** Convenience for registration:
     *  {@code (.extensions builder [(PerMessageDeflateExtension/offer)])}. */
    public static List<Extension> offer() {
        List<Extension> exts = new ArrayList<Extension>(1);
        exts.add(new PerMessageDeflateExtension());
        return exts;
    }
}
