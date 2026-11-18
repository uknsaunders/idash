package me.hufman.androidautoidrive.androidauto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.net.ssl.SSLContext;

/**
 * One Android Auto session, from the head unit's side: i3D plays the part of the car's screen.
 *
 * It tells the phone what it can show (one video stream sized for the iDrive screen, three audio
 * streams, a microphone, a rotary controller and the driving and night sensors), then passes the
 * video and sound to a {@link Listener} and sends the knob's turns and presses back.
 */
public class HeadUnit {
	/* channels, chosen by the head unit in the service discovery response */
	public static final int CH_CONTROL = 0;
	public static final int CH_INPUT = 1;
	public static final int CH_SENSOR = 2;
	public static final int CH_VIDEO = 3;
	public static final int CH_MEDIA_AUDIO = 4;
	public static final int CH_SPEECH_AUDIO = 5;
	public static final int CH_SYSTEM_AUDIO = 6;
	public static final int CH_MIC = 7;

	/* control channel messages */
	static final int MSG_SERVICE_DISCOVERY_REQUEST = 0x0005;
	static final int MSG_SERVICE_DISCOVERY_RESPONSE = 0x0006;
	static final int MSG_CHANNEL_OPEN_REQUEST = 0x0007;
	static final int MSG_CHANNEL_OPEN_RESPONSE = 0x0008;
	static final int MSG_PING_REQUEST = 0x000B;
	static final int MSG_PING_RESPONSE = 0x000C;
	static final int MSG_NAV_FOCUS_REQUEST = 0x000D;
	static final int MSG_NAV_FOCUS_RESPONSE = 0x000E;
	static final int MSG_SHUTDOWN_REQUEST = 0x000F;
	static final int MSG_SHUTDOWN_RESPONSE = 0x0010;
	static final int MSG_VOICE_SESSION = 0x0011;
	static final int MSG_AUDIO_FOCUS_REQUEST = 0x0012;
	static final int MSG_AUDIO_FOCUS_RESPONSE = 0x0013;

	/* audio and video channel messages */
	static final int MSG_MEDIA_WITH_TIMESTAMP = 0x0000;
	static final int MSG_MEDIA = 0x0001;
	static final int MSG_AV_SETUP_REQUEST = 0x8000;
	static final int MSG_AV_START = 0x8001;
	static final int MSG_AV_STOP = 0x8002;
	static final int MSG_AV_SETUP_RESPONSE = 0x8003;
	static final int MSG_AV_MEDIA_ACK = 0x8004;
	static final int MSG_AV_INPUT_OPEN_REQUEST = 0x8005;
	static final int MSG_AV_INPUT_OPEN_RESPONSE = 0x8006;
	static final int MSG_VIDEO_FOCUS_REQUEST = 0x8007;
	static final int MSG_VIDEO_FOCUS_INDICATION = 0x8008;

	/* input channel messages */
	static final int MSG_INPUT_EVENT = 0x8001;
	static final int MSG_INPUT_BINDING_REQUEST = 0x8002;
	static final int MSG_INPUT_BINDING_RESPONSE = 0x8003;

	/* sensor channel messages */
	static final int MSG_SENSOR_START_REQUEST = 0x8001;
	static final int MSG_SENSOR_START_RESPONSE = 0x8002;
	static final int MSG_SENSOR_EVENT = 0x8003;
	static final int SENSOR_NIGHT = 10;
	static final int SENSOR_DRIVING_STATUS = 13;

	/* Android key codes that Android Auto understands from a head unit */
	public static final int KEY_HOME = 3;
	public static final int KEY_BACK = 4;
	public static final int KEY_CALL = 5;
	public static final int KEY_END_CALL = 6;
	public static final int KEY_DPAD_UP = 19;
	public static final int KEY_DPAD_DOWN = 20;
	public static final int KEY_DPAD_LEFT = 21;
	public static final int KEY_DPAD_RIGHT = 22;
	public static final int KEY_DPAD_CENTER = 23;
	public static final int KEY_SEARCH = 84;
	public static final int KEY_MEDIA_PLAY_PAUSE = 85;
	public static final int KEY_MEDIA_NEXT = 87;
	public static final int KEY_MEDIA_PREVIOUS = 88;
	public static final int KEY_ROTARY = 65536;
	static final int[] KEYS = {KEY_HOME, KEY_BACK, KEY_CALL, KEY_END_CALL, KEY_DPAD_UP, KEY_DPAD_DOWN, KEY_DPAD_LEFT,
			KEY_DPAD_RIGHT, KEY_DPAD_CENTER, KEY_SEARCH, KEY_MEDIA_PLAY_PAUSE, KEY_MEDIA_NEXT, KEY_MEDIA_PREVIOUS, KEY_ROTARY};

