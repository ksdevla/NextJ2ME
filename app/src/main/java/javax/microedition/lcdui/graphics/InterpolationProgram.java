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

import static android.opengl.GLES20.GL_COMPILE_STATUS;
import static android.opengl.GLES20.GL_FRAGMENT_SHADER;
import static android.opengl.GLES20.GL_LINK_STATUS;
import static android.opengl.GLES20.GL_VERTEX_SHADER;
import static android.opengl.GLES20.glAttachShader;
import static android.opengl.GLES20.glCompileShader;
import static android.opengl.GLES20.glCreateProgram;
import static android.opengl.GLES20.glCreateShader;
import static android.opengl.GLES20.glDeleteProgram;
import static android.opengl.GLES20.glDeleteShader;
import static android.opengl.GLES20.glGetAttribLocation;
import static android.opengl.GLES20.glGetProgramInfoLog;
import static android.opengl.GLES20.glGetProgramiv;
import static android.opengl.GLES20.glGetShaderInfoLog;
import static android.opengl.GLES20.glGetShaderiv;
import static android.opengl.GLES20.glGetUniformLocation;
import static android.opengl.GLES20.glLinkProgram;
import static android.opengl.GLES20.glShaderSource;

import android.util.Log;

/**
 * The shader that draws a picture between two game frames (see {@link FrameGenerator}): it
 * samples the previous and the newest frame, moves each part of them along the motion found
 * between the frames and blends the two.
 */
final class InterpolationProgram {
	private static final String TAG = InterpolationProgram.class.getName();

	private static final String VERTEX =
			"attribute vec4 a_position;\n"
					+ "attribute vec2 a_uv;\n"
					+ "varying vec2 v_uv;\n"
					+ "void main() {\n"
					+ "    gl_Position = a_position;\n"
					+ "    v_uv = a_uv;\n"
					+ "}\n";

	/**
	 * The motion texture has one texel per block and two 8-bit channels (luminance and alpha) that
	 * hold the {@link MotionCode} of the motion plus 128, in {@code u_motionUnit} steps per frame
	 * pixel. A vector (vx, vy) means: what is at p in the
	 * previous frame is at p + v in the newest frame. At blend position t, the previous frame is
	 * sampled at p - v*t and the newest frame at p + v*(1-t).
	 * <p>
	 * Each pixel tries a few motions and keeps the one under which the two moved pictures agree best:
	 * first the motion blended between the centres of the neighbouring blocks (smooth, so blocks do not
	 * tear apart along their borders), then the motion of each of those four blocks (so a pixel where
	 * two differently moving areas meet follows its own area instead of being bent), and with
	 * {@code u_wide} also the twelve blocks around those. Where even the
	 * best one disagrees, the motion is wrong there (too fast to follow, or something appeared): those
	 * pixels fall back to a plain cross-fade, which looks soft instead of torn.
	 * <p>
	 * Picture coming in at an edge of the screen is only in one of the frames: a motion that
	 * takes a pixel from outside one frame is taken from the other one alone (see {@link #UNSEEN}).
	 * <p>
	 * With {@code u_cleanEdges}, such a pixel is first checked for being next to a moving object,
	 * using the motion both ways ({@code u_motion}: old to new, on the new frame's blocks;
	 * {@code u_motionBack}: new to old, on the old frame's blocks). Background the object uncovers
	 * exists only in the new frame: the place it comes from in the old frame tells a different
	 * story. Background the object covers exists only in the old frame. Such pixels are taken from
	 * the one frame where they can be seen, instead of being cross-faded with the object.
	 * <p>
	 * A blend position {@code u_t} above 1 (low latency) means a picture past the newest frame:
	 * the newest frame moved on along the motion, by {@code u_t - 1} of a frame. The motions are
	 * tried the same way: the one under which the newest frame and the frame before it agree, a
	 * frame further back along the same path, wins. Only the newest frame is shown, so agreeing
	 * colours alone can not tell apart motions inside a flat area. Among motions that agree about
	 * as well, the one of the block the picture is taken from wins, so an object is not bent by
	 * the motion of what is next to it; it only breaks ties, as a block's motion can be wrong where
	 * the colours tell better. Where no motion agrees, the best one is used all the same.
	 * <p>
	 * With the GPU search ({@code u_useError}), a scene cut found on the GPU shows the newest frame
	 * as it is.
	 * <p>
	 * During a fade the two frames differ in brightness everywhere, so a motion is also taken as
	 * agreeing when the older frame, with the change of brightness applied ({@code u_change}: gain
	 * and offset, see {@link Brightness}), matches the newer one. The blend itself uses the frames
	 * as they are, so the brightness changes smoothly.
	 */
	/**
	 * The mismatch given to a motion that takes a pixel from outside one of the frames: the picture
	 * coming in at an edge (or going out) is only in the other frame, so the motion can not be
	 * checked there and that frame alone is used. A motion that both frames confirm still wins.
	 */
	private static final String UNSEEN = "0.05";

