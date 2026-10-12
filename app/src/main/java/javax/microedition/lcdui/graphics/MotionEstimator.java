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

import java.util.Arrays;
import java.util.concurrent.ExecutorService;

/**
 * Finds how the picture moved between two game frames, block by block, for frame generation.
 * <p>
 * The pictures are small grey images (the game frame at half size). Every block of the new frame
 * is searched for in the previous frame within a small range; the result is a motion vector per
 * block. Plain Java so it can be tested without a screen.
 */
public final class MotionEstimator {
	/** Mean difference per pixel (0..255) above which two frames are treated as a scene cut. */
	static final float SCENE_CUT_ERROR = 24f;
	/** A block moves only if that makes it at least this much (fraction) better than staying put. */
	private static final float GAIN = 0.90f;
	/** Cost added per pixel of displacement, so that ties go to the smaller motion. */
	private static final int PENALTY_PER_PIXEL = 4;

	/** The motion of every block of a frame. */
	public static final class Result {
		public final int cols;
		public final int rows;
		/** For each block {@code dx, dy}: how far the picture moved from the previous to the new frame. */
		public final int[] vectors;
		/** True if the frames have nothing in common (a different scene): do not interpolate. */
		public final boolean sceneCut;
		/** Mean difference per pixel left after the motion was applied. */
		public final float meanError;

		Result(int cols, int rows, int[] vectors, boolean sceneCut, float meanError) {
			this.cols = cols;
			this.rows = rows;
			this.vectors = vectors;
			this.sceneCut = sceneCut;
			this.meanError = meanError;
		}

		/**
		 * The vectors as bytes for a two-channel texture: the {@link MotionCode} of
		 * {@code dx * scale} and {@code dy * scale} (quarter pixels) plus 128, so the shader can read
		 * them back.
		 */
		public byte[] encode(int scale) {
			return encode(scale, MotionCode.EXACT);
		}

		/** Like {@link #encode(int)}, coded with {@code linear} ({@link MotionCode}). */
		public byte[] encode(int scale, int linear) {
			byte[] out = new byte[cols * rows * 2];
			for (int i = 0; i < vectors.length; i++) {
				out[i] = (byte) (MotionCode.encode(vectors[i] * scale, linear) + 128);
			}
			return out;
		}

		/** The largest component of any vector, either way. */
		public int largest() {
			int max = 0;
			for (int v : vectors) {
				max = Math.max(max, Math.abs(v));
			}
			return max;
		}
	}

	private final int width;
	private final int height;
	private final int block;
	private final int range;
	private final int cols;
	private final int rows;
	/** Vectors in quarter pixels, found between whole pixels with a parabola; else whole pixels. */
	private final boolean subPixel;

	/** Units of a vector per pixel with sub-pixel motion. */
	static final int SUB_UNITS = 4;

	/**
	 * @param width  width of the grey images
	 * @param height height of the grey images
	 * @param block  side of a block in pixels (8 is a good value)
	 * @param range  how far a block may move in either direction, in pixels
	 */
	public MotionEstimator(int width, int height, int block, int range) {
		this(width, height, block, range, false);
	}

	/**
	 * @param subPixel give the vectors in quarter pixels ({@link #SUB_UNITS} per pixel), the motion
	 *                 between whole pixels found with a parabola through the neighbouring matches;
	 *                 otherwise in whole pixels
	 */
	public MotionEstimator(int width, int height, int block, int range, boolean subPixel) {
		if (width < block || height < block || block < 2 || range < 1) {
			throw new IllegalArgumentException("image too small for the block size");
		}
		this.width = width;
		this.height = height;
		this.block = block;
		this.range = range;
		this.cols = (width + block - 1) / block;
		this.rows = (height + block - 1) / block;
		this.subPixel = subPixel;
	}

	public int cols() {
		return cols;
	}

	public int rows() {
		return rows;
	}

	/** Grey image at half size (2x2 pixels averaged) from ARGB pixels. */
	public static byte[] toGray(int[] argb, int width, int height) {
		return toGray(argb, width, height, null);
	}

