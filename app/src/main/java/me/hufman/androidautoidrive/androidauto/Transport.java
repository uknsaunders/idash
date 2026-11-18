package me.hufman.androidautoidrive.androidauto;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

/**
 * Android Auto's wire format, from the head unit's side.
 *
 * Every frame is: channel (1 byte), flags (1 byte), payload length (2 bytes, big-endian), and for the
 * first frame of a message split over several frames, the whole message's length (4 bytes).
 * Each message starts with a 2-byte message id. After the TLS handshake each frame's payload is
 * encrypted on its own, so frames are decrypted first and then joined into messages.
 */
public class Transport {
	public static final int FRAME_MIDDLE = 0;
	public static final int FRAME_FIRST = 1;
	public static final int FRAME_LAST = 2;
	public static final int FRAME_BULK = 3;
	public static final int FLAG_CONTROL = 0x04;
	public static final int FLAG_ENCRYPTED = 0x08;
	/** Android Auto splits messages into frames of at most this much plain data */
	public static final int MAX_FRAME_PAYLOAD = 0x4000;

	public static final class Message {
		public final int channel;
		public final boolean control;
		public final int id;
		public final byte[] data;       // the message after its 2-byte id

		Message(int channel, boolean control, int id, byte[] data) {
			this.channel = channel;
			this.control = control;
			this.id = id;
			this.data = data;
		}

		@Override
		public String toString() {
			return "ch" + channel + (control ? " control" : "") + " id 0x" + Integer.toHexString(id) + " (" + data.length + " bytes)";
		}
	}

	private final DataInputStream in;
	private final OutputStream out;
	private final SSLEngine engine;
	private volatile boolean encrypted = false;
	private final Object sendLock = new Object();
	private final Object decryptLock = new Object();
	private final Map<Integer, ByteArrayOutputStream> partial = new HashMap<>();
	private ByteBuffer netIn;
	private ByteBuffer appIn;
	private ByteBuffer netOut;

	public Transport(InputStream in, OutputStream out, SSLContext sslContext) {
		this.in = new DataInputStream(in);
		this.out = out;
		this.engine = sslContext.createSSLEngine();
		this.engine.setUseClientMode(true);       // the head unit is the TLS client
		try {
			this.engine.setEnabledProtocols(new String[]{"TLSv1.2"});
		} catch (IllegalArgumentException e) {
			// keep the defaults
		}
		int packet = engine.getSession().getPacketBufferSize();
		int app = engine.getSession().getApplicationBufferSize();
		netIn = ByteBuffer.allocate(Math.max(packet, MAX_FRAME_PAYLOAD + 2048));
		appIn = ByteBuffer.allocate(Math.max(app, MAX_FRAME_PAYLOAD + 2048));
		netOut = ByteBuffer.allocate(Math.max(packet, MAX_FRAME_PAYLOAD + 2048));
	}

	/* ---------- sending ---------- */

	/** Sends a message as plain text. Only the version request, TLS handshake and auth complete go this way */
	public void sendPlain(int channel, int id, byte[] data) throws IOException {
		send(channel, false, id, data, false);
	}

	/** Sends a message, encrypted once the handshake is done */
	public void send(int channel, boolean control, int id, byte[] data) throws IOException {
		send(channel, control, id, data, encrypted);
	}

	private void send(int channel, boolean control, int id, byte[] data, boolean encrypt) throws IOException {
		byte[] plain = new byte[data.length + 2];
		plain[0] = (byte) (id >> 8);
		plain[1] = (byte) id;
		System.arraycopy(data, 0, plain, 2, data.length);
		synchronized (sendLock) {
			int offset = 0;
			int total = plain.length;
			ByteArrayOutputStream frames = new ByteArrayOutputStream(total + 64);
			do {
				int size = Math.min(MAX_FRAME_PAYLOAD, total - offset);
				boolean first = offset == 0;
				boolean last = offset + size >= total;
				int type = (first ? FRAME_FIRST : 0) | (last ? FRAME_LAST : 0);
				byte[] payload = new byte[size];
				System.arraycopy(plain, offset, payload, 0, size);
				if (encrypt) payload = encrypt(payload);
				int flags = type | (control ? FLAG_CONTROL : 0) | (encrypt ? FLAG_ENCRYPTED : 0);
				frames.write(channel);
				frames.write(flags);
				frames.write((payload.length >> 8) & 0xFF);
				frames.write(payload.length & 0xFF);
				if (type == FRAME_FIRST) {
					frames.write((total >> 24) & 0xFF);
					frames.write((total >> 16) & 0xFF);
					frames.write((total >> 8) & 0xFF);
					frames.write(total & 0xFF);
				}
				frames.write(payload, 0, payload.length);
				offset += size;
			} while (offset < total);
			out.write(frames.toByteArray());
			out.flush();
		}
	}

	private byte[] encrypt(byte[] plain) throws IOException {
		ByteBuffer src = ByteBuffer.wrap(plain);
		ByteArrayOutputStream result = new ByteArrayOutputStream(plain.length + 64);
		while (src.hasRemaining()) {
			netOut.clear();
			SSLEngineResult r = engine.wrap(src, netOut);
			if (r.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
				netOut = ByteBuffer.allocate(netOut.capacity() * 2);
				continue;
			}
			if (r.getStatus() != SSLEngineResult.Status.OK) throw new SSLException("Encrypting failed: " + r.getStatus());
			netOut.flip();
			result.write(netOut.array(), 0, netOut.limit());
		}
		return result.toByteArray();
	}

	/* ---------- receiving ---------- */

