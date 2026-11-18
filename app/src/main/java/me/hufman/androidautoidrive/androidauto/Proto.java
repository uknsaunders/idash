package me.hufman.androidautoidrive.androidauto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * Just enough protobuf to talk to Android Auto: writing and reading varints and length-delimited fields.
 * Android Auto's messages are small and few, so a hand-written encoder keeps the build free of protoc.
 */
public final class Proto {
	private Proto() {}

	private static final Charset UTF8 = Charset.forName("UTF-8");

	/** Builds one protobuf message */
	public static final class Writer {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		private void rawVarint(long value) {
			while ((value & ~0x7FL) != 0) {
				out.write((int) ((value & 0x7F) | 0x80));
				value >>>= 7;
			}
			out.write((int) value);
		}

		private void tag(int field, int wireType) {
			rawVarint(((long) field << 3) | wireType);
		}

		/** int32, int64, uint32, uint64, enum. Negative int32 values are sign-extended to 10 bytes, as protobuf does */
		public Writer varint(int field, long value) {
			tag(field, 0);
			rawVarint(value);
			return this;
		}

		public Writer bool(int field, boolean value) {
			return varint(field, value ? 1 : 0);
		}

		public Writer bytes(int field, byte[] value) {
			tag(field, 2);
			rawVarint(value.length);
			out.write(value, 0, value.length);
			return this;
		}

		public Writer string(int field, String value) {
			return bytes(field, value.getBytes(UTF8));
		}

		public Writer message(int field, Writer value) {
			return bytes(field, value.toByteArray());
		}

		public byte[] toByteArray() {
			return out.toByteArray();
		}
	}

	/** Steps through the fields of one protobuf message */
	public static final class Reader {
		private final byte[] data;
		private int pos;
		private final int end;
		private int field;
		private int wireType;

		public Reader(byte[] data) {
			this(data, 0, data.length);
		}

		public Reader(byte[] data, int offset, int length) {
			this.data = data;
			this.pos = offset;
			this.end = offset + length;
		}

		private long rawVarint() {
			long result = 0;
			int shift = 0;
			while (pos < end) {
				int b = data[pos++] & 0xFF;
				result |= (long) (b & 0x7F) << shift;
				if ((b & 0x80) == 0) return result;
				shift += 7;
				if (shift > 63) break;
			}
			throw new IllegalArgumentException("Bad varint");
		}

		/** Moves to the next field. Returns false at the end of the message */
		public boolean next() {
			if (pos >= end) return false;
			long t = rawVarint();
			field = (int) (t >>> 3);
			wireType = (int) (t & 7);
			return true;
		}

		public int field() { return field; }
		public int wireType() { return wireType; }

		public long varint() {
			if (wireType != 0) throw new IllegalArgumentException("Field " + field + " is not a varint");
			return rawVarint();
		}

		public boolean bool() { return varint() != 0; }

		public byte[] bytes() {
			if (wireType != 2) throw new IllegalArgumentException("Field " + field + " is not length-delimited");
			int length = (int) rawVarint();
			if (length < 0 || pos + length > end) throw new IllegalArgumentException("Bad length");
			byte[] result = new byte[length];
			System.arraycopy(data, pos, result, 0, length);
			pos += length;
			return result;
		}

		public String string() { return new String(bytes(), UTF8); }

		/** Skips the current field's value */
		public void skip() {
			switch (wireType) {
				case 0: rawVarint(); break;
				case 1: pos += 8; break;
				case 2: {
					int length = (int) rawVarint();     // read first: "pos += rawVarint()" would add to the old position
					pos += length;
					break;
				}
				case 5: pos += 4; break;
				default: throw new IllegalArgumentException("Unknown wire type " + wireType);
			}
		}

		/** Reads one varint field from a message, or the fallback if it isn't there */
		public static long findVarint(byte[] message, int wanted, long fallback) {
			try {
				Reader r = new Reader(message);
				while (r.next()) {
					if (r.field() == wanted && r.wireType() == 0) return r.varint();
					r.skip();
				}
			} catch (RuntimeException e) {
				// a damaged message: use the fallback
			}
			return fallback;
		}
	}
}
