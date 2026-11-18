package me.hufman.androidautoidrive.androidauto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Runs i3D's head unit against a pretend phone, written separately here from the phone's side of the
 * protocol: version, TLS handshake with the head unit's certificate, service discovery, channel opening,
 * video set-up and a few frames, the knob, and a clean shutdown.
 */
public class HeadUnitTest {

	static String resource(String name) {
		InputStream in = HeadUnitTest.class.getResourceAsStream("/androidauto/" + name);
		if (in == null) throw new IllegalStateException("Missing test file " + name);
		Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A");
		return s.hasNext() ? s.next() : "";
	}

	@Test
	public void protoRoundTrip() {
		byte[] data = new Proto.Writer().varint(1, 300).string(2, "i3D").bool(3, true).varint(4, -1).toByteArray();
		Proto.Reader r = new Proto.Reader(data);
		assertTrue(r.next()); assertEquals(1, r.field()); assertEquals(300, r.varint());
		assertTrue(r.next()); assertEquals(2, r.field()); assertEquals("i3D", r.string());
		assertTrue(r.next()); assertEquals(3, r.field()); assertTrue(r.bool());
		assertTrue(r.next()); assertEquals(4, r.field()); assertEquals(-1L, r.varint());
		assertTrue(!r.next());
		assertEquals(300, Proto.Reader.findVarint(data, 1, 0));
		assertEquals(7, Proto.Reader.findVarint(data, 9, 7));
	}

	@Test
	public void videoSetupMatchesTheWideScreen() {
		HeadUnit.VideoSetup v = HeadUnit.VideoSetup.forScreen(1280, 480, 160);
		assertEquals(2, v.resolution);
		assertEquals(0, v.marginWidth);
		assertEquals(240, v.marginHeight);
		HeadUnit.VideoSetup narrow = HeadUnit.VideoSetup.forScreen(800, 480, 160);
		assertEquals(1, narrow.resolution);
		assertEquals(0, narrow.marginWidth);
		assertEquals(0, narrow.marginHeight);
	}

	@Test
	public void readsPkcs1AndPkcs8Keys() throws Exception {
		Credentials.Parsed parsed = Credentials.parse(resource("test-headunit.pem"));
		assertEquals(1, parsed.certificates.size());
		assertEquals("RSA", parsed.key.getAlgorithm());
		Credentials.Parsed phone = Credentials.parse(resource("test-phone.pem"));
		assertEquals("RSA", phone.key.getAlgorithm());
	}

	@Test(timeout = 20000)
	public void fullSessionWithAPretendPhone() throws Exception {
		final ServerSocket server = new ServerSocket(0);
		final FakePhone phone = new FakePhone(resource("test-phone.pem"));
		final Throwable[] phoneProblem = new Throwable[1];
		Thread phoneThread = new Thread(new Runnable() {
			public void run() {
				try (Socket s = server.accept()) {
					phone.run(s.getInputStream(), s.getOutputStream());
				} catch (Throwable t) {
					phoneProblem[0] = t;
				}
			}
		});
		phoneThread.start();

		final List<String> events = Collections.synchronizedList(new ArrayList<String>());
		final ByteArrayOutputStream video = new ByteArrayOutputStream();
		final CountDownLatch framesSeen = new CountDownLatch(3);
		Socket socket = new Socket("127.0.0.1", server.getLocalPort());
		final HeadUnit hu = new HeadUnit(socket.getInputStream(), socket.getOutputStream(),
				Credentials.sslContext(resource("test-headunit.pem")),
				HeadUnit.VideoSetup.forScreen(1280, 480, 160),
				new HeadUnit.Listener() {
					public void onVideo(byte[] data, int offset, int length, long ts, boolean config) {
						synchronized (video) { video.write(data, offset, length); }
						events.add(config ? "video config" : "video frame");
						framesSeen.countDown();
					}
					public void onVideoStop() { events.add("video stop"); }
					public void onAudio(int channel, byte[] data, int offset, int length) { events.add("audio " + channel + " " + length); }
					public void onAudioStop(int channel) {}
					public void onMicrophone(boolean open) { events.add("mic " + open); }
					public void onStatus(String status) { events.add("status " + status); }
				});
		Thread huThread = new Thread(new Runnable() {
			public void run() {
				try { hu.run(); } catch (IOException e) { events.add("closed " + e); }
			}
		});
		huThread.start();

		if (!framesSeen.await(10, TimeUnit.SECONDS)) { if (phoneProblem[0] != null) throw new AssertionError("phone: " + phoneProblem[0] + " events " + events, phoneProblem[0]); throw new AssertionError("no video: " + events); }
		hu.rotate(1);
		hu.rotate(-2);
		hu.key(HeadUnit.KEY_DPAD_CENTER);
		hu.setMoving(true);
		phone.finish();
		huThread.join(10000);
		phoneThread.join(10000);
		socket.close();
		server.close();
		if (phoneProblem[0] != null) throw new AssertionError("The pretend phone failed", phoneProblem[0]);

		assertTrue(events.toString(), events.contains("video config"));
		assertTrue(events.toString(), events.contains("audio 4 4096"));
		assertEquals(3 + 4096 * 2 + 8192, video.size());  // 3 bytes of set-up data and three frames
		assertTrue("service discovery listed the video channel", phone.discovery.contains("video 1280x720 margins 0x240 dpi 160"));
		assertEquals(Integer.valueOf(1), phone.rotary.get(0));
		assertEquals(Integer.valueOf(-2), phone.rotary.get(1));
		assertEquals("[23 down, 23 up]", phone.keys.toString());
		assertEquals(phone.framesSent, phone.acks);
		assertTrue("driving status sent", phone.drivingStatus.contains(HeadUnit.DRIVING_RESTRICTED));
		assertTrue(events.toString(), events.contains("status Android Auto closed the link"));
	}