	/** Driving restrictions, sent while the car is moving: no video, no typing, no settings, short messages */
	public static final int DRIVING_UNRESTRICTED = 0;
	public static final int DRIVING_RESTRICTED = 1 | 2 | 8 | 16;

	/** What the phone's picture and sound are handed to */
	public interface Listener {
		/** Each piece of the phone's H.264 stream. config is true for the stream's set-up data */
		void onVideo(byte[] data, int offset, int length, long timestampUs, boolean config);
		void onVideoStop();
		/** PCM sound for one of the audio channels (CH_MEDIA_AUDIO, CH_SPEECH_AUDIO or CH_SYSTEM_AUDIO) */
		void onAudio(int channel, byte[] data, int offset, int length);
		void onAudioStop(int channel);
		/** The phone wants the microphone, for the Assistant or a call */
		void onMicrophone(boolean open);
		/** Something happened worth showing to the driver or writing to the log */
		void onStatus(String status);
	}

	/** How the video is set up: the stream's size, the unused margins, and how dense the screen is */
	public static final class VideoSetup {
		public final int resolution;  // 1 = 800x480, 2 = 1280x720, 3 = 1920x1080
		public final int width;
		public final int height;
		public final int marginWidth;
		public final int marginHeight;
		public final int dpi;

		public VideoSetup(int resolution, int width, int height, int marginWidth, int marginHeight, int dpi) {
			this.resolution = resolution;
			this.width = width;
			this.height = height;
			this.marginWidth = marginWidth;
			this.marginHeight = marginHeight;
			this.dpi = dpi;
		}

		/**
		 * Picks the stream for a screen area: 1280x720 for wide screens, 800x480 otherwise, with margins so
		 * Android Auto lays itself out in exactly the screen's shape
		 */
		public static VideoSetup forScreen(int screenWidth, int screenHeight, int dpi) {
			int resolution = screenWidth > 900 ? 2 : 1;
			int w = resolution == 2 ? 1280 : 800;
			int h = resolution == 2 ? 720 : 480;
			double aspect = (double) screenWidth / Math.max(1, screenHeight);
			int marginW = 0, marginH = 0;
			if (aspect > (double) w / h) {
				marginH = h - (int) Math.round(w / aspect);
			} else {
				marginW = w - (int) Math.round(h * aspect);
			}
			// Android Auto wants even numbers
			marginW &= ~1;
			marginH &= ~1;
			return new VideoSetup(resolution, w, h, Math.max(0, marginW), Math.max(0, marginH), dpi);
		}
	}

	private final Transport transport;
	private final Listener listener;
	private final VideoSetup video;
	private volatile boolean running = true;
	private volatile int videoSession = 0;
	private final int[] audioSessions = new int[8];
	private volatile int micSession = 0;
	private volatile boolean night = false;
	private volatile int driving = DRIVING_UNRESTRICTED;
	private volatile boolean sensorsStarted = false;
	private volatile boolean videoFocused = false;

	public HeadUnit(InputStream in, OutputStream out, SSLContext ssl, VideoSetup video, Listener listener) {
		this.transport = new Transport(in, out, ssl);
		this.video = video;
		this.listener = listener;
	}

	/** Connects and handles the phone's messages until the link closes or stop() is called */
	public void run() throws IOException {
		listener.onStatus("Agreeing a secure link with Android Auto");
		transport.connect();
		listener.onStatus("Secure link made, waiting for Android Auto");
		while (running) {
			Transport.Message m = transport.read();
			try {
				handle(m);
			} catch (RuntimeException e) {
				listener.onStatus("Couldn't understand " + m + ": " + e);
			}
		}
	}

