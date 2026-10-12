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

public class MotionCodeTest {
	private static final int EXACT = MotionCode.EXACT;
	private static final int WIDE = MotionCode.WIDE;

	@Test
	public void theExactCodeKeepsEveryMotionUpTo127() {
		for (int u = -127; u <= 127; u++) {
			assertEquals("the code is the motion itself", u, MotionCode.encode(u, EXACT));
			assertEquals(u, MotionCode.decode(MotionCode.encode(u, EXACT), EXACT));
		}
		assertEquals(127, MotionCode.encode(400, EXACT));
	}

	@Test
	public void theWideCodeKeepsMotionUpTo24PixelsExactly() {
		for (int u = -WIDE; u <= WIDE; u++) {
			assertEquals(u, MotionCode.decode(MotionCode.encode(u, WIDE), WIDE));
		}
	}

	@Test
	public void theWideCodeKeepsFasterMotionToThreeQuartersOfAPixel() {
		for (int u = WIDE + 1; u <= MotionCode.reach(WIDE); u++) {
			assertEquals("motion " + u, u, MotionCode.decode(MotionCode.encode(u, WIDE), WIDE),
					MotionCode.STEP / 2);
			assertEquals(-u, MotionCode.decode(MotionCode.encode(-u, WIDE), WIDE), MotionCode.STEP / 2);
		}
	}

	@Test
	public void theWideCodeReachesBeyond64Pixels() {
		assertTrue(MotionCode.reach(WIDE) >= 64 * 4);
		assertEquals(MotionCode.MAX, MotionCode.encode(10_000, WIDE));
		assertEquals(-MotionCode.MAX, MotionCode.encode(-10_000, WIDE));
	}

	@Test
	public void codesKeepTheOrderOfMotions() {
		for (int linear : new int[]{EXACT, WIDE}) {
			int last = MotionCode.encode(-1000, linear);
			for (int u = -999; u <= 1000; u++) {
				int c = MotionCode.encode(u, linear);
				assertTrue(c >= last);
				last = c;
			}
		}
	}
}
