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
 * Keeps track of how often the game finishes a frame: the smoothed time between frames, which
 * {@link FramePacer} uses to spread each blend over one game frame.
 */
public final class FrameClock {
	private static final long MS = 1_000_000L;
	private static final long MIN_INTERVAL = 4 * MS;
	private static final long MAX_INTERVAL = 250 * MS;
	/** A pause longer than this (a menu, a loading screen) does not count as the game's frame time. */
	private static final long STALL = 400 * MS;

	private final int weight;
	/** Which way the last frame time was well off the expected one: -1, 0 or 1. */
	private int lastOff;
	private long arrival;
	private long interval = 50 * MS;
	private boolean measured;

	public FrameClock() {
		this(4);
	}

	/**
	 * @param weight how slowly the frame time follows each new measurement: each counts for
	 *               {@code 1 / weight}; one more than a quarter off, or the second in a row well
	 *               off the same way (the game changed its pace), counts for at least a quarter
	 */
	FrameClock(int weight) {
		this.weight = weight;
	}

	/** A new game frame was finished at {@code nowNanos}. */
	public void onFrame(long nowNanos) {
		if (arrival != 0) {
			long gap = nowNanos - arrival;
			if (gap > 0 && gap < STALL) {
				long clamped = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, gap));
				// the first measurement is taken as it is, the rest is smoothed
				long diff = clamped - interval;
				int off = Math.abs(diff) > interval / 8 ? Long.signum(diff) : 0;
				boolean changed = Math.abs(diff) > interval / 4 || (off != 0 && off == lastOff);
				lastOff = off;
				int w = changed ? Math.min(weight, 4) : weight;
				interval = measured ? (interval * (w - 1) + clamped) / w : clamped;
				measured = true;
			}
		}
		arrival = nowNanos;
	}

	/** The expected time between game frames, in nanoseconds. */
	public long intervalNanos() {
		return interval;
	}
}