	public void stop() {
		running = false;
	}

	public boolean isVideoFocused() {
		return videoFocused;
	}

	private void handle(Transport.Message m) throws IOException {
		if (m.control && m.id == MSG_CHANNEL_OPEN_REQUEST) {
			transport.send(m.channel, true, MSG_CHANNEL_OPEN_RESPONSE, new Proto.Writer().varint(1, 0).toByteArray());
			return;
		}
		if (m.control && m.channel != CH_CONTROL) {
			// other control messages on a service channel, such as a ping: nothing to do
			return;
		}
		switch (m.channel) {
			case CH_CONTROL: handleControl(m); break;
			case CH_VIDEO: handleVideo(m); break;
			case CH_MEDIA_AUDIO:
			case CH_SPEECH_AUDIO:
			case CH_SYSTEM_AUDIO: handleAudio(m); break;
			case CH_INPUT:
				if (m.id == MSG_INPUT_BINDING_REQUEST) {
					transport.send(CH_INPUT, false, MSG_INPUT_BINDING_RESPONSE, new Proto.Writer().varint(1, 0).toByteArray());
				}
				break;
			case CH_SENSOR:
				if (m.id == MSG_SENSOR_START_REQUEST) {
					transport.send(CH_SENSOR, false, MSG_SENSOR_START_RESPONSE, new Proto.Writer().varint(1, 0).toByteArray());
					sensorsStarted = true;
					sendSensors();
				}
				break;
			case CH_MIC:
				if (m.id == MSG_AV_INPUT_OPEN_REQUEST) {
					boolean open = Proto.Reader.findVarint(m.data, 1, 0) != 0;
					transport.send(CH_MIC, false, MSG_AV_INPUT_OPEN_RESPONSE,
							new Proto.Writer().varint(1, micSession).varint(2, 0).toByteArray());
					listener.onMicrophone(open);
				}
				break;
			default:
				break;
		}
	}

	private void handleControl(Transport.Message m) throws IOException {
		switch (m.id) {
			case MSG_SERVICE_DISCOVERY_REQUEST:
				listener.onStatus("Android Auto asked what this screen can do");
				transport.send(CH_CONTROL, false, MSG_SERVICE_DISCOVERY_RESPONSE, serviceDiscoveryResponse());
				break;
			case MSG_PING_REQUEST: {
				long timestamp = Proto.Reader.findVarint(m.data, 1, 0);
				transport.send(CH_CONTROL, false, MSG_PING_RESPONSE, new Proto.Writer().varint(1, timestamp).toByteArray());
				break;
			}
			case MSG_NAV_FOCUS_REQUEST:
				// 2: Android Auto's own navigation is on screen
				transport.send(CH_CONTROL, false, MSG_NAV_FOCUS_RESPONSE, new Proto.Writer().varint(1, 2).toByteArray());
				break;
			case MSG_AUDIO_FOCUS_REQUEST: {
				// requests: 1 gain, 2 gain for a moment, 3 gain for navigation, 4 release
				// answers: 1 gain, 2 gain for a moment, 3 loss
				long type = Proto.Reader.findVarint(m.data, 1, 1);
				long state = type == 4 ? 3 : type == 2 ? 2 : 1;
				transport.send(CH_CONTROL, false, MSG_AUDIO_FOCUS_RESPONSE, new Proto.Writer().varint(1, state).toByteArray());
				break;
			}
			case MSG_SHUTDOWN_REQUEST:
				transport.send(CH_CONTROL, false, MSG_SHUTDOWN_RESPONSE, new byte[0]);
				listener.onStatus("Android Auto closed the link");
				running = false;
				break;
			case MSG_VOICE_SESSION:
			default:
				break;
		}
	}

