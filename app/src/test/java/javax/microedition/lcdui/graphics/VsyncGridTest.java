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

import org.junit.Test;

import java.util.Random;

public class VsyncGridTest {
	private static final long P = 16_666_667L; // 60 Hz
	private static final long BASE = 5_000_000_000L;
	private static final long MS = 1_000_000L;

	private static long vsync(int i) {
		return BASE + i * P;
	}

	@Test
	public void withoutRefreshesTheDrawingTimeIsKept() {
		VsyncGrid g = new VsyncGrid();
		assertEquals(123_456_789L, g.snap(123_456_789L));
		g.onVsync(vsync(0));
		// one refresh gives no period yet
		assertEquals(vsync(0) + 3 * MS, g.snap(vsync(0) + 3 * MS));
	}

	@Test
	public void unevenDrawingStartsGiveEvenPictureTimes() {
		VsyncGrid g = new VsyncGrid();
		Random r = new Random(1);
		long previous = 0;
		int checked = 0;
		for (int i = 0; i < 600; i++) {
			long start = vsync(i) + 3 * MS + (long) ((r.nextDouble() - 0.5) * 5 * MS);
			// the main thread hears of the refresh before or after drawing starts
			boolean heardFirst = r.nextBoolean();
			if (heardFirst) {
				g.onVsync(vsync(i));
			}
			long t = g.snap(start);
			if (!heardFirst) {
				g.onVsync(vsync(i));
			}
			if (i >= 3) {
				assertEquals("picture " + i, P, t - previous);
				checked++;
			}
			previous = t;
		}
		assertEquals(597, checked);
	}

	@Test
	public void drawingThatStartsJustBeforeARefreshStaysOnItsOwnRefresh() {
		// drawing usually starts 15 ms after a refresh, sometimes after the next one has come
		VsyncGrid g = new VsyncGrid();
		Random r = new Random(2);
		long previous = 0;
		for (int i = 0; i < 600; i++) {
			g.onVsync(vsync(i));
			long start = vsync(i) + 15 * MS + (long) ((r.nextDouble() - 0.5) * 6 * MS);
			long t = g.snap(start);
			if (i >= 3) {
				assertEquals("picture " + i, P, t - previous);
			}
			previous = t;
		}
	}

	@Test
	public void aLateDrawingIsPlacedForTheRefreshThatShowsIt() {
		VsyncGrid g = new VsyncGrid();
		long[] times = new long[12];
		for (int i = 0; i < times.length; i++) {
			g.onVsync(vsync(i));
			times[i] = g.snap(vsync(i) + 2 * MS + (i == 8 ? P : 0));
		}
		assertEquals(P, times[7] - times[6]);
		assertEquals("the late one moves a refresh on", 2 * P, times[8] - times[7]);
		assertEquals("the next one shares its refresh", 0, times[9] - times[8]);
		assertEquals(P, times[10] - times[9]);
	}

	@Test
	public void missedRefreshCallbacksDoNotChangeThePeriod() {
		// a busy main thread hears only of every second or third refresh
		VsyncGrid g = new VsyncGrid();
		int[] heard = {0, 1, 3, 4, 6, 9, 10, 12, 15, 16, 18, 21};
		for (int i : heard) {
			g.onVsync(vsync(i));
		}
		assertEquals(P, g.periodNanos(), 2);
	}

	@Test
	public void afterAPauseTheGridIsLearnedAgain() {
		VsyncGrid g = new VsyncGrid();
		for (int i = 0; i < 10; i++) {
			g.onVsync(vsync(i));
			g.snap(vsync(i) + 2 * MS);
		}
		long later = vsync(9) + 1_000_000_000L;
		assertEquals("refreshes a second old are not trusted", later, g.snap(later));
		g.reset();
		assertEquals(later, g.snap(later));
		g.onVsync(later);
		assertEquals(later, g.snap(later + 4 * MS));
	}
}
