/*
 * Copyright 2026 ksdevla
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.lcdui.graphics;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Measures how close the pictures frame generation draws between two game frames are to the true
 * picture at that moment, on this device's GPU: scenes whose every moment is known are drawn at
 * time 0 and 1, the generator draws time 0.5 (or 1.5 with low latency), and the result is compared
 * pixel by pixel with the scene drawn at that time. "Repeat" is what is shown without frame
 * generation (the nearest real frame).
 */
@RunWith(AndroidJUnit4.class)
public class FrameGenerationAccuracyTest {
	private static final String TAG = "FrameGenAccuracy";
	private static final int W = 240;
	private static final int H = 320;
	/** A pixel this far off (0..255, worst channel) counts as wrong. */
	private static final int WRONG = 40;

	private EGLDisplay display;
	private EGLContext context;
	private EGLSurface surface;

	@Before
	public void makeContext() {
		display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
		int[] version = new int[2];
		assertTrue(EGL14.eglInitialize(display, version, 0, version, 1));
		int[] attribs = {
				EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
				EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
				EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
				EGL14.EGL_ALPHA_SIZE, 8,
				EGL14.EGL_NONE};
		EGLConfig[] configs = new EGLConfig[1];
		int[] count = new int[1];
		assertTrue(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0));
		context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
				new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
		surface = EGL14.eglCreatePbufferSurface(display, configs[0],
				new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
		assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
	}

	@After
	public void dropContext() {
		EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
		EGL14.eglDestroySurface(display, surface);
		EGL14.eglDestroyContext(display, context);
	}

	// ------------------------------------------------------------------ scenes

	/** A scene: the colour of every pixel at any moment (0 = first frame, 1 = second frame). */
	interface Scene {
		int at(int x, int y, double s);
	}

	/** A detailed texture with smooth parts and hard edges, like a game's ground or wall. */
	private static int texture(double x, double y, int seed) {
		double a = 0.21 + 0.03 * seed, b = 0.17 - 0.02 * seed;
		double v = 128 + 45 * Math.sin(x * a) + 35 * Math.cos(y * b) + 25 * Math.sin((x + y) * 0.11);
		// hard-edged tiles
		v += ((Math.floor(x / 13.0) + Math.floor(y / 11.0)) % 2 == 0) ? 30 : -30;
		int r = clamp(v);
		int g = clamp(v * 0.8 + 30 * Math.sin(y * 0.09 + seed));
		int bl = clamp(255 - v * 0.7);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}

	private static int clamp(double v) {
		return (int) Math.max(0, Math.min(255, Math.round(v)));
	}

	/** The whole picture moves (a pan), with new picture coming in at the edges. */
	private static final Scene PAN = (x, y, s) -> texture(x - 7.3 * s, y - 2.6 * s, 0);
	/** A fast pan, beyond the plain Motion search range. */
	private static final Scene FAST = (x, y, s) -> texture(x - 21.5 * s, y + 3.2 * s, 0);
	/** A still background with an object moving over it (covering and uncovering it). */
	private static final Scene OBJECT = (x, y, s) -> {
		double ox = 70 + 11.5 * s, oy = 120 + 4.25 * s;
		if (x >= ox && x < ox + 64 && y >= oy && y < oy + 56) {
			return texture(x - ox, y - oy, 2);
		}
		return texture(x, y, 0);
	};
	/** Two halves moving different ways, as where a road meets the scenery. */
	private static final Scene SPLIT = (x, y, s) -> y < H / 2
			? texture(x - 6.5 * s, y, 0) : texture(x + 5.25 * s, y, 1);
	/** A pan under a still overlay (score, buttons) with sharp small detail. */
	private static final Scene HUD = (x, y, s) -> {
		if (y < 28 && x > 8 && x < W - 8) {
			boolean ink = ((x / 3) + (y / 3)) % 3 == 0 || (y > 10 && y < 14);
			return ink ? 0xFFFFFFFF : 0xFF102040;
		}
		return texture(x - 6.75 * s, y - 1.5 * s, 0);
	};

