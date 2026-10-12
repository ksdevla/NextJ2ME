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

/**
 * How bright a picture is (its mean grey and how much it varies), and how the brightness changed
 * between two pictures. A fade, a flash or night falling changes every pixel the same way:
 * new = old * gain + offset. The motion search and the check of the moved pictures compare the old
 * picture with that change applied, or they would not recognise anything while the screen fades.
 * Pure logic, no Android.
 */
final class Brightness {
	/** A change smaller than this (fraction of gain, grey levels of offset) is no change at all. */
	private static final float SAME_GAIN = 0.03f;
	private static final float SAME_OFFSET = 3f;
	/** Below this variation (grey levels) a picture is flat: only its offset can be told. */
	private static final float FLAT = 4f;

	/** No change: gain 1, offset 0. */
	static final float[] NONE = {1f, 0f};

	private long count;
	private long sum;
	private long sumOfSquares;

	/** Starts measuring a new picture. */
	void reset() {
		count = sum = sumOfSquares = 0;
	}

	/** Adds a pixel (ARGB) of the picture. */
	void add(int argb) {
		int g = gray(argb);
		count++;
		sum += g;
		sumOfSquares += (long) g * g;
	}

	/** The mean grey (0..255). */
	float mean() {
		return count == 0 ? 0f : (float) sum / count;
	}

	/** How much the grey varies: its standard deviation (0..127). */
	float deviation() {
		if (count == 0) {
			return 0f;
		}
		double m = (double) sum / count;
		return (float) Math.sqrt(Math.max(0, (double) sumOfSquares / count - m * m));
	}

	/** The same grey as the motion search uses. */
	static int gray(int p) {
		return (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 151 + (p & 0xFF) * 28) >> 8;
	}

	/**
	 * The change from a picture of mean {@code meanFrom} and deviation {@code devFrom} to one of
	 * {@code meanTo} and {@code devTo}: {gain, offset (grey levels)}, so that to = from * gain +
	 * offset; {@link #NONE} when they hardly differ.
	 */
	static float[] change(float meanFrom, float devFrom, float meanTo, float devTo) {
		float gain = devFrom < FLAT ? 1f : Math.max(0.25f, Math.min(4f, devTo / devFrom));
		float offset = meanTo - gain * meanFrom;
		if (Math.abs(gain - 1f) < SAME_GAIN && Math.abs(offset) < SAME_OFFSET) {
			return NONE;
		}
		return new float[]{gain, offset};
	}

	/** {@code gray} with the change applied, into {@code reuse} when it has the right size. */
	static byte[] apply(byte[] gray, float[] change, byte[] reuse) {
		byte[] out = reuse != null && reuse.length == gray.length ? reuse : new byte[gray.length];
		float gain = change[0], offset = change[1];
		for (int i = 0; i < gray.length; i++) {
			int v = Math.round((gray[i] & 0xFF) * gain + offset);
			out[i] = (byte) (v < 0 ? 0 : v > 255 ? 255 : v);
		}
		return out;
	}
}