	/** Like {@link #toGray(int[], int, int)}, into {@code reuse} when it has the right size. */
	public static byte[] toGray(int[] argb, int width, int height, byte[] reuse) {
		int gw = width / 2;
		int gh = height / 2;
		byte[] out = reuse != null && reuse.length == gw * gh ? reuse : new byte[gw * gh];
		for (int y = 0; y < gh; y++) {
			int row0 = (2 * y) * width;
			int row1 = row0 + width;
			for (int x = 0; x < gw; x++) {
				int i = 2 * x;
				int sum = gray(argb[row0 + i]) + gray(argb[row0 + i + 1])
						+ gray(argb[row1 + i]) + gray(argb[row1 + i + 1]);
				out[y * gw + x] = (byte) (sum >> 2);
			}
		}
		return out;
	}

	private static int gray(int p) {
		return (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 151 + (p & 0xFF) * 28) >> 8;
	}

	/** The motion from {@code prev} to {@code curr}; both are {@code width x height} grey images. */
	public Result estimate(byte[] prev, byte[] curr) {
		return estimate(prev, curr, null, 1);
	}

	/**
	 * Like {@link #estimate(byte[], byte[])}, with the rows of blocks shared out between this thread
	 * and {@code helpers} in up to {@code parts} bands. The result is the same.
	 */
	public Result estimate(byte[] prev, byte[] curr, ExecutorService helpers, int parts) {
		return estimate(prev, curr, helpers, parts, null);
	}

	/**
	 * Like {@link #estimate(byte[], byte[], ExecutorService, int)}, with the motion found into the
	 * previous frame ({@code previous}, the same blocks, or null) as an extra guess for each block:
	 * things usually keep moving the same way. A block takes it if it fits about as well as staying
	 * put, and among motions that fit equally it prefers the one closest to it, so the motion does
	 * not jump between frames on flat or repeating pictures; it is also tried when it lies outside
	 * the search range. Where it no longer fits (something turned or stopped) it loses to the search.
	 */
	public Result estimate(byte[] prev, byte[] curr, ExecutorService helpers, int parts, int[] previous) {
		if (previous != null && previous.length != cols * rows * 2) {
			previous = null; // a different size: of no use
		}
		final int[] guesses = previous;
		int[] vectors = new int[cols * rows * 2];
		int[] errors = new int[cols * rows];
		long[] bestPerRow = new long[rows];
		long[] zeroPerRow = new long[rows];
		RowBands.run(rows, helpers, parts,
				(from, to) -> search(prev, curr, from, to, vectors, errors, bestPerRow, zeroPerRow, guesses));
		long totalBest = 0;
		long totalZero = 0;
		for (int by = 0; by < rows; by++) {
			totalBest += bestPerRow[by];
			totalZero += zeroPerRow[by];
		}
		float pixels = (float) width * height;
		float meanError = totalBest / pixels;
		boolean sceneCut = meanError > SCENE_CUT_ERROR;
		if (totalZero / pixels < 1.0f) {
			Arrays.fill(vectors, 0); // nothing moved
		} else {
			// picture coming in at the edges moves at most the search range in
			fillEdges(vectors, errors, cols, rows, (range + block - 1) / block);
			median(vectors);
		}
		return new Result(cols, rows, vectors, sceneCut, meanError);
	}

	/** The best match of one block so far, and the guess from the previous frame. */
	private static final class Match {
		int zero;
		int limit;
		int bestCost;
		int best;
		int bestDx;
		int bestDy;
		boolean steady;
		int guessDx;
		int guessDy;

		void reset(int zero, int[] previous, int i, int units) {
			this.zero = zero;
			this.limit = (int) (zero * GAIN);
			bestCost = zero;
			best = zero;
			bestDx = 0;
			bestDy = 0;
			// the search looks for where a block came from: the opposite of its motion
			guessDx = previous == null ? 0 : -Math.round(previous[i] / (float) units);
			guessDy = previous == null ? 0 : -Math.round(previous[i + 1] / (float) units);
			steady = guessDx != 0 || guessDy != 0;
		}

		/** Whether (dx, dy) is the guess or right next to it. */
		boolean nearGuess(int dx, int dy) {
			return steady && Math.abs(dx - guessDx) + Math.abs(dy - guessDy) <= 1;
		}