	/** The phone's side: a TLS server that wants the head unit's certificate */
	static class FakePhone {
		final SSLEngine engine;
		DataInputStream in;
		OutputStream out;
		boolean encrypted = false;
		String discovery = "";
		final List<Integer> rotary = Collections.synchronizedList(new ArrayList<Integer>());
		final List<String> keys = Collections.synchronizedList(new ArrayList<String>());
		final List<Integer> drivingStatus = Collections.synchronizedList(new ArrayList<Integer>());
		volatile int framesSent = 0;
		volatile int acks = 0;
		final CountDownLatch done = new CountDownLatch(1);
		final CountDownLatch inputsSeen = new CountDownLatch(4);

		FakePhone(String pem) throws Exception {
			Credentials.Parsed p = Credentials.parse(pem);
			KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
			ks.load(null, null);
			ks.setKeyEntry("phone", p.key, "x".toCharArray(), p.certificates.toArray(new Certificate[0]));
			KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			kmf.init(ks, "x".toCharArray());
			SSLContext ctx = SSLContext.getInstance("TLS");
			ctx.init(kmf.getKeyManagers(), new TrustManager[]{new X509TrustManager() {
				public void checkClientTrusted(X509Certificate[] chain, String a) throws java.security.cert.CertificateException {
					if (!chain[0].getSubjectX500Principal().getName().contains("test-headunit"))
						throw new java.security.cert.CertificateException("Not the head unit");
				}
				public void checkServerTrusted(X509Certificate[] chain, String a) {}
				public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
			}}, new SecureRandom());
			engine = ctx.createSSLEngine();
			engine.setUseClientMode(false);
			engine.setNeedClientAuth(true);
			engine.setEnabledProtocols(new String[]{"TLSv1.2"});
		}

		void finish() throws InterruptedException {
			inputsSeen.await(5, TimeUnit.SECONDS);
			done.countDown();
		}

		/* framing, the phone's way */
		void frame(int ch, int flags, byte[] payload, int total) throws IOException {
			ByteArrayOutputStream f = new ByteArrayOutputStream();
			f.write(ch); f.write(flags); f.write(payload.length >> 8); f.write(payload.length & 0xFF);
			if ((flags & 3) == 1) { f.write(total >>> 24); f.write((total >> 16) & 0xFF); f.write((total >> 8) & 0xFF); f.write(total & 0xFF); }
			f.write(payload);
			synchronized (this) { out.write(f.toByteArray()); out.flush(); }
		}

		void send(int ch, boolean control, int id, byte[] data) throws IOException {
			byte[] plain = new byte[data.length + 2];
			plain[0] = (byte) (id >> 8); plain[1] = (byte) id;
			System.arraycopy(data, 0, plain, 2, data.length);
			int extra = (control ? 4 : 0) | (encrypted ? 8 : 0);
			if (plain.length <= 6000) {
				frame(ch, 3 | extra, encrypted ? wrap(plain) : plain, plain.length);
			} else {
				// split in two frames, to check the head unit joins them
				byte[] a = new byte[6000], b = new byte[plain.length - 6000];
				System.arraycopy(plain, 0, a, 0, a.length);
				System.arraycopy(plain, 6000, b, 0, b.length);
				frame(ch, 1 | extra, encrypted ? wrap(a) : a, plain.length);
				frame(ch, 2 | extra, encrypted ? wrap(b) : b, 0);
			}
		}

		byte[] wrap(byte[] plain) throws IOException {
			ByteBuffer net = ByteBuffer.allocate(engine.getSession().getPacketBufferSize() + plain.length);
			engine.wrap(ByteBuffer.wrap(plain), net);
			net.flip();
			byte[] r = new byte[net.remaining()];
			net.get(r);
			return r;
		}