	private static final String FRAGMENT =
			"#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
					+ "precision highp float;\n"
					+ "#else\n"
					+ "precision mediump float;\n"
					+ "#endif\n"
					+ "uniform sampler2D u_prev;\n"
					+ "uniform sampler2D u_curr;\n"
					+ "uniform sampler2D u_motion;\n"
					+ "uniform sampler2D u_motionBack;\n"
					+ "uniform sampler2D u_error;\n"
					+ "uniform float u_useError;\n"
					+ "uniform vec2 u_change;\n"
					+ "uniform float u_codeLinear;\n"
					+ "uniform float u_t;\n"
					+ "uniform float u_useMotion;\n"
					+ "uniform float u_motionUnit;\n"
					+ "uniform float u_wide;\n"
					+ "uniform float u_cleanEdges;\n"
					+ "uniform vec2 u_texSize;\n"
					+ "uniform vec2 u_blockPx;\n"
					+ "uniform vec2 u_grid;\n"
					+ "varying vec2 v_uv;\n"
					+ "vec4 g_a;\n"
					+ "vec4 g_b;\n"
					+ "float g_best;\n"
					+ "vec4 g_bestA;\n"
					+ "vec4 g_bestB;\n"
					+ "vec2 g_mv;\n"
					+ "vec2 g_bestMv;\n"
					+ MotionCode.GLSL
					+ "vec2 decode(vec4 m) {\n"
					+ "    vec2 c = vec2(m.r, m.a) * 255.0 - 128.0;\n"
					+ "    return vec2(decodeMotion(c.x, u_codeLinear), decodeMotion(c.y, u_codeLinear))\n"
					+ "            / u_motionUnit / u_texSize;\n"
					+ "}\n"
					+ "vec2 blockMotion(vec2 cell) {\n"
					+ "    vec2 c = clamp(cell, vec2(0.0), u_grid - 1.0);\n"
					+ "    return decode(texture2D(u_motion, (c + 0.5) / u_grid));\n"
					+ "}\n"
					+ "vec2 cellAt(vec2 uv) {\n"
					+ "    return clamp(floor(uv * u_texSize / u_blockPx), vec2(0.0), u_grid - 1.0);\n"
					+ "}\n"
					+ "vec2 newMotionAt(vec2 uv) {\n"
					+ "    return decode(texture2D(u_motion, (cellAt(uv) + 0.5) / u_grid));\n"
					+ "}\n"
					+ "vec2 oldMotionAt(vec2 uv) {\n"
					+ "    return -decode(texture2D(u_motionBack, (cellAt(uv) + 0.5) / u_grid));\n"
					+ "}\n"
					+ "float pixels(vec2 d) {\n"
					+ "    return length(d * u_texSize);\n"
					+ "}\n"
					// the newest frame between its pixels (blended from the four around), for pictures
					// past it: moved by a fraction of a pixel, a sharp edge then sits between two pixels
					// as in the pictures between two frames, instead of jumping to the nearest one
					+ "vec4 between(vec2 uv) {\n"
					+ "    vec2 q = uv * u_texSize - 0.5;\n"
					+ "    vec2 i = floor(q);\n"
					+ "    vec2 f = q - i;\n"
					+ "    vec2 a = (i + 0.5) / u_texSize;\n"
					+ "    vec2 s = 1.0 / u_texSize;\n"
					+ "    return mix(mix(texture2D(u_curr, a), texture2D(u_curr, a + vec2(s.x, 0.0)), f.x),\n"
					+ "            mix(texture2D(u_curr, a + vec2(0.0, s.y)), texture2D(u_curr, a + s), f.x), f.y);\n"
					+ "}\n"
					+ "bool still(vec2 uv) {\n"
					+ "    vec3 d = abs(texture2D(u_prev, uv).rgb - texture2D(u_curr, uv).rgb);\n"
					+ "    return max(max(d.r, d.g), d.b) < 0.002;\n"
					+ "}\n"
					+ "bool inside(vec2 uv) {\n"
					+ "    return uv.x >= 0.0 && uv.y >= 0.0 && uv.x <= 1.0 && uv.y <= 1.0;\n"
					+ "}\n"
					+ "float mismatch(vec2 mv) {\n"
					+ "    g_mv = mv;\n"
					+ "    vec2 pa = v_uv - mv * u_t;\n"
					+ "    vec2 pb = v_uv + mv * (1.0 - u_t);\n"
					+ "    g_a = texture2D(u_prev, pa);\n"
					+ "    g_b = texture2D(u_curr, pb);\n"
					+ "    float e;\n"
					+ "    bool ina = inside(pa);\n"
					+ "    bool inb = inside(pb);\n"
					+ "    if (ina && inb) {\n"
					// as they are, or with the brightness of a fade applied to the older frame
					+ "        vec3 d = abs(g_a.rgb - g_b.rgb);\n"
					+ "        vec3 dc = abs(g_a.rgb * u_change.x + u_change.y - g_b.rgb);\n"
					+ "        e = min(max(max(d.r, d.g), d.b), max(max(dc.r, dc.g), dc.b));\n"
					+ "    } else if (inb) {\n"
					+ "        g_a = g_b;\n"
					+ "        e = " + UNSEEN + ";\n"
					+ "    } else if (ina) {\n"
					+ "        g_b = g_a;\n"
					+ "        e = " + UNSEEN + ";\n"
					+ "    } else {\n"
					+ "        e = 1.0;\n"
					+ "    }\n"
					+ "    if (u_t > 1.0) {\n"
					+ "        e += 0.03 * smoothstep(0.5, 1.5,\n"
					+ "                pixels(newMotionAt(v_uv + mv * (1.0 - u_t)) - mv));\n"
					+ "    }\n"
					+ "    return e;\n"
					+ "}\n"
					+ "void tryBlock(vec2 cell) {\n"
					+ "    float e = mismatch(blockMotion(cell));\n"
					+ "    if (e < g_best - 0.02) {\n"
					+ "        g_best = e;\n"
					+ "        g_bestA = g_a;\n"
					+ "        g_bestB = g_b;\n"
					+ "        g_bestMv = g_mv;\n"
					+ "    }\n"
					+ "}\n"
					// how far two colours are apart, as they are or with the brightness of a fade
					// applied to the first (from the older frame)
					+ "float differ(vec4 older, vec4 newer) {\n"
					+ "    vec3 d = abs(older.rgb - newer.rgb);\n"
					+ "    vec3 dc = abs(older.rgb * u_change.x + u_change.y - newer.rgb);\n"
					+ "    return min(max(max(d.r, d.g), d.b), max(max(dc.r, dc.g), dc.b));\n"
					+ "}\n"
					+ "void main() {\n"
					+ "    if (u_useError > 0.5 && " + GpuMotionSearch.IS_CUT + ") {\n"
					+ "        gl_FragColor = texture2D(u_curr, v_uv);\n"
					+ "        return;\n"
					+ "    }\n"
					+ "    float tc = min(u_t, 1.0);\n"
					+ "    vec4 here = texture2D(u_curr, v_uv);\n"
					+ "    vec4 plain = mix(texture2D(u_prev, v_uv), here, tc);\n"
					+ "    if (u_useMotion < 0.5) {\n"
					+ "        gl_FragColor = plain;\n"
					+ "        return;\n"
					+ "    }\n"
					// still parts (a score, buttons, scenery that does not move) are shown as they are,
					// not moved along with the motion around them
					+ "    vec2 px = 1.0 / u_texSize;\n"
					+ "    bool s0 = still(v_uv);\n"
					+ "    bool s1 = still(v_uv + vec2(px.x, 0.0));\n"
					+ "    bool s2 = still(v_uv - vec2(px.x, 0.0));\n"
					+ "    bool s3 = still(v_uv + vec2(0.0, px.y));\n"
					+ "    bool s4 = still(v_uv - vec2(0.0, px.y));\n"
					+ "    if (s0 && s1 && s2 && s3 && s4) {\n"
					+ "        gl_FragColor = here;\n"
					+ "        return;\n"
					+ "    }\n"
					// next to a still part the newest frame is not blended between its pixels: that
					// would bring some of the still part into the moving picture
					+ "    bool nearStill = s0 || s1 || s2 || s3 || s4;\n"
					+ "    vec2 p = v_uv * u_texSize / u_blockPx - 0.5;\n"
					+ "    vec2 i = floor(p);\n"
					+ "    vec2 f = p - i;\n"
					+ "    vec2 m00 = blockMotion(i);\n"
					+ "    vec2 m10 = blockMotion(i + vec2(1.0, 0.0));\n"
					+ "    vec2 m01 = blockMotion(i + vec2(0.0, 1.0));\n"
					+ "    vec2 m11 = blockMotion(i + vec2(1.0, 1.0));\n"
					+ "    g_best = mismatch(mix(mix(m00, m10, f.x), mix(m01, m11, f.x), f.y));\n"
					+ "    g_bestA = g_a;\n"
					+ "    g_bestB = g_b;\n"
					+ "    g_bestMv = g_mv;\n"
					+ "    tryBlock(i);\n"
					+ "    tryBlock(i + vec2(1.0, 0.0));\n"
					+ "    tryBlock(i + vec2(0.0, 1.0));\n"
					+ "    tryBlock(i + vec2(1.0, 1.0));\n"
					+ "    if (u_wide > 0.5) {\n"
					+ "        tryBlock(i + vec2(-1.0, -1.0));\n"
					+ "        tryBlock(i + vec2(0.0, -1.0));\n"
					+ "        tryBlock(i + vec2(1.0, -1.0));\n"
					+ "        tryBlock(i + vec2(2.0, -1.0));\n"
					+ "        tryBlock(i + vec2(-1.0, 0.0));\n"
					+ "        tryBlock(i + vec2(2.0, 0.0));\n"
					+ "        tryBlock(i + vec2(-1.0, 1.0));\n"
					+ "        tryBlock(i + vec2(2.0, 1.0));\n"
					+ "        tryBlock(i + vec2(-1.0, 2.0));\n"
					+ "        tryBlock(i + vec2(0.0, 2.0));\n"
					+ "        tryBlock(i + vec2(1.0, 2.0));\n"
					+ "        tryBlock(i + vec2(2.0, 2.0));\n"
					+ "    }\n"
					+ "    float agree = 1.0 - smoothstep(0.06, 0.2, g_best);\n"
					+ "    vec4 moved = u_t <= 1.0 ? mix(g_bestA, g_bestB, tc)\n"
					+ "            : nearStill ? g_bestB : between(v_uv + g_bestMv * (1.0 - u_t));\n"
					// A sharp edge moved by a fraction of a pixel lands on whole pixels in each frame,
					// half a pixel apart: the two samples can differ although the motion is right.
					// Where they match a pixel off, it is that, not a wrong motion: the moved samples
					// are blended instead of falling back to the cross-fade of the frames in place.
					+ "    if (u_t <= 1.0 && agree < 0.999 && !s0) {\n"
					+ "        vec2 pa = v_uv - g_bestMv * u_t;\n"
					+ "        vec2 pb = v_uv + g_bestMv * (1.0 - u_t);\n"
					+ "        if (inside(pa) && inside(pb)) {\n"
					+ "            float e = differ(g_bestA, texture2D(u_curr, pb + vec2(px.x, 0.0)));\n"
					+ "            e = min(e, differ(g_bestA, texture2D(u_curr, pb - vec2(px.x, 0.0))));\n"
					+ "            e = min(e, differ(g_bestA, texture2D(u_curr, pb + vec2(0.0, px.y))));\n"
					+ "            e = min(e, differ(g_bestA, texture2D(u_curr, pb - vec2(0.0, px.y))));\n"
					+ "            e = min(e, differ(texture2D(u_prev, pa + vec2(px.x, 0.0)), g_bestB));\n"
					+ "            e = min(e, differ(texture2D(u_prev, pa - vec2(px.x, 0.0)), g_bestB));\n"
					+ "            e = min(e, differ(texture2D(u_prev, pa + vec2(0.0, px.y)), g_bestB));\n"
					+ "            e = min(e, differ(texture2D(u_prev, pa - vec2(0.0, px.y)), g_bestB));\n"
					+ "            if (e < 0.06) {\n"
					+ "                agree = 1.0;\n"
					+ "            }\n"
					+ "        }\n"
					+ "    }\n"
					+ "    vec4 fallback = u_t > 1.0 ? moved : plain;\n"
					+ "    if (u_cleanEdges > 0.5 && u_t <= 1.0 && agree < 0.999) {\n"
					+ "        vec2 mn = newMotionAt(v_uv);\n"
					+ "        vec2 pn = v_uv + mn * (1.0 - u_t);\n"
					+ "        vec2 fn = newMotionAt(pn);\n"
					+ "        bool uncovered = pixels(fn - oldMotionAt(pn - fn)) > 2.5;\n"
					+ "        vec2 mo = oldMotionAt(v_uv);\n"
					+ "        vec2 po = v_uv - mo * u_t;\n"
					+ "        vec2 fo = oldMotionAt(po);\n"
					+ "        bool covered = pixels(fo - newMotionAt(po + fo)) > 2.5;\n"
					+ "        if (uncovered && !covered) {\n"
					+ "            fallback = texture2D(u_curr, pn);\n"
					+ "        } else if (covered && !uncovered) {\n"
					+ "            fallback = texture2D(u_prev, po);\n"
					+ "        }\n"
					+ "    }\n"
					+ "    gl_FragColor = mix(fallback, moved, agree);\n"
					+ "}\n";