	private void handleVideo(Transport.Message m) throws IOException {
		switch (m.id) {
			case MSG_AV_SETUP_REQUEST:
				transport.send(CH_VIDEO, false, MSG_AV_SETUP_RESPONSE, setupResponse());
				sendVideoFocus(true);
				break;
			case MSG_AV_START:
				videoSession = (int) Proto.Reader.findVarint(m.data, 1, 0);
				listener.onStatus("Android Auto's picture started");
				break;
			case MSG_AV_STOP:
				listener.onVideoStop();
				break;
			case MSG_VIDEO_FOCUS_REQUEST:
				sendVideoFocus(true);
				break;
			case MSG_MEDIA_WITH_TIMESTAMP:
				if (m.data.length > 8) {
					long ts = 0;
					for (int i = 0; i < 8; i++) ts = (ts << 8) | (m.data[i] & 0xFF);
					listener.onVideo(m.data, 8, m.data.length - 8, ts, false);
				}
				ack(CH_VIDEO, videoSession);
				break;
			case MSG_MEDIA:
				listener.onVideo(m.data, 0, m.data.length, 0, true);
				ack(CH_VIDEO, videoSession);
				break;
			default:
				break;
		}
	}

	private void handleAudio(Transport.Message m) throws IOException {
		int ch = m.channel;
		switch (m.id) {
			case MSG_AV_SETUP_REQUEST:
				transport.send(ch, false, MSG_AV_SETUP_RESPONSE, setupResponse());
				break;
			case MSG_AV_START:
				audioSessions[ch] = (int) Proto.Reader.findVarint(m.data, 1, 0);
				break;
			case MSG_AV_STOP:
				listener.onAudioStop(ch);
				break;
			case MSG_MEDIA_WITH_TIMESTAMP:
				if (m.data.length > 8) listener.onAudio(ch, m.data, 8, m.data.length - 8);
				ack(ch, audioSessions[ch]);
				break;
			case MSG_MEDIA:
				listener.onAudio(ch, m.data, 0, m.data.length);
				ack(ch, audioSessions[ch]);
				break;
			default:
				break;
		}
	}

	private void ack(int channel, int session) throws IOException {
		transport.send(channel, false, MSG_AV_MEDIA_ACK, new Proto.Writer().varint(1, session).varint(2, 1).toByteArray());
	}

	private byte[] setupResponse() {
		// status 2 = ready, one unacknowledged piece at a time, using the first (only) configuration
		return new Proto.Writer().varint(1, 2).varint(2, 1).varint(3, 0).toByteArray();
	}

	/**
	 * Asks Android Auto to start its picture again, with fresh set-up data and a key frame,
	 * for when the picture's decoder had to be replaced
	 */
	public void refocusVideo() throws IOException {
		if (!videoFocused) return;      // the picture hasn't been set up yet: it will start by itself
		sendVideoFocus(false);
		sendVideoFocus(true);
	}

	private void sendVideoFocus(boolean focused) throws IOException {
		videoFocused = focused;
		transport.send(CH_VIDEO, false, MSG_VIDEO_FOCUS_INDICATION,
				new Proto.Writer().varint(1, focused ? 1 : 2).bool(2, false).toByteArray());
	}

	/* ---------- what i3D tells the phone it can do ---------- */

