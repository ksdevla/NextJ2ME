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
 * The high-quality motion search of frame generation: finer, faster-moving and more precise than
 * {@link MotionEstimator} alone, for more CPU time.
 * <ol>
 * <li>A coarse search on the frames at a quarter of their size finds motion of up to
 * {@code 4 * COARSE_RANGE} game pixels per frame.</li>
 * <li>Every {@value #BLOCK}x{@value #BLOCK} block of the full-size frame then refines the coarse
 * motion of its own and its neighbouring coarse blocks to the exact pixel.</li>
 * <li>A parabola through the match quality around the best pixel gives the motion to a quarter
 * of a pixel, so slow movement does not wobble between whole pixels.</li>
 * </ol>
 * Vectors are in quarter pixels ({@link #UNITS_PER_PIXEL} per game pixel). Plain Java so it can be
 * tested without a screen.
 */
public final class FineMotionEstimator {
	/** Side of a refined block, in game pixels. */
	static final int BLOCK = 8;
	/** Vector units per game pixel. */
	static final int UNITS_PER_PIXEL = 4;
	/** Coarse search range, in quarter-size pixels. */
	private static final int COARSE_RANGE = 8;
	private static final int COARSE_BLOCK = 4;
	/** How far the refinement looks around each coarse guess, in game pixels. */
	private static final int REFINE = 2;
	/** A block moves only if that makes it at least this much (fraction) better than staying put. */
	private static final float GAIN = 0.90f;
	/** How many blocks from the edges picture coming in can reach: the most it moves (32 pixels). */
	private static final int EDGE_DEPTH = 4 * COARSE_RANGE / BLOCK;
	/** Cost added per pixel of refinement away from a guess, so that ties go to the guess. */
	private static final int PENALTY_PER_PIXEL = 2;

	private final int width;
	private final int height;
	private final int cols;
	private final int rows;
	private final int quarterWidth;
	private final int quarterHeight;
	private final MotionEstimator coarse;

	/**
	 * @param width  width of the full-size grey images (game pixels)
	 * @param height height of the full-size grey images
	 */
	public FineMotionEstimator(int width, int height) {
		if (width < 4 * BLOCK || height < 4 * BLOCK) {
			throw new IllegalArgumentException("image too small");
		}
		this.width = width;
		this.height = height;
		this.cols = (width + BLOCK - 1) / BLOCK;
		this.rows = (height + BLOCK - 1) / BLOCK;
		this.quarterWidth = width / 4;
		this.quarterHeight = height / 4;
		this.coarse = new MotionEstimator(quarterWidth, quarterHeight, COARSE_BLOCK, COARSE_RANGE);
	}

	public int cols() {
		return cols;
	}

	public int rows() {
		return rows;
	}

	/** Full-size grey image from ARGB pixels. */
	public static byte[] toGray(int[] argb, int width, int height) {
		return toGray(argb, width, height, null);
	}

	/** Like {@link #toGray(int[], int, int)}, into {@code reuse} when it has the right size. */
	public static byte[] toGray(int[] argb, int width, int height, byte[] reuse) {
		byte[] out = reuse != null && reuse.length == width * height ? reuse : new byte[width * height];
		for (int i = 0; i < out.length; i++) {
			int p = argb[i];
			out[i] = (byte) ((((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 151 + (p & 0xFF) * 28) >> 8);
		}
		return out;
	}

	/** The motion from {@code prev} to {@code curr}, both full-size grey images, in quarter pixels. */
	public MotionEstimator.Result estimate(byte[] prev, byte[] curr) {
		return estimate(prev, curr, null, 1);
	}

	/**
	 * Like {@link #estimate(byte[], byte[])}, with the rows of blocks of both searches shared out
	 * between this thread and {@code helpers} in up to {@code parts} bands. The result is the same.
	 */
	public MotionEstimator.Result estimate(byte[] prev, byte[] curr, ExecutorService helpers, int parts) {
		return estimate(prev, curr, helpers, parts, null);
	}

	/**
	 * Like {@link #estimate(byte[], byte[], ExecutorService, int)}, with the motion found into the
	 * previous frame ({@code previous}, the same blocks in quarter pixels, or null) as one more guess
	 * to refine for each block: things usually keep moving the same way, which keeps the motion from
	 * jumping between frames and follows steady motion even beyond the coarse search. A block that
	 * keeps that motion only has to fit as well as staying put, not 10% better.
	 */
	public MotionEstimator.Result estimate(byte[] prev, byte[] curr, ExecutorService helpers, int parts,
										   int[] previous) {
		final int[] last = previous != null && previous.length == cols * rows * 2 ? previous : null;
		MotionEstimator.Result rough = coarse.estimate(quarter(prev), quarter(curr), helpers, parts);
		int[] vectors = new int[cols * rows * 2];
		if (rough.sceneCut) {
			return new MotionEstimator.Result(cols, rows, vectors, true, rough.meanError);
		}
		int[] errors = new int[cols * rows];
		RowBands.run(rows, helpers, parts,
				(from, to) -> refine(prev, curr, rough, last, from, to, vectors, errors));
		MotionEstimator.fillEdges(vectors, errors, cols, rows, EDGE_DEPTH);
		median(vectors);
		return new MotionEstimator.Result(cols, rows, vectors, false, rough.meanError);
	}

	/** Refines the blocks of rows {@code fromRow} to {@code toRow}; touches only those rows' entries. */
	private void refine(byte[] prev, byte[] curr, MotionEstimator.Result rough, int[] previous,
						int fromRow, int toRow, int[] vectors, int[] errors) {
		int[] guessX = new int[7];
		int[] guessY = new int[7];
		for (int by = fromRow; by < toRow; by++) {
			for (int bx = 0; bx < cols; bx++) {
				int x0 = bx * BLOCK;
				int y0 = by * BLOCK;
				int x1 = Math.min(x0 + BLOCK, width);
				int y1 = Math.min(y0 + BLOCK, height);
				int zero = sad(prev, curr, x0, y0, x1, y1, 0, 0, Integer.MAX_VALUE);
				int pixels = (x1 - x0) * (y1 - y0);
				errors[by * cols + bx] = MotionEstimator.errorOf(zero, pixels);
				if (zero == 0) {
					continue; // nothing changed here: it stays put
				}
				// the guesses: no motion, and the coarse motion of this place and the places around it
				// (a block at the edge of an object may belong to the neighbour's motion)
				int guesses = 0;
				guessX[guesses] = 0;
				guessY[guesses++] = 0;
				// the motion this place had in the previous frame (in whole pixels; refined below)
				int lastX = 0;
				int lastY = 0;
				boolean steady = false;
				if (previous != null) {
					int p = (by * cols + bx) * 2;
					lastX = -Math.round(previous[p] / (float) UNITS_PER_PIXEL);
					lastY = -Math.round(previous[p + 1] / (float) UNITS_PER_PIXEL);
					steady = lastX != 0 || lastY != 0;
					if (steady) {
						guessX[guesses] = lastX;
						guessY[guesses++] = lastY;
					}
				}
				int cx = Math.min((x0 + BLOCK / 2) / (4 * COARSE_BLOCK), rough.cols - 1);
				int cy = Math.min((y0 + BLOCK / 2) / (4 * COARSE_BLOCK), rough.rows - 1);
				for (int k = 0; k < 5; k++) {
					int nx = cx + (k == 1 ? -1 : k == 2 ? 1 : 0);
					int ny = cy + (k == 3 ? -1 : k == 4 ? 1 : 0);
					if (nx < 0 || ny < 0 || nx >= rough.cols || ny >= rough.rows) {
						continue;
					}
					int i = (ny * rough.cols + nx) * 2;
					// a vector is the motion; the search looks for where the block came from: -motion
					int gx = -rough.vectors[i] * 4;
					int gy = -rough.vectors[i + 1] * 4;
					boolean seen = false;
					for (int g = 0; g < guesses; g++) {
						seen |= guessX[g] == gx && guessY[g] == gy;
					}
					if (!seen) {
						guessX[guesses] = gx;
						guessY[guesses++] = gy;
					}
				}
				int bestCost = Integer.MAX_VALUE;
				int bestSad = zero;
				int bestDx = 0;
				int bestDy = 0;
				for (int g = 0; g < guesses; g++) {
					for (int dy = guessY[g] - REFINE; dy <= guessY[g] + REFINE; dy++) {
						if (y0 + dy < 0 || y1 + dy > height) {
							continue;
						}
						for (int dx = guessX[g] - REFINE; dx <= guessX[g] + REFINE; dx++) {
							if (x0 + dx < 0 || x1 + dx > width) {
								continue;
							}
							int penalty = PENALTY_PER_PIXEL * (Math.abs(dx - guessX[g]) + Math.abs(dy - guessY[g]));
							if (penalty >= bestCost) {
								continue;
							}
							int s = sad(prev, curr, x0, y0, x1, y1, dx, dy, bestCost - penalty);
							if (s + penalty < bestCost) {
								bestCost = s + penalty;
								bestSad = s;
								bestDx = dx;
								bestDy = dy;
							}
						}
					}
				}
				boolean kept = steady && Math.abs(bestDx - lastX) <= REFINE && Math.abs(bestDy - lastY) <= REFINE;
				if ((bestDx != 0 || bestDy != 0) && bestSad > zero * GAIN && !(kept && bestSad <= zero)) {
					continue; // moving is not clearly better than staying put
				}
				errors[by * cols + bx] = MotionEstimator.errorOf(bestSad, pixels);
				// to a quarter pixel: the lowest point of a parabola through the neighbouring costs
				float fx = subPixel(prev, curr, x0, y0, x1, y1, bestDx, bestDy, bestSad, 1, 0);
				float fy = subPixel(prev, curr, x0, y0, x1, y1, bestDx, bestDy, bestSad, 0, 1);
				int i = (by * cols + bx) * 2;
				vectors[i] = -Math.round((bestDx + fx) * UNITS_PER_PIXEL);
				vectors[i + 1] = -Math.round((bestDy + fy) * UNITS_PER_PIXEL);
			}
		}
	}

	/** Offset (-0.5 to 0.5) of the true best match from the whole pixel (dx, dy), along (ux, uy). */
	private float subPixel(byte[] prev, byte[] curr, int x0, int y0, int x1, int y1,
						   int dx, int dy, int s0, int ux, int uy) {
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

	/** The image at a quarter of its size, every 4x4 pixels averaged. */
	private byte[] quarter(byte[] full) {
		byte[] out = new byte[quarterWidth * quarterHeight];
		for (int y = 0; y < quarterHeight; y++) {
			for (int x = 0; x < quarterWidth; x++) {
				int sum = 0;
				for (int yy = 0; yy < 4; yy++) {
					int row = (4 * y + yy) * width + 4 * x;
					sum += (full[row] & 0xFF) + (full[row + 1] & 0xFF) + (full[row + 2] & 0xFF)
							+ (full[row + 3] & 0xFF);
				}
				out[y * quarterWidth + x] = (byte) (sum >> 4);
			}
		}
		return out;
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
