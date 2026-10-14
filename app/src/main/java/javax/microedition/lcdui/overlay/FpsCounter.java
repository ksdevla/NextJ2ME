/*
 * Copyright 2019 Yury Kharchenko
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
package javax.microedition.lcdui.overlay;

import android.view.View;

import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.lcdui.graphics.CanvasWrapper;

public class FpsCounter extends TimerTask implements Layer {

	/** What frame generation tells about itself, for the second line (read on the timer's thread). */
	public interface FrameGenerationInfo {
		/** How long the motion search of a game frame takes, in milliseconds; below 0 without one. */
		float searchMillis();

		/** Whether the motion is searched on the GPU (its time can not be told without waiting). */
		boolean searchesOnGpu();

		/** How long after the game finishes a frame it is fully shown, in milliseconds. */
		float delayMillis();
	}

	private final View view;
	private String prevFrameCount = "0";
	private int totalFrameCount;
	private int gameFrameCount;
	private volatile FrameGenerationInfo info;
	private volatile String[] lines;
	private final Timer timer;

	public FpsCounter(View view) {
		this.view = view;
		timer = new Timer("FpsCounter", true);
		timer.scheduleAtFixedRate(this, 0, 1000);
	}

	public void run() {
		prevFrameCount = String.valueOf(totalFrameCount);
		FrameGenerationInfo info = this.info;
		if (info != null) {
			// with frame generation: the game's own frames, then the pictures shown
			String first = gameFrameCount + " → " + totalFrameCount + " fps";
			float search = info.searchMillis();
			String second = info.searchesOnGpu() ? "search on GPU"
					: search >= 0 ? String.format(Locale.ROOT, "search %.1f ms", search) : null;
			String delay = String.format(Locale.ROOT, "delay %.0f ms", info.delayMillis());
			lines = new String[]{first, second == null ? delay : second + " · " + delay};
		} else {
			lines = null;
		}
		totalFrameCount = 0;
		gameFrameCount = 0;
		view.postInvalidate();
	}

	public void increment() {
		totalFrameCount++;
	}

	/** The game finished a frame (counted apart from the pictures shown with frame generation). */
	public void incrementGame() {
		gameFrameCount++;
	}

	/** Shows what frame generation adds: the game's frames, the motion search and the delay. */
	public void setFrameGenerationInfo(FrameGenerationInfo info) {
		this.info = info;
	}

	public void paint(CanvasWrapper g) {
		g.setFillColor(0x90000000);
		g.setTextColor(0xFF00FF00);
		String[] lines = this.lines;
		if (lines == null) {
			g.drawBackgroundedText(prevFrameCount);
			return;
		}
		for (int i = 0; i < lines.length; i++) {
			g.drawBackgroundedText(lines[i], i * g.getTextHeight());
		}
	}

	public void stop() {
		timer.cancel();
	}
}