	byte[] serviceDiscoveryResponse() {
		Proto.Writer r = new Proto.Writer();

		Proto.Writer input = new Proto.Writer();
		for (int key : KEYS) input.varint(1, key);
		r.message(1, new Proto.Writer().varint(1, CH_INPUT).message(4, input));

		Proto.Writer sensors = new Proto.Writer()
				.message(1, new Proto.Writer().varint(1, SENSOR_DRIVING_STATUS))
				.message(1, new Proto.Writer().varint(1, SENSOR_NIGHT));
		r.message(1, new Proto.Writer().varint(1, CH_SENSOR).message(2, sensors));

		Proto.Writer videoConfig = new Proto.Writer()
				.varint(1, video.resolution)
				.varint(2, 1)       // 30 frames a second
				.varint(3, video.marginWidth)
				.varint(4, video.marginHeight)
				.varint(5, video.dpi);
		Proto.Writer videoChannel = new Proto.Writer()
				.varint(1, 3)       // video
				.message(4, videoConfig)
				.bool(5, true);
		r.message(1, new Proto.Writer().varint(1, CH_VIDEO).message(3, videoChannel));

		r.message(1, audioChannel(CH_MEDIA_AUDIO, 3, 48000, 2));
		r.message(1, audioChannel(CH_SPEECH_AUDIO, 1, 16000, 1));
		r.message(1, audioChannel(CH_SYSTEM_AUDIO, 2, 16000, 1));

		Proto.Writer micChannel = new Proto.Writer()
				.varint(1, 1)       // audio
				.message(2, audioConfig(16000, 1))
				.bool(3, true);
		r.message(1, new Proto.Writer().varint(1, CH_MIC).message(5, micChannel));

		r.string(2, "i3D");          // head unit name
		r.string(3, "i3");           // car model
		r.string(4, "2018");         // car year
		r.string(5, "i3D");          // car serial
		r.bool(6, false);            // right-hand drive
		r.string(7, "i3D");          // head unit maker
		r.string(8, "i3D");          // head unit model
		r.string(9, "1");            // software build
		r.string(10, "1.0");         // software version
		r.bool(11, false);           // can't play its own media while the Assistant listens
		r.bool(12, false);           // show Android Auto's clock
		return r.toByteArray();
	}

	private static Proto.Writer audioConfig(int rate, int channels) {
		return new Proto.Writer().varint(1, rate).varint(2, 16).varint(3, channels);
	}

	private static Proto.Writer audioChannel(int channel, int audioType, int rate, int channels) {
		Proto.Writer av = new Proto.Writer()
				.varint(1, 1)       // audio
				.varint(2, audioType)
				.message(3, audioConfig(rate, channels))
				.bool(5, true);
		return new Proto.Writer().varint(1, channel).message(3, av);
	}

	/* ---------- from the car to the phone ---------- */

	/** One step of the iDrive knob: positive is clockwise */
	public void rotate(int steps) throws IOException {
		Proto.Writer event = new Proto.Writer().varint(1, KEY_ROTARY).varint(2, steps);
		Proto.Writer events = new Proto.Writer().message(1, event);
		sendInput(new Proto.Writer().varint(1, System.nanoTime() / 1000).message(6, events));
	}

	/** A key press and release */
	public void key(int keyCode) throws IOException {
		keyState(keyCode, true);
		keyState(keyCode, false);
	}

	private void keyState(int keyCode, boolean down) throws IOException {
		Proto.Writer button = new Proto.Writer().varint(1, keyCode).bool(2, down).varint(3, 0).bool(4, false);
		Proto.Writer buttons = new Proto.Writer().message(1, button);
		sendInput(new Proto.Writer().varint(1, System.nanoTime() / 1000).message(4, buttons));
	}

	private void sendInput(Proto.Writer event) throws IOException {
		transport.send(CH_INPUT, false, MSG_INPUT_EVENT, event.toByteArray());
	}

	/** Day or night colours */
	public void setNight(boolean night) throws IOException {
		if (this.night == night) return;
		this.night = night;
		if (sensorsStarted) sendSensors();
	}

	/** Whether Android Auto should hold back things that aren't safe while driving */
	public void setMoving(boolean moving) throws IOException {
		int status = moving ? DRIVING_RESTRICTED : DRIVING_UNRESTRICTED;
		if (driving == status) return;
		driving = status;
		if (sensorsStarted) sendSensors();
	}

	private void sendSensors() throws IOException {
		Proto.Writer event = new Proto.Writer()
				.message(10, new Proto.Writer().bool(1, night))
				.message(13, new Proto.Writer().varint(1, driving));
		transport.send(CH_SENSOR, false, MSG_SENSOR_EVENT, event.toByteArray());
	}

	/** Sends a piece of microphone sound (16 kHz, 16 bit, mono) */
	public void sendMicrophone(byte[] pcm, int length) throws IOException {
		byte[] data = new byte[8 + length];
		long ts = System.nanoTime() / 1000;
		for (int i = 7; i >= 0; i--) { data[i] = (byte) ts; ts >>>= 8; }
		System.arraycopy(pcm, 0, data, 8, length);
		transport.send(CH_MIC, false, MSG_MEDIA_WITH_TIMESTAMP, data);
	}
}
