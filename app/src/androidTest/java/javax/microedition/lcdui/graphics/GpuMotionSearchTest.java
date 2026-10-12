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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs the GPU motion search on this device's GPU with frames whose motion is known, and compares
 * it with the CPU search ({@link FineMotionEstimator}).
 */
@RunWith(AndroidJUnit4.class)
public class GpuMotionSearchTest {
	private static final String TAG = "GpuMotionSearchTest";
	private static final int W = 240;
	private static final int H = 320;
	private static final int U = GpuMotionSearch.UNITS_PER_PIXEL;

	private EGLDisplay display;
	private EGLContext context;
	private EGLSurface surface;
	private final List<Integer> textures = new ArrayList<>();

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
		int[] ids = new int[textures.size()];
		for (int i = 0; i < ids.length; i++) {
			ids[i] = textures.get(i);
		}
		GLES20.glDeleteTextures(ids.length, ids, 0);
		EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
		EGL14.eglDestroySurface(display, surface);
		EGL14.eglDestroyContext(display, context);
	}

	/** A smooth, detailed picture, defined at any real position, plus an optional object. */
	private static double scene(double x, double y) {
		return 128 + 40 * Math.sin(x * 0.21) + 35 * Math.cos(y * 0.17)
				+ 30 * Math.sin((x + y) * 0.11) + 20 * Math.cos((x - 2 * y) * 0.07);
	}

	private static byte[] gray(int w, int h, double mx, double my) {
		byte[] out = new byte[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				out[y * w + x] = (byte) Math.max(0, Math.min(255, Math.round(scene(x - mx, y - my))));
			}
		}
		return out;
	}

	/** A grey picture as a texture, the way the generator uploads game frames. */
	private int texture(byte[] gray, int w, int h) {
		int[] argb = new int[w * h];
		for (int i = 0; i < argb.length; i++) {
			int g = gray[i] & 0xFF;
			argb[i] = 0xFF000000 | (g << 16) | (g << 8) | g;
		}
		Bitmap b = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
		int[] id = new int[1];
		GLES20.glGenTextures(1, id, 0);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
		GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
		b.recycle();
		textures.add(id[0]);
		return id[0];
	}

	/** Runs the search from {@code prev} to {@code curr}; returns the vectors (and the scene cut). */
	private int[] search(GpuMotionSearch s, byte[] prev, byte[] curr, boolean[] cut) {
		return search(s, prev, curr, cut, false);
	}

	private int[] search(GpuMotionSearch s, byte[] prev, byte[] curr, boolean[] cut, boolean usePrevious) {
		int a = texture(prev, W, H);
		int b = texture(curr, W, H);
		s.addFrame(0, a);
		s.addFrame(1, b);
		s.search(0, a, 1, b, true, usePrevious, Brightness.NONE);
		boolean c = s.readSceneCut();
		if (cut != null) {
			cut[0] = c;
		}
		return c ? null : s.readVectors(false);
	}

	private static int[] middle(int[] v, int cols, int rows) {
		List<Integer> xs = new ArrayList<>();
		List<Integer> ys = new ArrayList<>();
		for (int by = 6; by < rows - 6; by++) {
			for (int bx = 6; bx < cols - 6; bx++) {
				int i = (by * cols + bx) * 2;
				xs.add(v[i]);
				ys.add(v[i + 1]);
			}
		}
		Collections.sort(xs);
		Collections.sort(ys);
		return new int[]{xs.get(xs.size() / 2), ys.get(ys.size() / 2)};
	}

	private static float near(int[] v, int cols, int rows, int x, int y, int tolerance) {
		int good = 0, n = 0;
		for (int by = 6; by < rows - 6; by++) {
			for (int bx = 6; bx < cols - 6; bx++) {
				int i = (by * cols + bx) * 2;
				n++;
				good += Math.abs(v[i] - x) <= tolerance && Math.abs(v[i + 1] - y) <= tolerance ? 1 : 0;
			}
		}
		return (float) good / n;
	}

	@Test
	public void wholePixelMotionIsFound() {
		GpuMotionSearch s = GpuMotionSearch.create(W, H);
		assertNotNull("the GPU search could not be built on this device", s);
		int[] v = search(s, gray(W, H, 0, 0), gray(W, H, 5, -3), null);
		int[] m = middle(v, s.cols(), s.rows());
		assertEquals(5 * U, m[0], 1);
		assertEquals(-3 * U, m[1], 1);
		assertTrue(near(v, s.cols(), s.rows(), 5 * U, -3 * U, 1) > 0.9f);
		s.release();
	}

	@Test
	public void motionBetweenWholePixelsIsFoundToAboutAQuarterPixel() {
		GpuMotionSearch s = GpuMotionSearch.create(W, H);
		int[] m = middle(search(s, gray(W, H, 0, 0), gray(W, H, 2.5, 1.25), null), s.cols(), s.rows());
		assertEquals(10, m[0], 1);
		assertEquals(5, m[1], 1);
		s.release();
	}

	@Test
	public void fastMotionIsFollowed() {
		GpuMotionSearch s = GpuMotionSearch.create(W, H);
		int[] m = middle(search(s, gray(W, H, 0, 0), gray(W, H, 26, 0), null), s.cols(), s.rows());
		assertEquals(26 * U, m[0], 2);
		assertEquals(0, m[1], 2);
		s.release();
	}

	@Test
	public void aStillPictureHasNoMotion() {
		GpuMotionSearch s = GpuMotionSearch.create(W, H);
		byte[] p = gray(W, H, 0, 0);
		for (int value : search(s, p, p.clone(), null)) {
			assertEquals(0, value);
		}
		s.release();
	}

	@Test
	public void unrelatedPicturesAreASceneCut() {
		GpuMotionSearch s = GpuMotionSearch.create(W, H);
		byte[] squares = new byte[W * H];
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				squares[y * W + x] = (byte) ((x / 24 + y / 24) % 2 == 0 ? 10 : 245);
			}
		}
		boolean[] cut = new boolean[1];
		search(s, gray(W, H, 0, 0), squares, cut);
		assertTrue(cut[0]);
		for (boolean backward : new boolean[]{false, true}) {
			for (int value : s.readVectors(backward)) {
				assertEquals("no motion across a scene cut", 0, value);
			}
		}
		boolean[] noCut = new boolean[1];
		search(s, gray(W, H, 0, 0), gray(W, H, 3, 3), noCut);
		assertFalse(noCut[0]);
		s.release();
	}

	@Test
	public void theBackwardSearchIsTheForwardMotionTurnedAround() {
		GpuMotionSearch s = GpuMotionSearch.create(W, H);
		int[] f = middle(search(s, gray(W, H, 0, 0), gray(W, H, 6.5, 3), null), s.cols(), s.rows());
		int[] b = middle(s.readVectors(true), s.cols(), s.rows());
		assertEquals(-f[0], b[0], 1);
		assertEquals(-f[1], b[1], 1);
		s.release();
	}

	@Test
	public void itAgreesWithTheCpuSearch() {
		GpuMotionSearch s = GpuMotionSearch.create(W, H);
		byte[] prev = gray(W, H, 0, 0);
		byte[] curr = gray(W, H, 7.25, -4.5);
		int[] gpu = search(s, prev, curr, null);
		int[] cpu = new FineMotionEstimator(W, H).estimate(prev, curr).vectors;
		int close = 0, n = 0;
		for (int by = 4; by < s.rows() - 4; by++) {
			for (int bx = 4; bx < s.cols() - 4; bx++) {
				int i = (by * s.cols() + bx) * 2;
				n++;
				close += Math.abs(gpu[i] - cpu[i]) <= 2 && Math.abs(gpu[i + 1] - cpu[i + 1]) <= 2 ? 1 : 0;
			}
		}
		assertTrue("blocks within half a pixel of the CPU: " + close + " of " + n, close >= n * 9 / 10);
		s.release();
	}

	/**
	 * Smooth vertical waves repeating every {@code period} pixels, moved by {@code mx}: every block
	 * has detail, but the picture repeats, so several motions fit it exactly.
	 */
	private static byte[] stripes(int period, double mx) {
		byte[] out = new byte[W * H];
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				double v = 128 + 100 * Math.sin(2 * Math.PI * (x - mx) / period);
				out[y * W + x] = (byte) Math.round(v);
			}
		}
		return out;
	}

	@Test
	public void repeatingStripesKeepTheMotionTheyHad() {
		// stripes 32 pixels apart moving 24: moving 8 back fits just as well and is shorter
		GpuMotionSearch s = GpuMotionSearch.create(W, H);
		int[] alone = middle(search(s, stripes(32, 0), stripes(32, 24), null, false), s.cols(), s.rows());
		assertEquals("without the last motion the short way wins", -8 * U, alone[0], 2);
		// a picture without repeats moving 24 first, so the last motion is known
		search(s, gray(W, H, 0, 0), gray(W, H, 24, 0), null, false);
		int[] kept = middle(search(s, stripes(32, 0), stripes(32, 24), null, true), s.cols(), s.rows());
		assertEquals("with the last motion they keep it", 24 * U, kept[0], 2);
		s.release();
	}

	@Test
	public void withoutTheLastMotionNothingChanges() {
		GpuMotionSearch s = GpuMotionSearch.create(W, H);
		int[] first = search(s, gray(W, H, 0, 0), gray(W, H, 5.5, -2.25), null, false);
		int[] again = search(s, gray(W, H, 0, 0), gray(W, H, 5.5, -2.25), null, false);
		org.junit.Assert.assertArrayEquals(first, again);
		s.release();
	}

	@Test
	public void howLongASearchTakes() {
		for (int[] size : new int[][]{{240, 320}, {480, 800}}) {
			GpuMotionSearch s = GpuMotionSearch.create(size[0], size[1]);
			int a = texture(gray(size[0], size[1], 0, 0), size[0], size[1]);
			int b = texture(gray(size[0], size[1], 7, 3), size[0], size[1]);
			s.addFrame(0, a);
			s.addFrame(1, b);
			s.search(0, a, 1, b, false, false, Brightness.NONE);
			GLES20.glFinish();
			int runs = 10;
			long start = System.nanoTime();
			for (int i = 0; i < runs; i++) {
				s.search(0, a, 1, b, false, false, Brightness.NONE);
			}
			GLES20.glFinish();
			long ms = (System.nanoTime() - start) / runs / 1_000_000L;
			Log.i(TAG, "GPU motion search " + size[0] + "x" + size[1] + ": " + ms + " ms");
			s.release();
		}
	}
}
