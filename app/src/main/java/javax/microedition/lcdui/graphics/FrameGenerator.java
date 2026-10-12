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

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_COLOR_ATTACHMENT0;
import static android.opengl.GLES20.GL_FLOAT;
import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_FRAMEBUFFER_COMPLETE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_LUMINANCE_ALPHA;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_RGBA;
import static android.opengl.GLES20.GL_TEXTURE0;
import static android.opengl.GLES20.GL_TEXTURE1;
import static android.opengl.GLES20.GL_TEXTURE2;
import static android.opengl.GLES20.GL_TEXTURE3;
import static android.opengl.GLES20.GL_TEXTURE4;
import static android.opengl.GLES20.GL_TEXTURE_2D;
import static android.opengl.GLES20.GL_TEXTURE_MAG_FILTER;
import static android.opengl.GLES20.GL_TEXTURE_MIN_FILTER;
import static android.opengl.GLES20.GL_TEXTURE_WRAP_S;
import static android.opengl.GLES20.GL_TEXTURE_WRAP_T;
import static android.opengl.GLES20.GL_TRIANGLE_STRIP;
import static android.opengl.GLES20.GL_UNPACK_ALIGNMENT;
import static android.opengl.GLES20.GL_UNSIGNED_BYTE;
import static android.opengl.GLES20.glActiveTexture;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glBindTexture;
import static android.opengl.GLES20.glCheckFramebufferStatus;
import static android.opengl.GLES20.glDeleteFramebuffers;
import static android.opengl.GLES20.glDeleteProgram;
import static android.opengl.GLES20.glDeleteTextures;
import static android.opengl.GLES20.glDisableVertexAttribArray;
import static android.opengl.GLES20.glDrawArrays;
import static android.opengl.GLES20.glEnableVertexAttribArray;
import static android.opengl.GLES20.glFramebufferTexture2D;
import static android.opengl.GLES20.glGenFramebuffers;
import static android.opengl.GLES20.glGenTextures;
import static android.opengl.GLES20.glPixelStorei;
import static android.opengl.GLES20.glTexImage2D;
import static android.opengl.GLES20.glTexParameteri;
import static android.opengl.GLES20.glUniform1f;
import static android.opengl.GLES20.glUniform1i;
import static android.opengl.GLES20.glUniform2f;
import static android.opengl.GLES20.glUseProgram;
import static android.opengl.GLES20.glVertexAttribPointer;
import static android.opengl.GLES20.glViewport;

