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

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The sorting network the GPU finds the motion of the whole picture with really sorts. */
public class GpuSortingNetworkTest {
	@Test
	public void theNetworkSortsAnyValues() {
		String network = GpuMotionSearch.sortingNetwork(32);
		Pattern step = Pattern.compile("s2\\(v(\\d+), v(\\d+)\\)");
		Random r = new Random(3);
		for (int run = 0; run < 2000; run++) {
			int[] v = new int[32];
			for (int i = 0; i < v.length; i++) {
				v[i] = r.nextInt(run % 2 == 0 ? 256 : 5); // also many equal values
			}
			int[] sorted = v.clone();
			Arrays.sort(sorted);
			Matcher m = step.matcher(network);
			while (m.find()) {
				int a = Integer.parseInt(m.group(1));
				int b = Integer.parseInt(m.group(2));
				int lo = Math.min(v[a], v[b]);
				int hi = Math.max(v[a], v[b]);
				v[a] = lo;
				v[b] = hi;
			}
			assertArrayEquals(sorted, v);
		}
	}
}