	final int id;
	final int aPosition;
	final int aUv;
	final int uPrev;
	final int uCurr;
	final int uMotion;
	final int uMotionBack;
	final int uError;
	final int uUseError;
	final int uChange;
	final int uCodeLinear;
	final int uCleanEdges;
	final int uT;
	final int uUseMotion;
	final int uMotionUnit;
	final int uWide;
	final int uTexSize;
	final int uBlockPx;
	final int uGrid;

	private InterpolationProgram(int id) {
		this.id = id;
		aPosition = glGetAttribLocation(id, "a_position");
		aUv = glGetAttribLocation(id, "a_uv");
		uPrev = glGetUniformLocation(id, "u_prev");
		uCurr = glGetUniformLocation(id, "u_curr");
		uMotion = glGetUniformLocation(id, "u_motion");
		uMotionBack = glGetUniformLocation(id, "u_motionBack");
		uError = glGetUniformLocation(id, "u_error");
		uUseError = glGetUniformLocation(id, "u_useError");
		uChange = glGetUniformLocation(id, "u_change");
		uCodeLinear = glGetUniformLocation(id, "u_codeLinear");
		uCleanEdges = glGetUniformLocation(id, "u_cleanEdges");
		uT = glGetUniformLocation(id, "u_t");
		uUseMotion = glGetUniformLocation(id, "u_useMotion");
		uMotionUnit = glGetUniformLocation(id, "u_motionUnit");
		uWide = glGetUniformLocation(id, "u_wide");
		uTexSize = glGetUniformLocation(id, "u_texSize");
		uBlockPx = glGetUniformLocation(id, "u_blockPx");
		uGrid = glGetUniformLocation(id, "u_grid");
	}