import android.graphics.Bitmap;
import android.opengl.GLUtils;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Frame generation: shows pictures between the frames a game finishes, so that a game that
 * draws 20 frames per second looks smoother on a 60 Hz screen. The game itself is not changed
 * or sped up; only what is shown on screen.
 * <p>
 * It keeps the previous and the newest game frame as textures. On every screen refresh it draws a
 * picture between them into an off-screen texture, which the normal drawing code (with the
 * user's shader filter and scaling) then puts on the screen. With motion enabled, how each block
 * of the picture moved between the two frames is worked out on a worker thread
 * ({@link MotionEstimator}) and the parts are moved along that motion before they are blended;
 * until it is ready, or when nothing matches, the two frames are only cross-faded.
 * <p>
 * The price is one game frame of added delay, because the newest frame is shown only after the
 * picture between it and the previous one. With low latency the newest frame is shown as soon as
 * it comes and, once its motion is known, carried on along that motion until the next frame comes: no added
 * delay, but a guess, which overshoots for a moment when things stop or turn. All methods must be
 * called on the GL thread.
 */
public final class FrameGenerator {
	private static final String TAG = FrameGenerator.class.getName();
	/** Cross-fade the frames. */
	public static final int MODE_BLEND = 1;
	/** Follow the motion between frames. */
	public static final int MODE_MOTION = 2;
	/** Follow the motion finer, faster and more precisely, for more CPU and GPU time. */
	public static final int MODE_MOTION_HQ = 3;
	private static final int BLOCK = 8;
	private static final int RANGE = 8;
	/** How long a motion search thread waits for work before it ends. */
	private static final long IDLE_SECONDS = 3;

	/** What the worker found out about the motion into a frame (no data: it could not tell). */
	private static final class Motion {
		final int generation;
		final int frame;
		final int cols;
		final int rows;
		final byte[] data;
		/** The motion the other way (new to old frame), when edges are cleaned; else null. */
		final byte[] dataBack;
		final boolean sceneCut;
		final long readyNanos;

		Motion(int generation, int frame, int cols, int rows, byte[] data, byte[] dataBack,
			   boolean sceneCut, long readyNanos) {
			this.generation = generation;
			this.frame = frame;
			this.cols = cols;
			this.rows = rows;
			this.data = data;
			this.dataBack = dataBack;
			this.sceneCut = sceneCut;
			this.readyNanos = readyNanos;
		}
	}

	/** The previous picture seen by the worker; only the worker thread touches it. */
	private static final class GrayState {
		byte[] gray;
		/** The array of the picture before, no longer needed: the next picture goes into it. */
		byte[] spare;
		/** The older picture with the change of brightness applied (reused). */
		byte[] changed;
		int frame = -2;
		/** The motion found into that frame (the guess for the next one), or null. */
		int[] vectors;
	}

	private final int mode;
	/** Also search the motion backward, to clean the edges next to moving objects. */
	private final boolean cleanEdges;
	/** Search the motion with the GPU (falls back to the CPU where that can not be built). */
	private final boolean gpuSearch;
	/** Offer the motion found into the previous frame as a guess (steady motion). */
	private final boolean steady;
	/** Show each frame as soon as it comes and carry it on along its motion (low latency). */
	private final boolean lowLatency;
	/** Time the blends from the game's rhythm, not the exact arrival of its frames (even pacing). */
	private final boolean evenPacing;
	/** Whether pictures go past the newest frame now: low latency, with a motion search. */
	private boolean ahead;
	private GpuMotionSearch gpu;
	/** The GPU search failed while running: the CPU searches from the next allocation on. */
	private boolean gpuFailed;
	/** Set up again (for the CPU search) on the next picture. */
	private boolean reallocate;
	/** The frame the GPU last searched the motion into. */
	private int gpuFrame = -1;
	private final boolean linear;
	private final InterpolationProgram program;
	private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4)
			.order(ByteOrder.nativeOrder()).asFloatBuffer();
	private final AtomicBoolean busy = new AtomicBoolean();
	/** The kept game frames: frame n is in frameTex[n % KEPT]. */
	private final int[] frameTex = new int[FramePacer.KEPT];
	/** How bright each kept frame is (mean and deviation of its grey), to follow fades. */
	private final float[] frameMean = new float[FramePacer.KEPT];
	private final float[] frameDeviation = new float[FramePacer.KEPT];
	private final Brightness brightness = new Brightness();
	private int[] sampleRow;
	/** The motion into each kept frame, once the worker has it. */
	private final Motion[] motions = new Motion[FramePacer.KEPT];
	private FramePacer pacer = new FramePacer(false);

	private int width;
	private int height;
	private int texMotion;
	private int texMotionBack;
	private int texOut;
	private int fbo;
	private boolean allocated;

	/** Counts the (re)allocations: motion from an earlier one is ignored. */
	private int generation;
	private GrayState grayState = new GrayState();
	private int seenFrame = -1;
	private int frameIndex;

	private MotionEstimator estimator;
	private FineMotionEstimator fineEstimator;
	/** Steps per frame pixel in the motion texture, and whether the shader tries more blocks. */
	private float motionUnit = 1f;
	private boolean wideSearch;
	private int[] pixels;
	/** Holds the motion on its way into a texture. */
	private ByteBuffer vectorBuffer;
	private ExecutorService worker;
	/** Threads that share the motion search with the worker; null on a phone with few cores. */
	private ExecutorService helpers;
	/** Into how many bands the motion search is split (1 = the worker alone). */
	private final int parts;
	private volatile Motion motion;
	private int uploadedMotion = -1;
	private int gridCols = 1;
	private int gridRows = 1;
	private float blockPxX = 1;
	private float blockPxY = 1;

	private FrameGenerator(InterpolationProgram program, int mode, boolean linear, boolean multiCore,
						   boolean cleanEdges, boolean gpuSearch, boolean steady, boolean lowLatency,
						   boolean evenPacing) {
		this.program = program;
		this.mode = mode;
		this.cleanEdges = cleanEdges;
		this.gpuSearch = gpuSearch;
		this.steady = steady;
		this.lowLatency = lowLatency;
		this.evenPacing = evenPacing;
		this.parts = multiCore ? searchParts(Runtime.getRuntime().availableProcessors()) : 1;
		this.linear = linear;
		// a full-screen quad: clip position then texture position, so the output texture is the
		// same picture as the input textures
		quad.put(new float[]{
				-1f, -1f, 0f, 0f,
				-1f, 1f, 0f, 1f,
				1f, -1f, 1f, 0f,
				1f, 1f, 1f, 1f});
		quad.rewind();
	}

	/**
	 * Creates the generator on the GL thread.
	 *
	 * @param mode      {@link #MODE_BLEND}, {@link #MODE_MOTION} or {@link #MODE_MOTION_HQ}
	 * @param linear    filter the textures smoothly (the screen's filtering setting)
	 * @param multiCore  let the motion search use several CPU cores (the same result, sooner)
	 * @param cleanEdges search the motion both ways too, so edges next to moving objects are taken
	 *                   from the frame where they can be seen instead of cross-faded (twice the CPU)
	 * @param gpuSearch  search the motion with the GPU instead of the CPU (where the GPU can)
	 * @param steady     offer the motion found into the previous frame as a guess, which keeps the
	 *                   motion from jumping between frames
	 * @param lowLatency show each game frame as soon as it comes and carry it on along its motion
	 *                   until the next one, instead of showing it a frame later (motion modes only;
	 *                   it guesses, so it overshoots when things stop or turn)
	 * @param evenPacing time the pictures from the game's rhythm instead of the exact moments its
	 *                   frames arrived, which a few milliseconds of wobble would make uneven
	 * @return null if the device can not run the shader, in which case the normal drawing is used
	 */
	public static FrameGenerator create(int mode, boolean linear, boolean multiCore,
										boolean cleanEdges, boolean gpuSearch, boolean steady,
										boolean lowLatency, boolean evenPacing) {
		InterpolationProgram p = InterpolationProgram.create();
		return p == null ? null
				: new FrameGenerator(p, mode, linear, multiCore, cleanEdges, gpuSearch, steady,
				lowLatency, evenPacing);
	}

	/**
	 * Works out the picture to show now.
	 *
	 * @param bitmap       the game's newest finished frame
	 * @param bufferLock   the lock that guards {@code bitmap}
	 * @param frameCounter changes every time the game finished a frame
	 * @param frameNanos   when the game finished its newest frame ({@code System.nanoTime()})
	 * @param nowNanos     the current {@code System.nanoTime()}
	 * @return the id of a texture with the picture, or 0 if frame generation can not be used
	 */
	public int render(Bitmap bitmap, Object bufferLock, int frameCounter, long frameNanos, long nowNanos) {
		if (!allocated || reallocate || bitmap.getWidth() != width || bitmap.getHeight() != height) {
			if (!allocate(bitmap.getWidth(), bitmap.getHeight())) {
				return 0;
			}
		}
		if (frameCounter != seenFrame) {
			seenFrame = frameCounter;
			acceptFrame(bitmap, bufferLock, frameNanos);
		}
		return draw(nowNanos);
	}

	/**
	 * Whether the last picture was the newest game frame as it is: until the game sends another
	 * frame, redrawing would show the same picture.
	 */
	public boolean isSettled() {
		return pacer.isSettled();
	}

	/** Stops the worker and frees the GL objects (when the context is lost they go with it). */
	public void shutdown() {
		releaseBuffers();
		try {
			glDeleteProgram(program.id);
		} catch (RuntimeException e) {
			// the context may already be gone
		}
	}

	/**
	 * Lets go of a generator whose GL context is gone (a new one was made): stops the worker but
	 * makes no GL calls, since its texture and program numbers may now belong to the new context.
	 */
	public void abandon() {
		stopThreads();
		gpu = null; // its GL objects went with the old context
		allocated = false;
	}

	/** Frees the textures and the framebuffer, and stops the worker. */
	private void releaseBuffers() {
		stopThreads();
		if (gpu != null) {
			try {
				gpu.release();
			} catch (RuntimeException e) {
				// the context may already be gone
			}
			gpu = null;
		}
		if (allocated) {
			try {
				glDeleteTextures(6, new int[]{frameTex[0], frameTex[1], frameTex[2], texMotion,
						texMotionBack, texOut}, 0);
				glDeleteFramebuffers(1, new int[]{fbo}, 0);
			} catch (RuntimeException e) {
				// the context may already be gone
			}
			allocated = false;
		}
	}

	private void stopThreads() {
		if (worker != null) {
			worker.shutdownNow();
			worker = null;
		}
		if (helpers != null) {
			helpers.shutdownNow();
			helpers = null;
		}
	}

	/**
	 * Into how many bands to split the motion search on a phone with {@code cores} cores: half of
	 * them, at most four, so the game, the screen and the rest of the phone keep cores of their own.
	 */
	static int searchParts(int cores) {
		return Math.max(1, Math.min(4, cores / 2));
	}

	// ------------------------------------------------------------------ setup

	private boolean allocate(int w, int h) {
		releaseBuffers();
		reallocate = false;
		generation++;
		grayState = new GrayState();
		int[] ids = new int[6];
		glGenTextures(6, ids, 0);
		System.arraycopy(ids, 0, frameTex, 0, FramePacer.KEPT);
		texMotion = ids[3];
		texMotionBack = ids[4];
		texOut = ids[5];
		for (int tex : frameTex) {
			setupTexture(tex, linear);
		}
		setupTexture(texMotion, false);
		setupTexture(texMotionBack, false);
		setupTexture(texOut, linear);
		glBindTexture(GL_TEXTURE_2D, texOut);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);
		int[] fb = new int[1];
		glGenFramebuffers(1, fb, 0);
		fbo = fb[0];
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texOut, 0);
		boolean complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		if (!complete) {
			Log.e(TAG, "the off-screen framebuffer is not usable");
			glDeleteTextures(6, ids, 0);
			glDeleteFramebuffers(1, fb, 0);
			return false;
		}
		width = w;
		height = h;
		allocated = true;
		seenFrame = -1;
		frameIndex = 0;
		uploadedMotion = -1;
		java.util.Arrays.fill(motions, null);
		motion = null;
		estimator = null;
		fineEstimator = null;
		pixels = null;
		motionUnit = 1f;
		wideSearch = false;
		gpuFrame = -1;
		int minSize = 4 * Math.max(BLOCK, FineMotionEstimator.BLOCK);
		if (mode >= MODE_MOTION && w >= minSize && h >= minSize && gpuSearch && !gpuFailed) {
			gpu = GpuMotionSearch.create(w, h);
			if (gpu == null) {
				Log.w(TAG, "the GPU can not search the motion here: the CPU does it");
			}
		}
		if (gpu != null) {
			// the motion stays on the GPU: no worker, nothing to wait for
			gridCols = gpu.cols();
			gridRows = gpu.rows();
			blockPxX = blockPxY = GpuMotionSearch.BLOCK;
			motionUnit = GpuMotionSearch.UNITS_PER_PIXEL;
			wideSearch = mode == MODE_MOTION_HQ;
		} else if (mode >= MODE_MOTION && w >= minSize && h >= minSize) {
			if (mode == MODE_MOTION_HQ) {
				fineEstimator = new FineMotionEstimator(w, h);
				gridCols = fineEstimator.cols();
				gridRows = fineEstimator.rows();
				blockPxX = blockPxY = FineMotionEstimator.BLOCK;
				motionUnit = FineMotionEstimator.UNITS_PER_PIXEL;
				wideSearch = true;
			} else {
				int gw = w / 2;
				int gh = h / 2;
				estimator = new MotionEstimator(gw, gh, BLOCK, RANGE, true);
				gridCols = estimator.cols();
				gridRows = estimator.rows();
				blockPxX = (float) BLOCK * w / gw;
				blockPxY = (float) BLOCK * h / gh;
				// quarter pixels of the half-size picture, encoded twice as many: quarter frame pixels
				motionUnit = MotionEstimator.SUB_UNITS;
			}
			pixels = new int[w * h];
			worker = threads(1, "FrameGenMotion");
			if (parts > 1) {
				helpers = threads(parts - 1, "FrameGenMotionHelper");
			}
			busy.set(false);
		}
		// going past the newest frame needs its motion; plain blending can only look back
		ahead = lowLatency && (worker != null || gpu != null);
		pacer = new FramePacer(worker != null && !ahead, evenPacing);
		return true;
	}

	/**
	 * {@code count} threads for the motion search. They end when they have had nothing to do for a
	 * while, so a canvas the game no longer shows (its GL context gone, nothing to shut it down)
	 * does not keep them, and start again with the next job.
	 */
	private static ExecutorService threads(int count, String name) {
		ThreadPoolExecutor pool = new ThreadPoolExecutor(count, count, IDLE_SECONDS, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(), r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			return t;
		});
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}

	private static void setupTexture(int tex, boolean linear) {
		glBindTexture(GL_TEXTURE_2D, tex);
		int filter = linear ? GL_LINEAR : GL_NEAREST;
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	}

	// ------------------------------------------------------------------ a new game frame

	private void acceptFrame(Bitmap bitmap, Object bufferLock, long frameNanos) {
		boolean grab = worker != null && !busy.get();
		// the pacer makes sure the frame this one replaces is no longer shown
		int n = pacer.onFrame(frameNanos, grab);
		frameIndex = n;
		glActiveTexture(GL_TEXTURE0);
		synchronized (bufferLock) {
			glBindTexture(GL_TEXTURE_2D, frameTex[n % FramePacer.KEPT]);
			GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0);
			if (n == 1) {
				// the first frame is blended from itself
				glBindTexture(GL_TEXTURE_2D, frameTex[0]);
				GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0);
			}
			if (mode >= MODE_MOTION) {
				measureBrightness(bitmap, n % FramePacer.KEPT);
				if (n == 1) {
					frameMean[0] = frameMean[1];
					frameDeviation[0] = frameDeviation[1];
				}
			}
			if (grab) {
				bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
			}
		}
		if (grab) {
			busy.set(true);
			startMotionJob(n);
		}
		if (gpu != null) {
			searchOnGpu(n);
		}
	}

	/** How bright a frame is, from every fourth pixel of every fourth row. */
	private void measureBrightness(Bitmap bitmap, int slot) {
		if (sampleRow == null || sampleRow.length != width) {
			sampleRow = new int[width];
		}
		brightness.reset();
		for (int y = 0; y < height; y += 4) {
			bitmap.getPixels(sampleRow, 0, width, 0, y, width, 1);
			for (int x = 0; x < width; x += 4) {
				brightness.add(sampleRow[x]);
			}
		}
		frameMean[slot] = brightness.mean();
		frameDeviation[slot] = brightness.deviation();
	}

	/** How the brightness changed from frame {@code k - 1} to frame {@code k} (a fade or a flash). */
	private float[] brightnessChange(int k) {
		if (mode < MODE_MOTION || k < 2) {
			return Brightness.NONE;
		}
		int a = (k - 1) % FramePacer.KEPT, b = k % FramePacer.KEPT;
		return Brightness.change(frameMean[a], frameDeviation[a], frameMean[b], frameDeviation[b]);
	}

	/** Searches the motion into frame {@code n} on the GPU, right away. */
	private void searchOnGpu(int n) {
		try {
			int slot = n % FramePacer.KEPT;
			gpu.addFrame(slot, frameTex[slot]);
			if (n == 1) {
				gpu.addFrame(0, frameTex[0]); // the first frame is blended from itself
				return;
			}
			int prev = (n - 1) % FramePacer.KEPT;
			// the last search's motion is a guess only if it was into the frame before this one
			// (after a scene cut it is no motion, which guesses nothing)
			boolean usePrevious = steady && gpuFrame == n - 1;
			// past the newest frame there is no next one to clean edges with
			gpu.search(prev, frameTex[prev], slot, frameTex[slot], cleanEdges && !ahead, usePrevious,
					brightnessChange(n));
			gpuFrame = n;
		} catch (RuntimeException e) {
			Log.w(TAG, "the GPU motion search failed: the CPU searches from now on", e);
			gpuFailed = true;
			// set up again on the next picture, with the CPU search; until then frames are blended
			reallocate = true;
			gpuFrame = -1;
		}
	}

	/** Hands the picture grabbed under the lock to the worker, which finds the motion into it. */
	private void startMotionJob(final int index) {
		final int[] px = pixels;
		final int w = width;
		final int h = height;
		final MotionEstimator est = estimator;
		final FineMotionEstimator fine = fineEstimator;
		final ExecutorService executor = worker;
		final ExecutorService pool = helpers;
		final int bands = parts;
		final GrayState state = grayState;
		final int gen = generation;
		final boolean bothWays = cleanEdges && !ahead;
		final boolean useLast = steady;
		final float[] change = brightnessChange(index);
		executor.execute(() -> {
			try {
				byte[] gray = fine != null ? FineMotionEstimator.toGray(px, w, h, state.spare)
						: MotionEstimator.toGray(px, w, h, state.spare);
				state.spare = null; // in use now
				// a new picture is only grabbed after this job finished, so nothing else reads it
				Motion found = null;
				int[] last = null;
				if (state.gray != null && state.frame == index - 1) {
					// the motion into the previous frame, if it was worked out: things keep moving
					int[] guess = useLast ? state.vectors : null;
					// the older picture as bright as the newer one, so a fade still matches
					byte[] older = change == Brightness.NONE ? state.gray
							: (state.changed = Brightness.apply(state.gray, change, state.changed));
					MotionEstimator.Result r = fine != null ? fine.estimate(older, gray, pool, bands, guess)
							: est.estimate(older, gray, pool, bands, guess);
					last = r.sceneCut ? null : r.vectors;
					// the fine vectors are already in the texture's steps (quarter pixels); the
					// normal ones are in quarter pixels of the half-size picture, two frame pixels each
					int scale = fine != null ? 1 : 2;
					byte[] back = null;
					if (bothWays && !r.sceneCut) {
						// the same search with the frames swapped: new to old, on the old frame's blocks
						MotionEstimator.Result b = fine != null ? fine.estimate(gray, older, pool, bands)
								: est.estimate(gray, older, pool, bands);
						back = b.encode(scale);
					}
					found = new Motion(gen, index, r.cols, r.rows, r.encode(scale), back,
							r.sceneCut, System.nanoTime());
				}
				state.spare = state.gray;
				state.gray = gray;
				state.frame = index;
				state.vectors = last;
				// also when nothing was found, so the blend does not wait for it
				motion = found != null ? found
						: new Motion(gen, index, 0, 0, null, null, false, System.nanoTime());
			} catch (java.util.concurrent.CancellationException e) {
				// the generator is shutting down
			} catch (RuntimeException e) {
				Log.w(TAG, "motion estimation failed", e);
			} finally {
				busy.set(false);
			}
		});
	}

	// ------------------------------------------------------------------ one screen refresh

	private int draw(long nowNanos) {
		takeMotion();
		int k = ahead ? pacer.updateAhead(nowNanos) : pacer.update(nowNanos);
		return drawPicture(k, pacer.blend());
	}

	/** Takes the motion the worker published, if it is new. */
	private void takeMotion() {
		Motion published = motion;
		if (published != null && published.generation == generation
				&& published.frame > frameIndex - FramePacer.KEPT
				&& motions[published.frame % FramePacer.KEPT] != published) {
			motions[published.frame % FramePacer.KEPT] = published;
			pacer.onMotionReady(published.frame, published.readyNanos);
		}
	}

	/**
	 * For tests: the picture at blend position {@code t} between the two newest frames (above 1:
	 * past the newest), whatever the time; the texture with it.
	 */
	int drawAt(float t) {
		takeMotion();
		return drawPicture(frameIndex, t);
	}

	/** For tests: whether the motion into the newest frame is known (or there is none to wait for). */
	boolean motionReady() {
		takeMotion();
		if (gpu != null) {
			return frameIndex <= 1 || gpuFrame == frameIndex; // the first frame has none
		}
		Motion m = motions[frameIndex % FramePacer.KEPT];
		return worker == null || (m != null && m.frame == frameIndex);
	}

	/** Draws the picture between frames k-1 and k at blend position {@code t}. */
	private int drawPicture(int k, float t) {
		boolean useMotion = false;
		boolean useBack = false;
		Motion m = motions[k % FramePacer.KEPT];
		boolean useError = false;
		if (gpu != null) {
			if (gpuFrame == k) {
				// a scene cut is told by the shader, from the GPU's own measure
				useMotion = true;
				useBack = cleanEdges && !ahead;
				useError = true;
			}
		} else if (m != null && m.frame == k && m.data != null) {
			if (m.sceneCut) {
				t = 1f; // a different scene: show it as it is
			} else {
				if (uploadedMotion != m.frame) {
					uploadMotion(m);
				}
				useMotion = true;
				useBack = m.dataBack != null;
			}
		}
		if (!useMotion && t > 1f) {
			t = 1f; // the motion is not known yet: the newest frame as it is
		}

		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glViewport(0, 0, width, height);
		glUseProgram(program.id);

		quad.position(0);
		glVertexAttribPointer(program.aPosition, 2, GL_FLOAT, false, 16, quad);
		glEnableVertexAttribArray(program.aPosition);
		quad.position(2);
		glVertexAttribPointer(program.aUv, 2, GL_FLOAT, false, 16, quad);
		glEnableVertexAttribArray(program.aUv);

		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, frameTex[(k - 1) % FramePacer.KEPT]);
		glActiveTexture(GL_TEXTURE1);
		glBindTexture(GL_TEXTURE_2D, frameTex[k % FramePacer.KEPT]);
		glActiveTexture(GL_TEXTURE2);
		glBindTexture(GL_TEXTURE_2D, gpu != null ? gpu.forwardTexture() : texMotion);
		glActiveTexture(GL_TEXTURE3);
		glBindTexture(GL_TEXTURE_2D, gpu != null ? gpu.backwardTexture() : texMotionBack);
		glActiveTexture(GL_TEXTURE4);
		glBindTexture(GL_TEXTURE_2D, gpu != null ? gpu.errorTexture() : texMotion);
		glUniform1i(program.uPrev, 0);
		glUniform1i(program.uCurr, 1);
		glUniform1i(program.uMotion, 2);
		glUniform1i(program.uMotionBack, 3);
		glUniform1i(program.uError, 4);
		glUniform1f(program.uUseError, useError ? 1f : 0f);
		float[] change = brightnessChange(k);
		glUniform2f(program.uChange, change[0], change[1] / 255f);
		glUniform1f(program.uCleanEdges, useBack ? 1f : 0f);
		glUniform1f(program.uT, t);
		glUniform1f(program.uUseMotion, useMotion ? 1f : 0f);
		glUniform1f(program.uMotionUnit, motionUnit);
		glUniform1f(program.uWide, wideSearch ? 1f : 0f);
		glUniform2f(program.uTexSize, width, height);
		glUniform2f(program.uBlockPx, blockPxX, blockPxY);
		glUniform2f(program.uGrid, gridCols, gridRows);
		glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

		glDisableVertexAttribArray(program.aPosition);
		glDisableVertexAttribArray(program.aUv);
		glActiveTexture(GL_TEXTURE0);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		return texOut;
	}

	private void uploadMotion(Motion m) {
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		uploadVectors(GL_TEXTURE2, texMotion, m.data, m.cols, m.rows);
		if (m.dataBack != null) {
			uploadVectors(GL_TEXTURE3, texMotionBack, m.dataBack, m.cols, m.rows);
		}
		glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
		glActiveTexture(GL_TEXTURE0);
		gridCols = m.cols;
		gridRows = m.rows;
		uploadedMotion = m.frame;
	}

	private void uploadVectors(int unit, int texture, byte[] data, int cols, int rows) {
		// GL copies the data before it returns, so one buffer serves every upload
		if (vectorBuffer == null || vectorBuffer.capacity() < data.length) {
			vectorBuffer = ByteBuffer.allocateDirect(data.length);
		}
		ByteBuffer buf = vectorBuffer;
		buf.clear();
		buf.put(data).position(0);
		glActiveTexture(unit);
		glBindTexture(GL_TEXTURE_2D, texture);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE_ALPHA, cols, rows, 0,
				GL_LUMINANCE_ALPHA, GL_UNSIGNED_BYTE, buf);
	}
}