	private static int[] draw(Scene scene, double s) {
		int[] out = new int[W * H];
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				out[y * W + x] = scene.at(x, y, s);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ measuring

	/**
	 * How far {@code got} is from {@code want}: the mean error, the share of wrong pixels, and the
	 * share of pixels wrong even next to where they should be (a pixel one place off, which moving
	 * a picture by a fraction of a pixel can not avoid, does not count): real faults.
	 */
	private static double[] compare(int[] got, int[] want) {
		long sum = 0;
		int wrong = 0;
		int faults = 0;
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				int a = got[y * W + x];
				int[] c = channels(a, want[y * W + x]);
				sum += c[0] + c[1] + c[2];
				int d = Math.max(c[0], Math.max(c[1], c[2]));
				if (d > WRONG) {
					wrong++;
					int best = d;
					for (int dy = -1; dy <= 1; dy++) {
						for (int dx = -1; dx <= 1; dx++) {
							int xx = x + dx, yy = y + dy;
							if (xx >= 0 && yy >= 0 && xx < W && yy < H) {
								best = Math.min(best, difference(a, want[yy * W + xx]));
							}
						}
					}
					faults += best > WRONG ? 1 : 0;
				}
			}
		}
		return new double[]{sum / (3.0 * got.length), 100.0 * wrong / got.length,
				100.0 * faults / got.length};
	}

	private static int[] channels(int a, int b) {
		return new int[]{Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)),
				Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)), Math.abs((a & 0xFF) - (b & 0xFF))};
	}

	/** The worst channel's difference. */
	private static int difference(int a, int b) {
		int[] c = channels(a, b);
		return Math.max(c[0], Math.max(c[1], c[2]));
	}

	/** Reads a texture of the frame size back as ARGB pixels. */
	private static int[] read(int texture) {
		int[] fb = new int[1];
		GLES20.glGenFramebuffers(1, fb, 0);
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0]);
		GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
				GLES20.GL_TEXTURE_2D, texture, 0);
		ByteBuffer buf = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
		GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
		GLES20.glDeleteFramebuffers(1, fb, 0);
		int[] out = new int[W * H];
		for (int i = 0; i < out.length; i++) {
			int r = buf.get(i * 4) & 0xFF, g = buf.get(i * 4 + 1) & 0xFF, b = buf.get(i * 4 + 2) & 0xFF;
			out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
		}
		return out;
	}

	/** A frame generation setup to measure. */
	private static final class Setup {
		final String name;
		final int mode;
		final boolean gpu;
		final boolean cleanEdges;
		final boolean lowLatency;

		Setup(String name, int mode, boolean gpu, boolean cleanEdges, boolean lowLatency) {
			this.name = name;
			this.mode = mode;
			this.gpu = gpu;
			this.cleanEdges = cleanEdges;
			this.lowLatency = lowLatency;
		}
	}

	/**
	 * Has {@code setup} draw the picture at {@code s} (0.5 between the frames, 1.5 past them with low
	 * latency) from the scene's frames at 0 and 1; returns it compared with the true picture.
	 */
	private static double[] measure(Scene scene, Setup setup, double s) throws InterruptedException {
		FrameGenerator g = FrameGenerator.create(setup.mode, false, true, setup.cleanEdges, setup.gpu,
				false, setup.lowLatency, false);
		assertNotNull(g);
		Object lock = new Object();
		long t0 = System.nanoTime();
		Bitmap a = Bitmap.createBitmap(draw(scene, 0), W, H, Bitmap.Config.ARGB_8888);
		Bitmap b = Bitmap.createBitmap(draw(scene, 1), W, H, Bitmap.Config.ARGB_8888);
		g.render(a, lock, 1, t0, t0);
		waitForMotion(g);
		g.render(b, lock, 2, t0 + 50_000_000L, t0 + 50_000_000L);
		waitForMotion(g);
		int[] got = read(g.drawAt((float) s));
		g.shutdown();
		a.recycle();
		b.recycle();
		int[] want = draw(scene, s);
		dump(setup.name + " " + sceneName(scene) + " " + s, got, want);
		return compare(got, want);
	}

	private static String sceneName(Scene scene) {
		return scene == PAN ? "pan" : scene == FAST ? "fast" : scene == OBJECT ? "object"
				: scene == SPLIT ? "split" : "hud";
	}

	/**
	 * With the instrumentation argument {@code dump}, saves the picture and a map of its errors (red:
	 * wrong) into the cache of the app under test, to look at where the errors are.
	 */
	private static void dump(String name, int[] got, int[] want) {
		if (InstrumentationRegistry.getArguments().getString("dump") == null) {
			return;
		}
		int[] map = new int[got.length];
		for (int i = 0; i < got.length; i++) {
			int a = got[i], b = want[i];
			int d = Math.max(Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)),
					Math.max(Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)), Math.abs((a & 0xFF) - (b & 0xFF))));
			int grey = ((a >> 16) & 0xFF) / 3;
			map[i] = d > WRONG ? 0xFFFF0000 : 0xFF000000 | (grey << 16) | (grey << 8) | grey;
		}
		// the cache of the app under test (the test runs in its process), which is thrown away
		File dir = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
				"accuracy");
		if (!dir.isDirectory() && !dir.mkdirs()) {
			Log.w(TAG, "no folder for the pictures: " + dir);
			return;
		}
		for (Object[] img : new Object[][]{{"got", got}, {"err", map}}) {
			Bitmap bmp = Bitmap.createBitmap((int[]) img[1], W, H, Bitmap.Config.ARGB_8888);
			try (FileOutputStream out = new FileOutputStream(new File(dir, name + " " + img[0] + ".png"))) {
				bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
			} catch (IOException e) {
				Log.w(TAG, "could not save " + name, e);
			}
			bmp.recycle();
		}
	}

	private static void waitForMotion(FrameGenerator g) throws InterruptedException {
		for (int i = 0; i < 500 && !g.motionReady(); i++) {
			Thread.sleep(10);
		}
		assertTrue("the motion search did not finish", g.motionReady());
	}

	private static final Setup[] SETUPS = {
			new Setup("Blend", FrameGenerator.MODE_BLEND, false, false, false),
			new Setup("Motion", FrameGenerator.MODE_MOTION, false, false, false),
			new Setup("Motion HQ", FrameGenerator.MODE_MOTION_HQ, false, false, false),
			new Setup("HQ clean edges", FrameGenerator.MODE_MOTION_HQ, false, true, false),
			new Setup("HQ GPU search", FrameGenerator.MODE_MOTION_HQ, true, false, false),
	};

	@Test
	public void accuracyOfEveryModeOnEveryScene() throws InterruptedException {
		Object[][] scenes = {{"pan", PAN}, {"fast pan", FAST}, {"object", OBJECT}, {"split", SPLIT},
				{"pan under HUD", HUD}};
		double[] totals = new double[SETUPS.length];
		StringBuilder table = new StringBuilder("\nwrong pixels / real faults at t = 0.5\n");
		table.append(String.format(Locale.ROOT, "%-15s %-15s", "", "Repeat"));
		for (Setup setup : SETUPS) {
			table.append(String.format(Locale.ROOT, " %-15s", setup.name));
		}
		table.append('\n');
		for (Object[] row : scenes) {
			Scene scene = (Scene) row[1];
			double[] repeat = compare(draw(scene, 1), draw(scene, 0.5));
			table.append(String.format(Locale.ROOT, "%-15s %5.2f%% %5.2f%%  ", row[0], repeat[1], repeat[2]));
			for (int i = 0; i < SETUPS.length; i++) {
				double[] m = measure(scene, SETUPS[i], 0.5);
				totals[i] += m[1];
				table.append(String.format(Locale.ROOT, " %5.2f%% %5.2f%%  ", m[1], m[2]));
			}
			table.append('\n');
		}
		Log.i(TAG, table.toString());
		// following the motion must beat cross-fading
		assertTrue(totals[2] < totals[0]);
	}

	@Test
	public void accuracyOfLowLatency() throws InterruptedException {
		Object[][] scenes = {{"pan", PAN}, {"fast pan", FAST}, {"object", OBJECT}, {"split", SPLIT},
				{"pan under HUD", HUD}};
		Setup[] setups = {
				new Setup("HQ low latency", FrameGenerator.MODE_MOTION_HQ, false, false, true),
				new Setup("GPU low latency", FrameGenerator.MODE_MOTION_HQ, true, false, true),
		};
		StringBuilder table = new StringBuilder("\nwrong pixels / real faults at t = 1.5 (low latency)\n");
		for (Object[] row : scenes) {
			Scene scene = (Scene) row[1];
			double[] repeat = compare(draw(scene, 1), draw(scene, 1.5));
			table.append(String.format(Locale.ROOT, "%-15s repeat %5.2f%% %5.2f%%  ", row[0], repeat[1], repeat[2]));
			for (Setup setup : setups) {
				double[] m = measure(scene, setup, 1.5);
				table.append(String.format(Locale.ROOT, " %s %5.2f%% %5.2f%%  ", setup.name, m[1], m[2]));
			}
			table.append('\n');
		}
		Log.i(TAG, table.toString());
	}
}