	/** Compiles and links the shader; null if the device refuses it (frame generation then stays off). */
	static InterpolationProgram create() {
		int vertex = compile(GL_VERTEX_SHADER, VERTEX);
		int fragment = compile(GL_FRAGMENT_SHADER, FRAGMENT);
		if (vertex == 0 || fragment == 0) {
			return null;
		}
		int program = glCreateProgram();
		glAttachShader(program, vertex);
		glAttachShader(program, fragment);
		glLinkProgram(program);
		int[] status = new int[1];
		glGetProgramiv(program, GL_LINK_STATUS, status, 0);
		glDeleteShader(vertex);
		glDeleteShader(fragment);
		if (status[0] == 0) {
			Log.e(TAG, "link failed: " + glGetProgramInfoLog(program));
			glDeleteProgram(program);
			return null;
		}
		InterpolationProgram p = new InterpolationProgram(program);
		if (p.aPosition < 0 || p.aUv < 0 || p.uPrev < 0 || p.uCurr < 0 || p.uT < 0) {
			Log.e(TAG, "the shader does not have the expected inputs");
			glDeleteProgram(program);
			return null;
		}
		return p;
	}

	private static int compile(int type, String source) {
		int shader = glCreateShader(type);
		glShaderSource(shader, source);
		glCompileShader(shader);
		int[] status = new int[1];
		glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0);
		if (status[0] == 0) {
			Log.e(TAG, "compile failed: " + glGetShaderInfoLog(shader));
			glDeleteShader(shader);
			return 0;
		}
		return shader;
	}
}
