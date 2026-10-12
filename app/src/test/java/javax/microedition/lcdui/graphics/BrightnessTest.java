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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Random;

public class BrightnessTest {
	/** Grey pixels of a varied picture, each changed by new = old * gain + offset. */
	private static Brightness measure(int[] grays, float gain, float offset) {
		Brightness b = new Brightness();
		for (int g : grays) {
			int v = Math.max(0, Math.min(255, Math.round(g * gain + offset)));
			b.add(0xFF000000 | (v << 16) | (v << 8) | v);
		}
		return b;
	}

	private static int[] picture() {
		Random r = new Random(5);
		int[] out = new int[4000];
		for (int i = 0; i < out.length; i++) {
			out[i] = 40 + r.nextInt(170);
		}
		return out;
	}

	private static float[] change(int[] p, float gain, float offset) {
		Brightness a = measure(p, 1, 0);
		Brightness b = measure(p, gain, offset);
		return Brightness.change(a.mean(), a.deviation(), b.mean(), b.deviation());
	}

	@Test
	public void aFadeToBlackIsAGain() {
		float[] c = change(picture(), 0.75f, 0);
		assertEquals(0.75f, c[0], 0.01f);
		assertEquals(0f, c[1], 1f);
	}

	@Test
	public void aFadeToWhiteIsAGainAndAnOffset() {
		// a quarter of the way to white
		float[] c = change(picture(), 0.75f, 0.25f * 255);
		assertEquals(0.75f, c[0], 0.01f);
		assertEquals(0.25f * 255, c[1], 1.5f);
	}

	@Test
	public void theSamePictureIsNoChange() {
		assertSame(Brightness.NONE, change(picture(), 1f, 0f));
		assertSame("a hardly visible change", Brightness.NONE, change(picture(), 1.01f, 1f));
	}

	@Test
	public void aFlatPictureOnlyChangesItsOffset() {
		int[] flat = new int[100];
		java.util.Arrays.fill(flat, 100);
		float[] c = change(flat, 1f, 30f);
		assertEquals(1f, c[0], 0f);
		assertEquals(30f, c[1], 0.5f);
	}

	@Test
	public void applyingTheChangeMatchesTheNewPicture() {
		byte[] old = {0, 50, 100, (byte) 200, (byte) 255};
		byte[] out = Brightness.apply(old, new float[]{0.5f, 10f}, null);
		assertArrayEquals(new byte[]{10, 35, 60, 110, (byte) 138}, out);
		byte[] reuse = new byte[5];
		assertSame(reuse, Brightness.apply(old, Brightness.NONE, reuse));
		assertArrayEquals(old, reuse);
	}
}