	/** Reads the next whole message. Blocks until it arrives */
	public Message read() throws IOException {
		while (true) {
			int channel = in.readUnsignedByte();
			int flags = in.readUnsignedByte();
			int size = in.readUnsignedShort();
			int type = flags & FRAME_BULK;
			if (type == FRAME_FIRST) in.readInt();     // the whole message's length; we just join the frames
			byte[] payload = new byte[size];
			in.readFully(payload);
			if ((flags & FLAG_ENCRYPTED) != 0) payload = decrypt(payload);

			int key = channel | ((flags & FLAG_CONTROL) << 8);
			byte[] whole;
			if (type == FRAME_BULK) {
				whole = payload;
			} else {
				ByteArrayOutputStream buffer = partial.get(key);
				if (type == FRAME_FIRST || buffer == null) {
					buffer = new ByteArrayOutputStream();
					partial.put(key, buffer);
				}
				buffer.write(payload, 0, payload.length);
				if (type != FRAME_LAST) continue;
				partial.remove(key);
				whole = buffer.toByteArray();
			}
			if (whole.length < 2) continue;
			int id = ((whole[0] & 0xFF) << 8) | (whole[1] & 0xFF);
			byte[] data = new byte[whole.length - 2];
			System.arraycopy(whole, 2, data, 0, data.length);
			return new Message(channel, (flags & FLAG_CONTROL) != 0, id, data);
		}
	}

	private byte[] decrypt(byte[] cipher) throws IOException {
		synchronized (decryptLock) {
			ensureNetIn(cipher.length);
			netIn.put(cipher);
			netIn.flip();
			ByteArrayOutputStream result = new ByteArrayOutputStream(cipher.length);
			while (netIn.hasRemaining()) {
				appIn.clear();
				SSLEngineResult r = engine.unwrap(netIn, appIn);
				if (r.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
					appIn = ByteBuffer.allocate(appIn.capacity() * 2);
					continue;
				}
				if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) break;  // the rest comes in the next frame
				if (r.getStatus() == SSLEngineResult.Status.CLOSED) throw new EOFException("The phone closed the secure link");
				appIn.flip();
				result.write(appIn.array(), 0, appIn.limit());
				if (r.bytesConsumed() == 0 && r.bytesProduced() == 0) break;
			}
			netIn.compact();
			return result.toByteArray();
		}
	}

	private void ensureNetIn(int more) {
		if (netIn.remaining() < more) {
			ByteBuffer bigger = ByteBuffer.allocate(netIn.position() + more + 4096);
			netIn.flip();
			bigger.put(netIn);
			netIn = bigger;
		}
	}

	/* ---------- the start of a connection ---------- */

	public static final int MSG_VERSION_REQUEST = 0x0001;
	public static final int MSG_VERSION_RESPONSE = 0x0002;
	public static final int MSG_SSL_HANDSHAKE = 0x0003;
	public static final int MSG_AUTH_COMPLETE = 0x0004;

	/** Agrees the protocol version and sets up encryption. Messages that arrive out of turn are an error */
	public void connect() throws IOException {
		sendPlain(0, MSG_VERSION_REQUEST, new byte[]{0, 1, 0, 1});
		Message version = read();
		if (version.id != MSG_VERSION_RESPONSE || version.data.length < 6) {
			throw new IOException("Expected the phone's version, got " + version);
		}
		int status = ((version.data[4] & 0xFF) << 8) | (version.data[5] & 0xFF);
		if (status != 0) {
			throw new IOException("The phone refused the protocol version " + (version.data[0] << 8 | version.data[1]) + "." + (version.data[2] << 8 | version.data[3]));
		}
		handshake();
		sendPlain(0, MSG_AUTH_COMPLETE, new Proto.Writer().varint(1, 0).toByteArray());
		encrypted = true;
	}

	private void handshake() throws IOException {
		engine.beginHandshake();
		ByteArrayOutputStream pending = new ByteArrayOutputStream();
		ByteBuffer empty = ByteBuffer.allocate(0);
		int rounds = 0;
		while (true) {
			if (++rounds > 200) throw new SSLException("The secure handshake went round too many times");
			SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
			switch (hs) {
				case NEED_TASK: {
					Runnable task;
					while ((task = engine.getDelegatedTask()) != null) task.run();
					break;
				}
				case NEED_WRAP: {
					netOut.clear();
					SSLEngineResult r = engine.wrap(empty, netOut);
					if (r.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
						netOut = ByteBuffer.allocate(netOut.capacity() * 2);
						break;
					}
					netOut.flip();
					pending.write(netOut.array(), 0, netOut.limit());
					if (r.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_WRAP && pending.size() > 0) {
						sendPlain(0, MSG_SSL_HANDSHAKE, pending.toByteArray());
						pending.reset();
					}
					if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) return;
					break;
				}
				case NEED_UNWRAP: {
					netIn.flip();
					appIn.clear();
					SSLEngineResult r = netIn.hasRemaining() ? engine.unwrap(netIn, appIn) : null;
					netIn.compact();
					if (r == null || r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
						Message m = read();
						if (m.id != MSG_SSL_HANDSHAKE) throw new IOException("Expected the secure handshake, got " + m);
						ensureNetIn(m.data.length);
						netIn.put(m.data);
					} else if (r.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
						appIn = ByteBuffer.allocate(appIn.capacity() * 2);
					} else if (r.getStatus() == SSLEngineResult.Status.CLOSED) {
						throw new EOFException("The phone closed the secure handshake");
					} else if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
						return;
					}
					break;
				}
				case FINISHED:
				case NOT_HANDSHAKING:
					return;
				default:
					throw new SSLException("Unexpected handshake state " + hs);
			}
		}
	}
}