		/** The displacement that costs: from staying put, or from the guess if that is closer. */
		int distance(int dx, int dy) {
			int d = Math.abs(dx) + Math.abs(dy);
			return steady ? Math.min(d, Math.abs(dx - guessDx) + Math.abs(dy - guessDy)) : d;
		}
	}

	private void consider(Match m, byte[] prev, byte[] curr, int x0, int y0, int x1, int y1, int dx, int dy) {
		int penalty = PENALTY_PER_PIXEL * m.distance(dx, dy);
		// a move must beat staying put by 10%; keeping the motion of the previous frame only has to
		// tie with it
		int limit = m.nearGuess(dx, dy) ? m.zero + 1 : m.limit;
		int cap = Math.min(m.bestCost, limit) - penalty;
		if (cap <= 0) {
			return;
		}
		int s = sad(prev, curr, x0, y0, x1, y1, dx, dy, cap);
		if (s < cap && s + penalty < m.bestCost) {
			m.bestCost = s + penalty;
			m.best = s;
			m.bestDx = dx;
			m.bestDy = dy;
		}
	}

	/** Searches the blocks of rows {@code fromRow} to {@code toRow}; touches only those rows' entries. */
	private void search(byte[] prev, byte[] curr, int fromRow, int toRow, int[] vectors, int[] errors,
						long[] bestPerRow, long[] zeroPerRow, int[] previous) {
		Match m = new Match();
		for (int by = fromRow; by < toRow; by++) {
			long rowBest = 0;
			long rowZero = 0;
			for (int bx = 0; bx < cols; bx++) {
				int x0 = bx * block;
				int y0 = by * block;
				int x1 = Math.min(x0 + block, width);
				int y1 = Math.min(y0 + block, height);
				int zero = sad(prev, curr, x0, y0, x1, y1, 0, 0, Integer.MAX_VALUE);
				int i = (by * cols + bx) * 2;
				m.reset(zero, previous, i, subPixel ? SUB_UNITS : 1);
				if (zero > 0) {
					int minDx = Math.max(-range, -x0);
					int maxDx = Math.min(range, width - x1);
					int minDy = Math.max(-range, -y0);
					int maxDy = Math.min(range, height - y1);
					for (int dy = minDy; dy <= maxDy; dy++) {
						for (int dx = minDx; dx <= maxDx; dx++) {
							if (dx != 0 || dy != 0) {
								consider(m, prev, curr, x0, y0, x1, y1, dx, dy);
							}
						}
					}
					if (m.steady) {
						// the guess and the places around it that the range did not cover
						for (int dy = m.guessDy - 1; dy <= m.guessDy + 1; dy++) {
							for (int dx = m.guessDx - 1; dx <= m.guessDx + 1; dx++) {
								boolean searched = dx >= minDx && dx <= maxDx && dy >= minDy && dy <= maxDy;
								boolean inside = x0 + dx >= 0 && x1 + dx <= width && y0 + dy >= 0 && y1 + dy <= height;
								if (!searched && inside && (dx != 0 || dy != 0)) {
									consider(m, prev, curr, x0, y0, x1, y1, dx, dy);
								}
							}
						}
					}
				}
				rowBest += m.best;
				rowZero += zero;
				errors[by * cols + bx] = errorOf(m.best, (x1 - x0) * (y1 - y0));
				// curr(x) matches prev(x + d), so the picture moved by -d
				if (subPixel) {
					// between whole pixels: the lowest point of a parabola through the neighbours
					float fx = subPixel(prev, curr, x0, y0, x1, y1, m.bestDx, m.bestDy, m.best, 1, 0);
					float fy = subPixel(prev, curr, x0, y0, x1, y1, m.bestDx, m.bestDy, m.best, 0, 1);
					vectors[i] = -Math.round((m.bestDx + fx) * SUB_UNITS);
					vectors[i + 1] = -Math.round((m.bestDy + fy) * SUB_UNITS);
				} else {
					vectors[i] = -m.bestDx;
					vectors[i + 1] = -m.bestDy;
				}
			}
			bestPerRow[by] = rowBest;
			zeroPerRow[by] = rowZero;
		}
	}