		byte[] unwrap(byte[] cipher) throws IOException {
			ByteBuffer app = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize() + cipher.length);
			engine.unwrap(ByteBuffer.wrap(cipher), app);
			app.flip();
			byte[] r = new byte[app.remaining()];
			app.get(r);
			return r;
		}

		int[] lastHeader = new int[2];
		byte[] read() throws IOException {
			int ch = in.readUnsignedByte(), flags = in.readUnsignedByte(), size = in.readUnsignedShort();
			if ((flags & 3) == 1) in.readInt();
			byte[] p = new byte[size];
			in.readFully(p);
			if ((flags & 8) != 0) p = unwrap(p);
			if ((flags & 3) != 3) throw new IOException("The test phone expects single-frame messages from the head unit");
			lastHeader[0] = ch; lastHeader[1] = flags;
			return p;
		}

		static int id(byte[] m) { return ((m[0] & 0xFF) << 8) | (m[1] & 0xFF); }
		static byte[] body(byte[] m) { byte[] b = new byte[m.length - 2]; System.arraycopy(m, 2, b, 0, b.length); return b; }

		void run(InputStream rawIn, OutputStream rawOut) throws Exception {
			in = new DataInputStream(rawIn);
			out = rawOut;
			// version
			byte[] m = read();
			if (id(m) != 1) throw new IOException("Expected the version request");
			send(0, false, 2, new byte[]{0, 1, 0, 1, 0, 0});
			// TLS handshake, carried in message 3
			engine.beginHandshake();
			ByteBuffer netIn = ByteBuffer.allocate(65536), app = ByteBuffer.allocate(65536);
			ByteArrayOutputStream pending = new ByteArrayOutputStream();
			while (true) {
				SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
				if (hs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
					Runnable t; while ((t = engine.getDelegatedTask()) != null) t.run();
				} else if (hs == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
					ByteBuffer net = ByteBuffer.allocate(65536);
					SSLEngineResult r = engine.wrap(ByteBuffer.allocate(0), net);
					net.flip();
					byte[] b = new byte[net.remaining()]; net.get(b);
					pending.write(b);
					if (r.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_WRAP) {
						send(0, false, 3, pending.toByteArray());
						pending.reset();
					}
					if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) break;
				} else if (hs == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
					netIn.flip();
					SSLEngineResult r = netIn.hasRemaining() ? engine.unwrap(netIn, app) : null;
					netIn.compact();
					if (r == null || r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
						byte[] hm = read();
						if (id(hm) != 3) throw new IOException("Expected handshake, got " + id(hm));
						netIn.put(body(hm));
					} else if (r.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
						break;
					}
				} else {
					break;
				}
			}
			m = read();
			if (id(m) != 4) throw new IOException("Expected auth complete, got " + id(m));
			encrypted = true;

			// service discovery
			send(0, false, 5, new byte[0]);
			m = read();
			if (id(m) != 6) throw new IOException("Expected service discovery response");
			discovery = describe(body(m));

			// open the channels the head unit offered
			for (int ch : new int[]{1, 2, 3, 4}) {
				send(ch, true, 7, new Proto.Writer().varint(1, 0).varint(2, ch).toByteArray());
				m = read();
				if (id(m) != 8 || lastHeader[0] != ch || (lastHeader[1] & 4) == 0) throw new IOException("Expected channel open response on " + ch);
			}
			// sensors
			send(2, false, 0x8001, new Proto.Writer().varint(1, 13).toByteArray());
			expect(2, 0x8002);
			readSensor(read());
			// input
			send(1, false, 0x8002, new Proto.Writer().varint(1, 65536).toByteArray());
			expect(1, 0x8003);
			// video: set up, focus, start, then data
			send(3, false, 0x8000, new Proto.Writer().varint(1, 3).toByteArray());
			byte[] setup = expect(3, 0x8003);
			if (Proto.Reader.findVarint(setup, 1, 0) != 2) throw new IOException("Video set-up not ready");
			byte[] focus = expect(3, 0x8008);
			if (Proto.Reader.findVarint(focus, 1, 0) != 1) throw new IOException("Video not focused");
			send(3, false, 0x8001, new Proto.Writer().varint(1, 5).varint(2, 0).toByteArray());
			send(3, false, 0x0001, new byte[]{0, 0, 1});
			framesSent++;
			ackOf(3, 5);
			for (int i = 0; i < 3; i++) {
				byte[] frame = new byte[8 + (i == 2 ? 8192 : 4096)];   // the last one is split over two frames
				frame[7] = (byte) i;
				send(3, false, 0x0000, frame);
				framesSent++;
				ackOf(3, 5);
			}
			// media audio
			send(4, false, 0x8000, new Proto.Writer().varint(1, 1).toByteArray());
			expect(4, 0x8003);
			send(4, false, 0x8001, new Proto.Writer().varint(1, 9).varint(2, 0).toByteArray());
			send(4, false, 0x0000, new byte[8 + 4096]);
			ackOf(4, 9);
			// a ping
			send(0, false, 0x000B, new Proto.Writer().varint(1, 1234).toByteArray());
			byte[] pong = expect(0, 0x000C);
			if (Proto.Reader.findVarint(pong, 1, 0) != 1234) throw new IOException("Ping answered wrongly");

