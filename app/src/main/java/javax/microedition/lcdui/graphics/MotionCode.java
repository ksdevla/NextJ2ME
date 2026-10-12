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
 * How a motion (in quarter pixels) is kept in one byte of a motion texture. The code is
 * -127..127, stored plus 128, and keeps the order of the motions, so a median of codes is the code
 * of the median. Motions up to {@code linear} quarter pixels are kept exactly, larger ones in steps
 * of {@link #STEP}:
 * <ul>
 * <li>{@link #EXACT}: exactly, up to 31.75 pixels (the reach of the Quality search);</li>
 * <li>{@link #WIDE}: exactly up to 24 pixels, where precision shows, then in steps of 1.5 pixels
 * up to 70.5 pixels, so the very fast motion the Ultra search follows fits too.</li>
 * </ul>
 * The shaders use {@link #GLSL}, with {@code linear} as an argument.
 */
final class MotionCode {
	/** Every motion kept exactly, up to 127 quarter pixels. */
	static final int EXACT = 127;
	/** Kept exactly up to 96 quarter pixels (24 pixels), beyond in steps of {@link #STEP}. */
	static final int WIDE = 96;
	/** Beyond the exact part, each code step is this many quarter pixels. */
	static final int STEP = 6;
	static final int MAX = 127;

	private MotionCode() {
	}

	/** The largest motion (quarter pixels) that can be kept with {@code linear}. */
	static int reach(int linear) {
		return linear + (MAX - linear) * STEP;
	}

	/** The code (-127..127) of a motion in quarter pixels. */
	static int encode(int units, int linear) {
		int a = Math.abs(units);
		int c = a <= linear ? a : Math.min(MAX, linear + Math.round((a - linear) / (float) STEP));
		return units < 0 ? -c : c;
	}

	/** The motion in quarter pixels of a code (-127..127). */
	static int decode(int code, int linear) {
		int a = Math.abs(code);
		int u = a <= linear ? a : linear + (a - linear) * STEP;
		return code < 0 ? -u : u;
	}

	/** {@link #encode} and {@link #decode} for the shaders, on codes and motions as floats. */
	static final String GLSL =
			"float encodeMotion(float v, float linear) {\n"
					+ "    float a = abs(v);\n"
					+ "    float c = a <= linear ? a : linear + floor((a - linear) / " + STEP + ".0 + 0.5);\n"
					+ "    return sign(v) * min(c, " + MAX + ".0);\n"
					+ "}\n"
					+ "float decodeMotion(float c, float linear) {\n"
					+ "    float a = abs(c);\n"
					+ "    return sign(c) * (a <= linear + 0.5 ? a : linear + (a - linear) * " + STEP + ".0);\n"
					+ "}\n";
}
