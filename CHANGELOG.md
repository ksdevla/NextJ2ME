# Changelog

## NextJ2ME 1.0.0 (2026-10-12)

The first release of NextJ2ME, a community fork of [J2ME Loader](https://github.com/nikita36078/J2ME-Loader). It is based on J2ME Loader 1.8.2 plus the upstream fixes made since then, keeps everything the original does, and adds the features below.

### Highlights

- **Frame generation**: smoother motion for games that run at a low frame rate, with Blend, Motion and Motion HQ modes.
- **Built-in shaders**: Smooth upscale, Pixel art, FXAA, Sharpen and Retro screen, no files needed.
- **3D resolution**: smooth polygon edges in Mascot Capsule 3D games.
- **Memory Debugger**: scan, watch, edit and freeze values in a running game.
- **Save slots**, **backup and restore**, and **one folder per game**.
- **Privacy**: crash reports are saved on the phone only; nothing is sent anywhere.

### New

#### Frame generation (experimental)

Shows extra pictures between the frames a game draws, so games capped at 20 or 30 fps look smoother. The game itself is not changed or sped up. Per game, in the game's settings (graphics mode HW acceleration (OpenGL ES)):

- **Blend**: cross-fades the last two game frames. Cheap.
- **Motion**: follows how each part of the picture moved and moves it along before blending. Smoother and sharper.
- **Motion HQ**: measures the motion finer, to a quarter of a pixel, and follows faster movement (up to 32 pixels per frame). The smoothest.
- **Display rate**: one picture per screen refresh, or a fixed 60, 90 or 120 fps (90 and 120 fps ask the screen to run that fast on phones that support it).
- **Multi-core search** (on by default): the motion search uses up to four CPU cores, with the same result two to three times sooner.
- **Clean edges** (off by default): takes the background next to moving objects from the frame where it can be seen, for fewer halos.
- **GPU search** (experimental, off by default): searches the motion on the graphics chip instead of the CPU.
- **Steady motion** (off by default): uses the last frame's motion as a first guess, for less shimmer in slow pans.

Frame generation stops redrawing when the game shows a still picture, and falls back to normal drawing on devices that cannot run it.

#### Built-in shaders

Five filters in the shader list, each with sliders behind the tune button:

- **Smooth upscale**: crisp pixels with smooth borders, with an optional mix of bicubic scaling.
- **Pixel art (corner smoothing)**: rounds the corners of blocky pixels, for 2D sprite games.
- **FXAA (3D edges)**: smooths jagged polygon edges.
- **Sharpen**: counters the softness of scaling a small picture up.
- **Retro screen**: scan lines, pixel grid, saturation and gamma.

#### 3D resolution (Mascot Capsule)

Renders the 3D scene of Mascot Capsule games at 2x or 3x the game's size and shrinks it down, so polygon edges are smooth instead of jagged.

#### Memory Debugger

Settings → Developer / Debugging (off by default), then the toolbar button while a game runs.

- Scan the game's real Java state: raw memory, Java objects, primitive arrays and static fields.
- Types from int8 to uint64, float, double, boolean, string and raw bytes; **any integer size** searches a number at every width it fits.
- Exact, unknown, changed/unchanged, increased/decreased and **group scans** (several values found together).
- Scan history, List and Hex memory views, editing, freezing and a watch list, saved per game.
- Memory limits keep the debugger from taking the game down.

#### Your data

- **Save slots**: several named saves per game (long-press a game → Save slots). Make, switch, duplicate, rename and delete slots.
- **Backup and restore** (Settings): save your saves and settings, or everything including games, to one zip file anywhere, and load it back. No storage permission needed.

#### Games list and installer

- **Install details**: a new game opens an editable pop-up first (name, vendor, version, description and the other attributes).
- **Change name** when a game with the same name is already installed.
- Each game shows its **size** and **screen resolution** in the list.
- **Clear data** in the long-press menu deletes a game's saved data after showing how much it is.
- **Safer reinstall**: the old game is kept until the new one is in place, and restored if anything fails.

### Fixes

- Opening the app again (for example from the launcher icon) no longer closes the running game.
- The shader tuning dialog no longer crashes or mixes up its sliders after rotating the screen or coming back to the app.

### Privacy

- Crash reporting no longer sends anything. After a crash you can save a report (`crash.txt` in the app's folder) to attach to a bug report yourself.

### Good to know

- NextJ2ME does not use J2ME Loader's data: install your games again from their .jar or .jad files.
- On Android 11 and newer the app's data is in its own folder (`Android/data/com.ksdevla.nextj2me/files`), which Android deletes when the app is uninstalled. Use **Backup and restore** before uninstalling.
- Requires Android 4.0 or newer. The frame generation display rate needs Android 4.1 or newer; 90 and 120 fps need a phone whose screen supports them.

### Known limitations

- Frame generation is experimental and adds about one game frame of delay to the controls. Results depend on the game and the phone.
- **GPU search** is experimental and gives slightly softer results at fine edges than the CPU search. **Steady motion** can show broken-up patches on pictures made only of repeating patterns, which is why it is off by default.
- **3D resolution** only works for Mascot Capsule games, not for M3G games or games that draw their 3D themselves.
- The Memory Debugger may start a game class early when it reads its static fields.
- Some .jar files built with modern Java compilers fail to install with a "stack: overflow" error (a limitation of the converter inherited from J2ME Loader). Original J2ME games are not affected.

### Credits

NextJ2ME is built on [J2ME Loader](https://github.com/nikita36078/J2ME-Loader) by Nikita Shakarun and its contributors, and is released under the Apache License 2.0. It is not affiliated with or endorsed by the authors of J2ME Loader.