			// the knob: two turns and a press (down and up), then the driving status
			while (inputsSeen.getCount() > 0) {
				byte[] e = read();
				if (lastHeader[0] == 1 && id(e) == 0x8001) {
					readInput(body(e));
					inputsSeen.countDown();
				} else if (lastHeader[0] == 2 && id(e) == 0x8003) {
					readSensor(e);
				}
			}
			done.await(5, TimeUnit.SECONDS);
			// the driving status that followed
			while (drivingStatus.size() < 2) {
				byte[] e = read();
				if (lastHeader[0] == 2 && id(e) == 0x8003) readSensor(e);
			}
			send(0, false, 0x000F, new Proto.Writer().varint(1, 1).toByteArray());
			expect(0, 0x0010);
		}

		byte[] expect(int ch, int wanted) throws IOException {
			byte[] m = read();
			// the knob and sensors can arrive at any time: note them and carry on waiting
			while (!(lastHeader[0] == ch && id(m) == wanted) && (lastHeader[0] == 1 || lastHeader[0] == 2) && (id(m) == 0x8001 || id(m) == 0x8003)) {
				if (lastHeader[0] == 1) { readInput(body(m)); inputsSeen.countDown(); } else readSensor(m);
				m = read();
			}
			if (lastHeader[0] != ch || id(m) != wanted)
				throw new IOException("Expected 0x" + Integer.toHexString(wanted) + " on " + ch + ", got 0x" + Integer.toHexString(id(m)) + " on " + lastHeader[0]);
			return body(m);
		}

		void ackOf(int ch, int session) throws IOException {
			byte[] a = expect(ch, 0x8004);
			if (Proto.Reader.findVarint(a, 1, -1) != session) throw new IOException("Ack for the wrong session");
			acks += ch == 3 ? 1 : 0;
		}

		void readSensor(byte[] m) {
			Proto.Reader r = new Proto.Reader(body(m));
			while (r.next()) {
				if (r.field() == 13) drivingStatus.add((int) Proto.Reader.findVarint(r.bytes(), 1, -1));
				else r.skip();
			}
		}

		void readInput(byte[] b) {
			Proto.Reader r = new Proto.Reader(b);
			while (r.next()) {
				if (r.field() == 6) {
					Proto.Reader events = new Proto.Reader(r.bytes());
					while (events.next()) {
						byte[] ev = events.bytes();
						long code = Proto.Reader.findVarint(ev, 1, 0);
						long delta = Proto.Reader.findVarint(ev, 2, 0);
						if (code == 65536) rotary.add((int) delta);
					}
				} else if (r.field() == 4) {
					Proto.Reader events = new Proto.Reader(r.bytes());
					while (events.next()) {
						byte[] ev = events.bytes();
						keys.add(Proto.Reader.findVarint(ev, 1, 0) + (Proto.Reader.findVarint(ev, 2, 0) != 0 ? " down" : " up"));
					}
				} else {
					r.skip();
				}
			}
		}

		/** A readable summary of the channels in the service discovery response */
		static String describe(byte[] sdr) {
			StringBuilder s = new StringBuilder();
			Proto.Reader r = new Proto.Reader(sdr);
			while (r.next()) {
				if (r.field() != 1) { r.skip(); continue; }
				Proto.Reader ch = new Proto.Reader(r.bytes());
				while (ch.next()) {
					if (ch.field() == 3) {
						Proto.Reader av = new Proto.Reader(ch.bytes());
						while (av.next()) {
							if (av.field() == 4) {
								byte[] vc = av.bytes();
								long res = Proto.Reader.findVarint(vc, 1, 0);
								s.append("video ").append(res == 2 ? "1280x720" : res == 1 ? "800x480" : "?")
										.append(" margins ").append(Proto.Reader.findVarint(vc, 3, -1)).append('x').append(Proto.Reader.findVarint(vc, 4, -1))
										.append(" dpi ").append(Proto.Reader.findVarint(vc, 5, -1)).append("; ");
							} else av.skip();
						}
					} else ch.skip();
				}
			}
			return s.toString();
		}
	}
}
