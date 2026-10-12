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

import android.annotation.TargetApi;
import android.app.Activity;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

/**
 * Draws frame generation at a chosen number of pictures per second instead of on every screen
 * refresh. It listens to the screen's refreshes on the main thread and asks the view (which then
 * only draws when asked) for a picture on the refreshes {@link RedrawCadence} picks. It also asks
 * Android to run the screen at least that fast, which matters for 90 and 120 fps on phones whose
 * screens can do more than 60 Hz.
 * <p>
 * With even pacing it also keeps the times of the refreshes ({@link VsyncGrid}), so each picture is
 * placed for its refresh; then it drives the drawing at the screen's rate too.
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public final class FrameRateDriver implements Choreographer.FrameCallback {
	private final GLSurfaceView view;
	private final int fps;
	/** Which refreshes draw; null = every refresh. */
	private final RedrawCadence cadence;
	/** The refreshes' times, with even pacing; null otherwise. */
	private final VsyncGrid grid;
	private final Handler main = new Handler(Looper.getMainLooper());
	/** Wanted by the renderer (the game is drawing), and not paused (the game is in the background). */
	private boolean wanted;
	private boolean paused;
	private boolean posted;
	private volatile boolean running;

	private FrameRateDriver(GLSurfaceView view, int fps, boolean evenPacing) {
		this.view = view;
		this.fps = fps;
		this.cadence = fps > 0 ? new RedrawCadence(fps) : null;
		this.grid = evenPacing ? new VsyncGrid() : null;
	}

	/**
	 * A driver for {@code fps} pictures per second (0 = the screen's rate), or null where it is not
	 * needed (the screen's rate without even pacing) or this Android can not pace drawing.
	 */
	public static FrameRateDriver create(GLSurfaceView view, int fps, boolean evenPacing) {
		if ((fps <= 0 && !evenPacing) || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
			return null;
		}
		return new FrameRateDriver(view, fps, evenPacing);
	}

	/** Asks Android to run the screen at least {@code fps} times a second while the game is shown. */
	public void requestScreenRate(Activity activity) {
		if (fps <= 0) {
			return;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			view.getHolder().addCallback(new SurfaceHolder.Callback() {
				@Override
				public void surfaceCreated(SurfaceHolder holder) {
					setSurfaceRate(holder.getSurface());
				}

				@Override
				public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
				}

				@Override
				public void surfaceDestroyed(SurfaceHolder holder) {
				}
			});
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && activity != null) {
			WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
			lp.preferredRefreshRate = fps;
			activity.getWindow().setAttributes(lp);
		}
	}

	@TargetApi(Build.VERSION_CODES.R)
	private void setSurfaceRate(Surface surface) {
		try {
			surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
		} catch (RuntimeException e) {
			// only a wish: the screen keeps its rate
		}
	}

	/**
	 * The time to place a picture for when drawing it started at {@code nowNanos}: its refresh with
	 * even pacing, otherwise {@code nowNanos}. Called from the GL thread.
	 */
	public long pictureTime(long nowNanos) {
		return grid != null ? grid.snap(nowNanos) : nowNanos;
	}

	/** Whether pictures are placed for their refreshes (even pacing). */
	public boolean pacesEvenly() {
		return grid != null;
	}

	/** The time between screen refreshes, or 0 while it is not known (or without even pacing). */
	public long refreshNanos() {
		return grid != null ? grid.periodNanos() : 0;
	}

	/** Whether pictures are being asked for (then the game's own frames need not ask). */
	public boolean isRunning() {
		return running;
	}

	/** Starts or stops asking for pictures; called from the renderer. */
	public void setWanted(boolean wanted) {
		main.post(() -> {
			this.wanted = wanted;
			update();
		});
	}

	/** The game went to the background (true) or came back (false). */
	public void setPaused(boolean paused) {
		main.post(() -> {
			this.paused = paused;
			update();
		});
	}

	private void update() {
		boolean run = wanted && !paused;
		running = run;
		if (run && !posted) {
			if (cadence != null) {
				cadence.reset();
			}
			if (grid != null) {
				grid.reset();
			}
			posted = true;
			Choreographer.getInstance().postFrameCallback(this);
		}
	}

	@Override
	public void doFrame(long frameTimeNanos) {
		posted = false;
		if (!running) {
			return;
		}
		if (grid != null) {
			grid.onVsync(frameTimeNanos);
		}
		if (cadence == null || cadence.onVsync(frameTimeNanos)) {
			view.requestRender();
		}
		posted = true;
		Choreographer.getInstance().postFrameCallback(this);
	}
}