	/** Offset (-0.5 to 0.5) of the true best match from the whole pixel (dx, dy), along (ux, uy). */
	private float subPixel(byte[] prev, byte[] curr, int x0, int y0, int x1, int y1,
						   int dx, int dy, int s0, int ux, int uy) {
		if (s0 == 0) {
			return 0f; // an exact match
		}
		int ax = dx - ux, ay = dy - uy, bx = dx + ux, by = dy + uy;
		if (x0 + Math.min(ax, bx) < 0 || x1 + Math.max(ax, bx) > width
				|| y0 + Math.min(ay, by) < 0 || y1 + Math.max(ay, by) > height) {
			return 0f;
		}
		int sa = sad(prev, curr, x0, y0, x1, y1, ax, ay, Integer.MAX_VALUE);
		int sb = sad(prev, curr, x0, y0, x1, y1, bx, by, Integer.MAX_VALUE);
		int curve = sa - 2 * s0 + sb;
		if (curve <= 0) {
			return 0f;
		}
		float f = (float) (sa - sb) / (2 * curve);
		return Math.max(-0.5f, Math.min(0.5f, f));
	}

	/**
	 * Sum of absolute differences between the block of {@code curr} and the block of {@code prev}
	 * shifted by (dx, dy); stops early once it reaches {@code cap}.
	 */
	private int sad(byte[] prev, byte[] curr, int x0, int y0, int x1, int y1, int dx, int dy, int cap) {
		int sum = 0;
		for (int y = y0; y < y1; y++) {
			int c = y * width + x0;
			int p = (y + dy) * width + x0 + dx;
			for (int x = x0; x < x1; x++, c++, p++) {
				int d = (curr[c] & 0xFF) - (prev[p] & 0xFF);
				sum += d < 0 ? -d : d;
			}
			if (sum >= cap) {
				return sum;
			}
		}
		return sum;
	}

	/**
	 * Blocks near the edges of the picture whose picture came in from outside it can not be found
	 * in the previous frame, so their match is poor and their motion is a guess. Up to
	 * {@code depth} blocks from an edge, such a block takes the motion of the first block inside
	 * that depth (in a pan the whole picture moves together); a block that matched well keeps its
	 * own motion.
	 *
	 * @param error the match error of each block, {@link #errorOf} of its sum of differences
	 */
	static void fillEdges(int[] vectors, int[] error, int cols, int rows, int depth) {
		if (cols <= 2 * depth || rows <= 2 * depth) {
			return; // too small to have an inside
		}
		int[] src = vectors.clone();
		for (int r = 0; r < rows; r++) {
			int tr = r < depth ? depth : r >= rows - depth ? rows - 1 - depth : r;
			for (int c = 0; c < cols; c++) {
				int tc = c < depth ? depth : c >= cols - depth ? cols - 1 - depth : c;
				if (tc == c && tr == r) {
					continue;
				}
				int b = r * cols + c;
				int t = tr * cols + tc;
				if (error[b] > POOR_MATCH && error[b] > 2 * error[t]) {
					vectors[b * 2] = src[t * 2];
					vectors[b * 2 + 1] = src[t * 2 + 1];
				}
			}
		}
	}

	/** The match error of a block: its mean difference per pixel, in sixteenths (0..4080). */
	static int errorOf(int sad, int pixels) {
		return pixels == 0 ? 0 : sad * 16 / pixels;
	}

	/** A match error ({@link #errorOf}) above which a block's picture was not really found. */
	static final int POOR_MATCH = 10 * 16;

	/** 3x3 median of each component, so a single wrong block does not tear the picture. */
	private void median(int[] vectors) {
		int[] src = vectors.clone();
		int[] window = new int[9];
		for (int by = 0; by < rows; by++) {
			for (int bx = 0; bx < cols; bx++) {
				for (int c = 0; c < 2; c++) {
					int n = 0;
					for (int oy = -1; oy <= 1; oy++) {
						for (int ox = -1; ox <= 1; ox++) {
							int x = bx + ox;
							int y = by + oy;
							if (x >= 0 && x < cols && y >= 0 && y < rows) {
								window[n++] = src[(y * cols + x) * 2 + c];
							}
						}
					}
					Arrays.sort(window, 0, n);
					vectors[(by * cols + bx) * 2 + c] = window[n / 2];
				}
			}
		}
	}
}
