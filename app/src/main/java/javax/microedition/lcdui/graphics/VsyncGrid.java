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
 * The screen's refreshes as an even grid of times, so that each generated picture is placed for a
 * refresh instead of for the moment drawing happened to start. Drawing starts a few milliseconds
 * after a refresh, by a different amount every time; placing pictures by that start time makes
 * moving things step unevenly although the screen shows them at an even rate.
 * <p>
 * The refreshes come from the main thread ({@link #onVsync}), the drawing times from the GL thread
 * ({@link #snap}). Drawing usually starts at about the same point after a refresh; that point is
 * learned, and a drawing time is moved to the refresh it belongs to, so a start that is a little
 * early or late still lands on the same refresh. Times are {@code System.nanoTime()} values. Pure
 * logic, no Android.
 */
final class VsyncGrid {
	private static final long NO_TIME = Long.MIN_VALUE;
	/** A gap longer than this between refreshes is a pause, not the screen's refresh period. */
	private static final long PAUSE = 50_000_000L;

	private long lastVsync = NO_TIME;
	private long period;
	/** How long after its refresh drawing usually starts, learned from the drawing times. */
	private long phase;
	private boolean phaseKnown;

	/** A screen refresh happened at {@code vsyncNanos}. */
	synchronized void onVsync(long vsyncNanos) {
		if (lastVsync != NO_TIME) {
			long gap = vsyncNanos - lastVsync;
			if (gap > 0 && gap < PAUSE) {
				// several refreshes can come in one callback when the main thread was busy
				long periods = period == 0 ? 1 : Math.max(1, (gap + period / 2) / period);
				long one = gap / periods;
				period = period == 0 ? one : (period * 7 + one) / 8;
			}
		}
		if (lastVsync == NO_TIME || vsyncNanos > lastVsync) {
			lastVsync = vsyncNanos;
		}
	}

	/** The time between refreshes, or 0 while it is not known yet. */
	synchronized long periodNanos() {
		return period;
	}

	/**
	 * The refresh that drawing which started at {@code nowNanos} belongs to; {@code nowNanos}
	 * itself while the grid is not known yet.
	 */
	synchronized long snap(long nowNanos) {
		if (period == 0 || lastVsync == NO_TIME || nowNanos - lastVsync > PAUSE
				|| lastVsync - nowNanos > PAUSE) {
			return nowNanos;
		}
		if (!phaseKnown) {
			phase = floorMod(nowNanos - lastVsync, period);
			phaseKnown = true;
		} else {
			// the usual start, followed slowly. A start that is a refresh earlier or later counts as
			// close; the phase itself is never wrapped, as that would move every picture a refresh.
			long diff = floorMod(nowNanos - lastVsync - phase + period / 2, period) - period / 2;
			phase += diff / 16;
		}
		// the refresh whose usual start is closest to this one
		long sinceUsualStart = nowNanos - lastVsync - phase;
		long refreshes = floorDiv(sinceUsualStart + period / 2, period);
		return lastVsync + refreshes * period;
	}

	/** Starts over, as after a pause. */
	synchronized void reset() {
		lastVsync = NO_TIME;
		phaseKnown = false;
	}

	// Math.floorDiv and floorMod need Android 7
	private static long floorDiv(long a, long b) {
		long q = a / b;
		return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q;
	}

	private static long floorMod(long a, long b) {
		return a - floorDiv(a, b) * b;
	}
}
