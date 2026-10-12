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
import static org.junit.Assert.assertTrue;

import org.junit.Test;


public class FineMotionEstimatorTest {
	private static final int W = 240;
	private static final int H = 320;
	private static final int U = FineMotionEstimator.UNITS_PER_PIXEL;

	/** A smooth, detailed picture (sums of waves), defined at any real position. */
	private static double scene(double x, double y) {
		return 128 + 40 * Math.sin(x * 0.21) + 35 * Math.cos(y * 0.17)
				+ 30 * Math.sin((x + y) * 0.11) + 20 * Math.cos((x - 2 * y) * 0.07);
	}

	/** The scene as a grey image, moved by (mx, my) game pixels. */
	private static byte[] picture(double mx, double my) {
		byte[] out = new byte[W * H];
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				out[y * W + x] = (byte) Math.max(0, Math.min(255, Math.round(scene(x - mx, y - my))));
			}
		}
		return out;
	}

	/** The median vector of the blocks away from the edges (things move in or out there). */
	private static int[] middleVector(MotionEstimator.Result r) {
		java.util.List<Integer> xs = new java.util.ArrayList<>();
		java.util.List<Integer> ys = new java.util.ArrayList<>();
		for (int by = 6; by < r.rows - 6; by++) {
			for (int bx = 6; bx < r.cols - 6; bx++) {
				int i = (by * r.cols + bx) * 2;
				xs.add(r.vectors[i]);
				ys.add(r.vectors[i + 1]);
			}
		}
		java.util.Collections.sort(xs);
		java.util.Collections.sort(ys);
		return new int[]{xs.get(xs.size() / 2), ys.get(ys.size() / 2)};
	}

	private static float fractionNear(MotionEstimator.Result r, int vx, int vy, int tolerance) {
		int good = 0, n = 0;
		for (int by = 6; by < r.rows - 6; by++) {
			for (int bx = 6; bx < r.cols - 6; bx++) {
				int i = (by * r.cols + bx) * 2;
				n++;
				if (Math.abs(r.vectors[i] - vx) <= tolerance && Math.abs(r.vectors[i + 1] - vy) <= tolerance) {
					good++;
				}
			}
		}
		return (float) good / n;
	}

	@Test
	public void wholePixelMotionIsFound() {
		FineMotionEstimator e = new FineMotionEstimator(W, H);
		MotionEstimator.Result r = e.estimate(picture(0, 0), picture(5, -3));
		assertFalse(r.sceneCut);
		assertEquals(8 * 0 + 30, r.cols);
		int[] v = middleVector(r);
		assertEquals(5 * U, v[0], 1);
		assertEquals(-3 * U, v[1], 1);
		assertTrue(fractionNear(r, 5 * U, -3 * U, 1) > 0.9f);
	}

	@Test
	public void motionBetweenWholePixelsIsFoundToAboutAQuarterPixel() {
		FineMotionEstimator e = new FineMotionEstimator(W, H);
		MotionEstimator.Result r = e.estimate(picture(0, 0), picture(2.5, 1.25));
		int[] v = middleVector(r);
		assertEquals(10, v[0], 1); // 2.5 pixels
		assertEquals(5, v[1], 1); // 1.25 pixels
	}

	@Test
	public void fastMotionBeyondTheNormalSearchIsFollowed() {
		FineMotionEstimator e = new FineMotionEstimator(W, H);
		MotionEstimator.Result r = e.estimate(picture(0, 0), picture(26, 0));
		int[] v = middleVector(r);
		assertEquals(26 * U, v[0], 2);
		assertEquals(0, v[1], 2);
	}

	@Test
	public void aStillPictureHasNoMotion() {
		FineMotionEstimator e = new FineMotionEstimator(W, H);
		byte[] p = picture(0, 0);
		MotionEstimator.Result r = e.estimate(p, p.clone());
		for (int value : r.vectors) {
			assertEquals(0, value);
		}
	}

	@Test
	public void unrelatedPicturesAreASceneCut() {
		FineMotionEstimator e = new FineMotionEstimator(W, H);
		byte[] a = picture(0, 0);
		byte[] b = new byte[W * H]; // big black and white squares: nothing like the waves
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				b[y * W + x] = (byte) ((x / 24 + y / 24) % 2 == 0 ? 10 : 245);
			}
		}
		MotionEstimator.Result r = e.estimate(a, b);
		assertTrue("mean error " + r.meanError, r.sceneCut);
	}

	@Test
	public void theVectorsFitTheMotionTexture() {
		FineMotionEstimator e = new FineMotionEstimator(W, H);
		MotionEstimator.Result r = e.estimate(picture(0, 0), picture(-31, 20));
		byte[] bytes = r.encode(1);
		int[] v = middleVector(r);
		assertEquals(-31 * U, v[0], 2);
		assertEquals(r.cols * r.rows * 2, bytes.length);
	}

	@Test
	public void grayKeepsTheFullSize() {
		int[] argb = new int[W * H];
		argb[5] = 0xFFFFFFFF;
		byte[] g = FineMotionEstimator.toGray(argb, W, H);
		assertEquals(W * H, g.length);
		assertEquals(0, g[4]);
		assertTrue((g[5] & 0xFF) > 250);
	}

	@Test
	public void howLongOneEstimateTakes() {
		FineMotionEstimator e = new FineMotionEstimator(W, H);
		byte[] a = picture(0, 0);
		byte[] b = picture(7, 3);
		for (int i = 0; i < 5; i++) {
			e.estimate(a, b); // warm up
		}
		long start = System.nanoTime();
		int runs = 20;
		for (int i = 0; i < runs; i++) {
			e.estimate(a, b);
		}
		long perRun = (System.nanoTime() - start) / runs / 1_000_000L;
		System.out.println("FineMotionEstimator 240x320: " + perRun + " ms per estimate on this computer");
		assertTrue("far too slow: " + perRun + " ms", perRun < 200);
	}

	@Test
	public void blocksWherePictureCameInFromOutsideMoveWithTheRest() {
		// a pan of 20 pixels: the first two columns of blocks came in from outside the picture
		FineMotionEstimator e = new FineMotionEstimator(W, H);
		MotionEstimator.Result r = e.estimate(picture(0, 0), picture(20, 0));
		for (int by = 4; by < r.rows - 4; by++) {
			for (int bx = 0; bx < 2; bx++) {
				int i = (by * r.cols + bx) * 2;
				assertEquals("block " + bx + "," + by, 20 * U, r.vectors[i], 2);
				assertEquals(0, r.vectors[i + 1], 2);
			}
		}
	}

	@Test
	public void plainMotionFindsMotionBetweenWholePixels() {
		// the plain search on a picture of its own, moved 2.5 and 1.5 of its pixels
		int w = W / 2, h = H / 2;
		byte[] a = new byte[w * h], b = new byte[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				a[y * w + x] = (byte) Math.round(scene(x, y));
				b[y * w + x] = (byte) Math.round(scene(x - 2.5, y - 1.5));
			}
		}
		MotionEstimator.Result r = new MotionEstimator(w, h, 8, 8, true).estimate(a, b);
		int[] m = middleVector(r);
		assertEquals(10, m[0], 1);
		assertEquals(6, m[1], 1);
		MotionEstimator.Result whole = new MotionEstimator(w, h, 8, 8).estimate(a, b);
		int[] mw = middleVector(whole);
		assertTrue("whole pixels: " + mw[0], mw[0] == 2 || mw[0] == 3);
	}

	@Test
	public void onlyPoorlyMatchedEdgeBlocksTakeTheMotionFromInside() {
		int cols = 10, rows = 10;
		int[] vectors = new int[cols * rows * 2];
		int[] errors = new int[cols * rows];
		for (int b = 0; b < cols * rows; b++) {
			vectors[b * 2] = 5;
			vectors[b * 2 + 1] = 1;
		}
		// the left column came in from outside: a poor match and a wrong guess
		for (int r = 0; r < rows; r++) {
			vectors[(r * cols) * 2] = -7;
			errors[r * cols] = MotionEstimator.POOR_MATCH * 3;
		}
		// one block at the right edge moves its own way, and was found well
		vectors[(4 * cols + 9) * 2] = -3;
		MotionEstimator.fillEdges(vectors, errors, cols, rows, 2);
		for (int r = 0; r < rows; r++) {
			assertEquals("row " + r, 5, vectors[(r * cols) * 2]);
		}
		assertEquals(-3, vectors[(4 * cols + 9) * 2]);
	}

	@Test
	public void ultraFollowsMotionBeyond32Pixels() {
		MotionEstimator.Result quality = new FineMotionEstimator(W, H).estimate(picture(0, 0), picture(44.5, -6.25));
		MotionEstimator.Result ultra = new FineMotionEstimator(W, H, true).estimate(picture(0, 0), picture(44.5, -6.25));
		int[] u = middleVector(ultra);
		assertEquals(Math.round(44.5f * U), u[0], 2);
		assertEquals(Math.round(-6.25f * U), u[1], 2);
		assertTrue("Quality can not reach it", Math.abs(middleVector(quality)[0] - 44.5 * U) > 4 * U);
	}

	/** A band of a pattern repeating every 16 pixels across the middle, the rest without repeats. */
	private static byte[] fence(double mx) {
		byte[] out = new byte[W * H];
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				double v = y >= 110 && y < 210
						? 128 + 80 * Math.sin((x - mx) * 2 * Math.PI / 16)
						: scene(x - mx, y);
				out[y * W + x] = (byte) Math.max(0, Math.min(255, Math.round(v)));
			}
		}
		return out;
	}

	@Test
	public void aRepeatingBandMovesTheWayTheRestOfThePictureDoes() {
		// moving 11.25 or 4.75 back fits the band equally; the rest of the picture tells which
		MotionEstimator.Result r = new FineMotionEstimator(W, H).estimate(fence(0), fence(11.25));
		int right = 0, n = 0;
		for (int by = 15; by < 25; by++) {
			for (int bx = 4; bx < r.cols - 4; bx++) {
				n++;
				right += Math.abs(r.vectors[(by * r.cols + bx) * 2] - 45) <= 2 ? 1 : 0;
			}
		}
		assertTrue("blocks of the band moving the right way: " + right + " of " + n, right >= n * 9 / 10);
	}
}
