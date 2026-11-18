package me.hufman.androidautoidrive.androidauto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The head unit certificate that Android Auto asks for during the secure handshake.
 *
 * i3D doesn't include one. The owner supplies a PEM text with the certificate and its RSA private key
 * (PKCS#1 "RSA PRIVATE KEY" or PKCS#8 "PRIVATE KEY"), which can be in one file or pasted together.
 */
public final class Credentials {
	private Credentials() {}

	private static final Pattern BLOCK = Pattern.compile("-----BEGIN ([A-Z ]+)-----([^-]+)-----END \\1-----");

	public static final class Parsed {
		public final List<X509Certificate> certificates;
		public final PrivateKey key;

		Parsed(List<X509Certificate> certificates, PrivateKey key) {
			this.certificates = certificates;
			this.key = key;
		}
	}

	/** Reads the certificate and key out of PEM text. Throws with a plain explanation if either is missing */
	public static Parsed parse(String pem) throws Exception {
		List<X509Certificate> certs = new ArrayList<>();
		PrivateKey key = null;
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		Matcher m = BLOCK.matcher(pem);
		while (m.find()) {
			String type = m.group(1).trim();
			byte[] der = base64(m.group(2));
			if (type.equals("CERTIFICATE")) {
				certs.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
			} else if (type.equals("PRIVATE KEY")) {
				key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
			} else if (type.equals("RSA PRIVATE KEY")) {
				key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs1ToPkcs8(der)));
			}
		}
		if (certs.isEmpty()) throw new IllegalArgumentException("No certificate found (a block starting -----BEGIN CERTIFICATE-----)");
		if (key == null) throw new IllegalArgumentException("No private key found (a block starting -----BEGIN RSA PRIVATE KEY----- or -----BEGIN PRIVATE KEY-----)");
		return new Parsed(certs, key);
	}

	/** A TLS client set up with the head unit certificate. The phone's own certificate is accepted as it is */
	public static SSLContext sslContext(String pem) throws Exception {
		Parsed parsed = parse(pem);
		KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
		store.load(null, null);
		char[] password = "i3d".toCharArray();
		store.setKeyEntry("headunit", parsed.key, password, parsed.certificates.toArray(new Certificate[0]));
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(store, password);
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(kmf.getKeyManagers(), new TrustManager[]{new AcceptPhone()}, new SecureRandom());
		return context;
	}

	/**
	 * The phone is on the same device (127.0.0.1), and its certificate isn't one we could check anyway,
	 * so the link is accepted. The encryption is only there because Android Auto requires it.
	 */
	private static final class AcceptPhone implements X509TrustManager {
		@Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
		@Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
		@Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
	}

	/** Wraps a PKCS#1 RSA key in the PKCS#8 envelope that Java's KeyFactory reads */
	static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
		byte[] algorithm = {
				0x30, 0x0D,
				0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01,
				0x05, 0x00};
		byte[] version = {0x02, 0x01, 0x00};
		byte[] octets = der(0x04, pkcs1);
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(version, 0, version.length);
		body.write(algorithm, 0, algorithm.length);
		body.write(octets, 0, octets.length);
		return der(0x30, body.toByteArray());
	}

	private static byte[] der(int tag, byte[] content) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(tag);
		int len = content.length;
		if (len < 0x80) {
			out.write(len);
		} else if (len < 0x100) {
			out.write(0x81); out.write(len);
		} else if (len < 0x10000) {
			out.write(0x82); out.write(len >> 8); out.write(len & 0xFF);
		} else {
			out.write(0x83); out.write(len >> 16); out.write((len >> 8) & 0xFF); out.write(len & 0xFF);
		}
		out.write(content, 0, content.length);
		return out.toByteArray();
	}

	/** Base64 that ignores line breaks and spaces, and works on every Android version */
	static byte[] base64(String text) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() * 3 / 4);
		int buffer = 0, bits = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			int v;
			if (c >= 'A' && c <= 'Z') v = c - 'A';
			else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
			else if (c >= '0' && c <= '9') v = c - '0' + 52;
			else if (c == '+' || c == '-') v = 62;
			else if (c == '/' || c == '_') v = 63;
			else continue;      // padding, line breaks, spaces
			buffer = (buffer << 6) | v;
			bits += 6;
			if (bits >= 8) {
				bits -= 8;
				out.write((buffer >> bits) & 0xFF);
			}
		}
		return out.toByteArray();
	}
}
