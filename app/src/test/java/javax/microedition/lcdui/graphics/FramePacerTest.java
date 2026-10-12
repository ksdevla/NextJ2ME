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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FramePacerTest {
	private static final long MS = 1_000_000L;

	@Test
	public void beforeAnyFrameNothingIsShown() {
		FramePacer p = new FramePacer(true);
		assertEquals(0, p.update(5 * MS));
		assertEquals(1f, p.blend(), 0f);
	}

	@Test
	public void theFirstFrameIsShownAsItIs() {
		FramePacer p = new FramePacer(false);
		p.onFrame(1000 * MS, false);
		assertEquals(1, p.update(1000 * MS));
	}

	@Test
	public void crossFadingBlendsEachFrameInOverOneFrameTime() {
		FramePacer p = new FramePacer(false);
		p.onFrame(1000 * MS, false);
		p.onFrame(1050 * MS, false); // a 50 ms game
		assertEquals(2, p.update(1050 * MS));
		assertEquals(0f, p.blend(), 0f);
		p.update(1075 * MS);
		assertEquals(0.5f, p.blend(), 0.01f);
		p.update(1100 * MS);
		assertEquals(1f, p.blend(), 0.01f);
		p.update(1500 * MS);
		assertEquals("it stays on the newest frame while the game waits", 1f, p.blend(), 0f);
		assertEquals(0, p.delayNanos());
	}

	/** A 50 ms game whose motion always takes 10 ms. */
	private static FramePacer steadyMotionGame(int frames) {
		FramePacer p = new FramePacer(true);
		for (int i = 0; i < frames; i++) {
			long t = (1000 + 50 * i) * MS;
			int n = p.onFrame(t, true);
			p.update(t);
			p.onMotionReady(n, t + 10 * MS);
		}
		return p;
	}

	@Test
	public void theDisplayRunsAsFarBehindAsTheMotionTakes() {
		FramePacer p = steadyMotionGame(10);
		long delay = p.delayNanos();
		assertTrue("delay " + delay, delay >= 10 * MS && delay <= 14 * MS);
	}

	@Test
	public void aBlendRunsAtAnEvenSpeedOverAWholeFrameTime() {
		FramePacer p = steadyMotionGame(10);
		long delay = p.delayNanos();
		long arrival = (1000 + 50 * 10) * MS;
		int n = p.onFrame(arrival, true);
		p.onMotionReady(n, arrival + 10 * MS);
		// until its turn the previous blend finishes
		assertEquals(n - 1, p.update(arrival + delay - MS));
		// then it starts from the start and moves evenly, taking the whole frame time
		assertEquals(n, p.update(arrival + delay));
		assertEquals(0f, p.blend(), 0.01f);
		p.update(arrival + delay + 25 * MS);
		assertEquals(0.5f, p.blend(), 0.02f);
		p.update(arrival + delay + 50 * MS);
		assertEquals(1f, p.blend(), 0.02f);
	}

	@Test
	public void thePreviousBlendReachesItsEndWhenTheNextOneTakesOver() {
		FramePacer p = steadyMotionGame(10);
		long delay = p.delayNanos();
		long arrival = (1000 + 50 * 10) * MS;
		int n = p.onFrame(arrival, true);
		p.onMotionReady(n, arrival + 10 * MS);
		assertEquals(n - 1, p.update(arrival + delay - 1));
		assertEquals("no jump at the hand-over", 1f, p.blend(), 0.02f);
	}

	@Test
	public void lateMotionIsWaitedForAtTheEndOfTheBlend() {
		FramePacer p = steadyMotionGame(10);
		long delay = p.delayNanos();
		long arrival = (1000 + 50 * 10) * MS;
		int n = p.onFrame(arrival, true); // this time the motion is late
		assertEquals(n - 1, p.update(arrival + delay + 5 * MS));
		assertEquals("holding the last picture of the previous blend", 1f, p.blend(), 0f);
		p.onMotionReady(n, arrival + delay + 8 * MS);
		assertEquals(n, p.update(arrival + delay + 8 * MS));
	}

	@Test
	public void motionThatNeverComesIsNotWaitedForMoreThanHalfAFrame() {
		FramePacer p = steadyMotionGame(10);
		long arrival = (1000 + 50 * 10) * MS;
		int n = p.onFrame(arrival, true);
		assertEquals(n - 1, p.update(arrival + 20 * MS));
		assertEquals(n, p.update(arrival + 25 * MS));
	}

	@Test
	public void aFrameWithoutAMotionJobIsNotWaitedFor() {
		FramePacer p = steadyMotionGame(10);
		long delay = p.delayNanos();
		long arrival = (1000 + 50 * 10) * MS;
		int n = p.onFrame(arrival, false); // the worker was still busy
		assertEquals(n, p.update(arrival + delay));
	}

	@Test
	public void theDelayIsLimitedToMostOfAFrame() {
		FramePacer p = new FramePacer(true);
		for (int i = 0; i < 10; i++) {
			long t = (1000 + 50 * i) * MS;
			int n = p.onFrame(t, true);
			p.onMotionReady(n, t + 200 * MS); // absurdly slow motion
		}
		assertTrue(p.delayNanos() <= 50 * MS * 3 / 4);
	}

	@Test
	public void aDisplayFarBehindCatchesUpSoNoKeptFrameIsReplacedWhileShown() {
		FramePacer p = steadyMotionGame(5);
		long t = 2000 * MS;
		// three frames in a burst, without a screen refresh in between
		p.onFrame(t, true);
		p.onFrame(t + MS, true);
		int n = p.onFrame(t + 2 * MS, true);
		int shown = p.update(t + 2 * MS);
		assertTrue("shown " + shown + " newest " + n, shown >= n - 1 && shown <= n);
	}

	@Test
	public void lateNewsAboutAnOldFrameIsIgnored() {
		FramePacer p = steadyMotionGame(10);
		long before = p.delayNanos();
		p.onMotionReady(1, 99_999 * MS);
		p.onMotionReady(1000, 99_999 * MS);
		assertEquals(before, p.delayNanos());
	}

	/** A 50 ms game for low latency (nothing waits for the motion). */
	private static FramePacer aheadGame(int frames) {
		FramePacer p = new FramePacer(false);
		for (int i = 0; i < frames; i++) {
			long t = (1000 + 50 * i) * MS;
			p.onFrame(t, true);
			p.updateAhead(t);
		}
		return p;
	}

	@Test
	public void lowLatencyShowsANewFrameTheMomentItComes() {
		FramePacer p = aheadGame(10);
		long arrival = (1000 + 50 * 10) * MS;
		int n = p.onFrame(arrival, true);
		assertEquals(n, p.updateAhead(arrival));
		assertEquals("the frame as it is", 1f, p.blend(), 0f);
		assertEquals("nothing to wait for", 0, p.delayNanos());
	}

	@Test
	public void lowLatencyCarriesTheNewestFrameOnEvenlyUpToOneFrame() {
		FramePacer p = aheadGame(10);
		long arrival = (1000 + 50 * 9) * MS;
		p.updateAhead(arrival + 25 * MS);
		assertEquals(1.5f, p.blend(), 0.02f);
		p.updateAhead(arrival + 50 * MS);
		assertEquals(2f, p.blend(), 0.02f);
		p.updateAhead(arrival + 80 * MS);
		assertEquals("a late frame: no further than one frame on", 2f, p.blend(), 0f);
	}

	@Test
	public void lowLatencyShowsAStoppedGameAsItIs() {
		FramePacer p = aheadGame(10);
		long arrival = (1000 + 50 * 9) * MS;
		p.updateAhead(arrival + 101 * MS);
		assertEquals("two frame times without a frame", 1f, p.blend(), 0f);
		p.updateAhead(arrival + 5000 * MS);
		assertEquals(1f, p.blend(), 0f);
	}

	@Test
	public void lowLatencyOfASlowGameStopsCarryingOnBeforeTheScreenStopsRedrawing() {
		// a 200 ms game: two frame times are longer than the screen keeps redrawing (300 ms)
		FramePacer p = new FramePacer(false);
		for (int i = 0; i < 6; i++) {
			p.onFrame((1000 + 200 * i) * MS, true);
		}
		long arrival = (1000 + 200 * 5) * MS;
		p.updateAhead(arrival + 100 * MS);
		assertEquals(1.5f, p.blend(), 0.02f);
		p.updateAhead(arrival + 260 * MS);
		assertEquals(1f, p.blend(), 0f);
	}

	/**
	 * How unevenly the picture moves: a 50 ms game whose frames come up to {@code jitterMs} early or
	 * late, shown at 60 Hz. The position (frame number plus blend) should grow by the same amount on
	 * every refresh; returns the standard deviation of that step, in frames.
	 */
	private static double stepSpread(boolean even, double jitterMs) {
		FramePacer p = new FramePacer(false, even);
		java.util.Random r = new java.util.Random(7);
		double refresh = 1e9 / 60;
		long nextFrame = 1000 * MS;
		int sent = 0;
		double last = Double.NaN;
		double sum = 0, sumSq = 0;
		int n = 0;
		for (int i = 0; i < 600; i++) {
			long now = 1000 * MS + Math.round(i * refresh);
			while (nextFrame <= now) {
				p.onFrame(nextFrame, false);
				sent++;
				nextFrame = 1000 * MS + (sent * 50 + Math.round((r.nextDouble() * 2 - 1) * jitterMs)) * MS;
			}
			int k = p.update(now);
			double position = k - 1 + p.blend();
			if (i > 60 && !Double.isNaN(last)) {
				double step = position - last;
				sum += step;
				sumSq += step * step;
				n++;
			}
			last = position;
		}
		double mean = sum / n;
		return Math.sqrt(sumSq / n - mean * mean);
	}

	@Test
	public void evenArrivalsSmoothOutAGameThatIsAFewMillisecondsOff() {
		double plain = stepSpread(false, 4);
		double even = stepSpread(true, 4);
		assertTrue("plain " + plain, plain > 0.03);
		assertTrue("even " + even + ", plain " + plain, even < plain / 4);
		plain = stepSpread(false, 8);
		even = stepSpread(true, 8);
		assertTrue("even " + even + ", plain " + plain, even < plain / 2);
	}

	@Test
	public void evenArrivalsChangeNothingForAGameThatIsOnTime() {
		assertTrue(stepSpread(true, 0) < 0.01);
		assertTrue(stepSpread(false, 0) < 0.01);
	}

	@Test
	public void evenArrivalsFollowAChangeOfPaceAtOnce() {
		FramePacer p = new FramePacer(false, true);
		long t = 1000 * MS;
		for (int i = 0; i < 20; i++) {
			p.onFrame(t, false);
			t += 50 * MS;
		}
		// the game now runs at 33 ms a frame: far off the rhythm, which starts again
		t -= 50 * MS;
		for (int i = 0; i < 3; i++) {
			t += 33 * MS;
			int n = p.onFrame(t, false);
			assertEquals(n, p.update(t + p.delayNanos() + 40 * MS));
		}
		p.update(t + p.delayNanos());
		assertEquals("the newest frame's blend starts from its own arrival", 0f, p.blend(), 0.02f);
	}

	@Test
	public void evenArrivalsTakeUpANewFrameTimeAsQuicklyAsWithout() {
		FramePacer even = new FramePacer(false, true);
		FramePacer plain = new FramePacer(false, false);
		long t = 1000 * MS;
		for (int i = 0; i < 30; i++) {
			even.onFrame(t, false);
			plain.onFrame(t, false);
			t += 50 * MS;
		}
		for (int i = 0; i < 6; i++) {
			t += 33 * MS;
			even.onFrame(t, false);
			plain.onFrame(t, false);
		}
		assertTrue("even " + even.intervalNanos() + ", plain " + plain.intervalNanos(),
				even.intervalNanos() <= plain.intervalNanos() + MS);
	}

	@Test
	public void evenArrivalsWaitALittleForALateFrame() {
		FramePacer p = new FramePacer(false, true);
		assertTrue(p.delayNanos() > 0);
		assertEquals(0, new FramePacer(false, false).delayNanos());
	}

	@Test
	public void theNewestFrameShownAsItIsIsSettled() {
		FramePacer p = new FramePacer(false);
		p.onFrame(1000 * MS, false);
		p.onFrame(1050 * MS, false);
		p.update(1075 * MS);
		assertTrue("half way through the blend", !p.isSettled());
		p.update(1100 * MS);
		assertTrue(p.isSettled());
	}

	@Test
	public void lowLatencyIsSettledOnlyOnceItStopsCarryingOn() {
		FramePacer p = aheadGame(10);
		long arrival = (1000 + 50 * 9) * MS;
		p.updateAhead(arrival + 25 * MS);
		assertTrue(!p.isSettled());
		p.updateAhead(arrival + 101 * MS);
		assertTrue(p.isSettled());
	}

	@Test
	public void lowLatencyBeforeAnyFrameShowsNothing() {
		FramePacer p = new FramePacer(false);
		assertEquals(0, p.updateAhead(5 * MS));
		assertEquals(1f, p.blend(), 0f);
	}
}
