# NextJ2ME

NextJ2ME is a community fork of [J2ME Loader](https://github.com/nikita36078/J2ME-Loader), a J2ME (Java ME) emulator for Android. It keeps everything the original does and focuses on three things:

- **New features** that help people who play, study and debug J2ME games.
- **Performance**: faster, lighter emulation and tools that stay out of the way when they are not used.
- **Bug fixes**: problems found while using the emulator, fixed and kept covered by tests where possible.

> NextJ2ME is an independent fork. It is not affiliated with or endorsed by the authors of J2ME Loader.

## What is new in this fork

### Memory Debugger

A built-in tool to scan, watch, edit and freeze the state of a running game. J2ME Loader has no VM of its own (games are converted to DEX and run on ART), so the debugger works on the **real Java state of the game**: it never shows host pointers or Android process memory.

Turn it on in **Settings → Developer / Debugging** (off by default). Then open it from the toolbar while a game is running.

- **Scan sources:** Raw memory (a virtual big-endian address space built from the game's primitive arrays), Java objects, Primitive arrays and Static fields.
- **Value types:** int8, uint8, int16, uint16, int32, uint32, int64, uint64, float, double, boolean, string and raw bytes.
- **Scan modes:** exact value, unknown initial value, changed / unchanged, increased / decreased (optionally by an amount), equal / not equal to, and **group scan** (find several values that sit together, such as a score next to lives).
- **Any integer size (fuzzy scan):** search a whole number without knowing whether the game stores it as 8, 16, 32 or 64 bits. One pass finds it at every size it fits and lists every address together with its size; the next scans filter all sizes together until only the right one is left.
- **Scan history:** go back to the results of any earlier scan step.
- **Memory view:** List and Hex views, change the data type on the fly, and jump to an address from a scan result or a watch.
- **Edit and freeze:** write a new value, or keep a value fixed while the game runs.
- **Watch list:** keep an eye on values and open any of them in the memory view.
- **Per-game persistence:** watches, frozen values and scan settings are saved separately for each game.
- **Memory safety:** scans are capped, history is trimmed when memory runs low and the user is told when results are dropped, so the debugger does not take the game down.

### One folder per game

Everything that belongs to a game is in one folder of the work folder, split by how precious it is:

```
games/<game>/
    app/        the installed game: converted.dex, res.jar, icon (can be rebuilt)
    saves/      the game's saves: the default slot, and slots/<name>
    config/     the game's settings and key layout
    debugger/   the memory debugger's data for the game
```

Deleting a game removes this one folder. Reinstalling replaces only `app/`, so saves and settings are never touched. Backups pick the parts they need from every game. Profiles (`templates/`), the shared file system (`fs/`), `shaders/` and `cache/` stay at the top level.

### Save slots

Each game can keep several named save slots, for example one per player or one before a hard level. Long-press a game and choose **Save slots**: the slot marked ● is the one the game uses, and you can make a new slot (empty or a copy of the current saves), switch slots, duplicate, rename and delete them. The game picks its slot when it starts, so nothing is copied when you switch, and nothing can be changed while the game is open. The saves of a game live in `games/<game>/saves/`: the "Default" slot is that folder itself, and the slots you make are in its `slots` subfolder. Slots are included in backups, and "Clear data" clears only the slot in use.

### Frame generation (experimental)

For games that cap their own frame rate (for example 20-30 fps), the per-game setting **Frame generation** shows extra pictures between the frames the game draws, so the motion looks smoother on a 60 Hz screen. The game itself is not sped up or changed, only what is shown.

- **Off**: normal drawing.
- **Blend**: cross-fades between the last two game frames. Cheap.
- **Motion**: finds how each part of the picture moved between the two frames (on a half-size copy, to half a pixel) and moves it along before blending. Smoother, uses more CPU. It follows motion of up to 16 pixels per frame; for faster games use Motion HQ.
- **Motion HQ**: like Motion, but measures the motion on 8x8 pixel blocks at full size, to a quarter of a pixel, and follows faster movement (up to 32 pixels per frame). Smoothest and sharpest; uses the most CPU and GPU.

Every frame generation option has a small (i) button: tapping the option's name explains what it does and what it costs.

**Display rate** sets how many pictures per second are shown: **Screen rate**, one picture per screen refresh (the default), or 60, 90 or 120 fps. 90 and 120 fps ask Android to run the screen that fast, on phones whose screens can (otherwise it draws as fast as the screen goes). A rate that does not divide the screen's rate, such as 90 fps on a 120 Hz screen, is shown as evenly as the screen allows.

The switch **Even pacing** (off by default, all modes) places each picture for the screen refresh that shows it, instead of for the moment drawing happened to start. Drawing starts a few milliseconds after each refresh, by a different amount every time, so without it moving things advance by slightly uneven steps; with it they advance by exactly the same distance on every refresh. It also tells Android when each picture is due (Android 4.3 and newer), so a late picture is skipped instead of being shown a refresh late. On the game's side, frames are finished a few milliseconds early or late (the game's thread wakes up late, a frame takes longer to draw); with Even pacing the pictures are timed from the game's rhythm instead of each frame's exact moment, which evens that out too, for about 4 ms more delay. A real change of pace is followed at once. It costs almost no CPU; it can not fix a game that drops or holds frames.

With Motion and Motion HQ, the switch **Clean edges** (off by default) also searches the motion backward, from the new frame to the old one. Where the two directions disagree, background is being covered or uncovered by a moving object; those pixels are taken from the one frame where they can be seen, instead of being cross-faded with the object, so moving objects keep clean edges without a halo. It doubles the CPU used for the motion search.

With Motion and Motion HQ, the switch **Steady motion** (off by default) offers each block the motion it had in the previous frame as a guess, since things in games usually keep moving the same way. Keeping that motion only has to fit as well as standing still, and among motions that fit equally the closest one wins, so the motion no longer jumps between frames on flat or repeating pictures (sky, water, road markings, stripes), and steady movement is followed even beyond the search range. Where the old motion no longer fits (something turned or stopped), the normal search wins. On pictures made only of repeating patterns it can keep a wrong but equally fitting motion for a while, which shows as broken-up patches; that is why it is off by default.

With Motion and Motion HQ, the switch **GPU search** (experimental, off by default) moves the motion search from the CPU to the graphics chip: the same steps as Motion HQ's search (quarter-size coarse search, 8x8 blocks refined to a quarter pixel, smoothing), done in OpenGL ES 2.0 shaders, with the result staying on the GPU. It frees the CPU and removes the delay the CPU search adds. If a phone's GPU can not run it, the CPU searches as before.

With Motion and Motion HQ, the switch **Low latency** (off by default) removes the added delay: each game frame is shown as soon as the game draws it, and the pictures until the next frame carry it on along its motion (extrapolation) instead of leading up to it. The controls respond almost as fast as with frame generation off. The pictures between frames are a guess, so when something stops, turns or speeds up the picture goes a little too far for a moment, and the edges of moving objects can tear a little instead of looking soft. Clean edges has no effect with it.

With Motion and Motion HQ, the switch **Multi-core search** (on by default) shares the motion search between up to four cores: the same pictures, worked out two to three times sooner, which lowers the added delay and helps Motion HQ keep up. Switch it off to save battery.

It needs the graphics mode **HW acceleration (OpenGL ES)** (the default) and adds about one game frame of delay (not with Low latency). If the device cannot run it, the game falls back to normal drawing.

### 3D resolution (Mascot Capsule, experimental)

The per-game setting **3D resolution** renders the 3D scene of Mascot Capsule games at 2x or 3x the game's size and averages it down, so polygon edges are smooth instead of jagged. The game still sees a picture of its own size, so this smooths edges but does not add detail or sharpen textures. It costs fill rate: 3x draws nine times as many pixels. If the device can not allocate the larger buffer, the game falls back to the normal size. With the setting on **Normal** (the default) nothing changes.

It does not cover M3G (JSR-184) games, or games that draw their 3D with their own software code.

### Built-in shaders

The shader list (graphics mode **HW acceleration (OpenGL ES)**) has five filters that need no files in the shaders folder. Each has sliders behind the tune button next to the list.

- **Smooth upscale**: keeps the game's pixels crisp and smooths only their borders (sharp bilinear), with an optional mix of Catmull-Rom bicubic scaling. A good default for most games.
- **Pixel art (corner smoothing)**: rounds the corners of blocky pixels (Scale2x rules) before scaling. For 2D games with sprites.
- **FXAA (3D edges)**: smooths jagged polygon edges in 3D games. Best with screen filtering on.
- **Sharpen**: an unsharp mask measured in screen pixels, to counter the softness of upscaling. Strength, radius and edge overshoot.
- **Retro screen**: scan lines and a pixel grid (they fade out when the picture is scaled up less than about 2x), plus saturation and gamma.

Only one shader can be used at a time. Shaders from the shaders folder still work as before.

### Backup and restore

**Settings → Backup and restore** saves your data to a single zip file and loads it back. The file is chosen with Android's system file picker, so no storage permission is needed and it can be stored anywhere: internal storage, an SD card or a cloud-backed folder.

- **Saves and settings** (small): game saves, per-game settings, profiles, virtual keyboard layouts and debugger data.
- **Everything** (large): the above plus the installed games.
- Restoring checks the file first, asks for confirmation and overwrites files with the same names; other files are left alone. Files are written through a temporary name, so a failure never leaves a half-written file.
- A backup is a plain zip file; you can open it on a computer.

### Branding

New launcher icon (light style), app name and About screen that credit the original project.

## Roadmap

This is the direction of the project, not a list of finished work. Ideas and bug reports are welcome.

- Performance work on rendering and input handling, measured on real games before and after.
- Bug fixes for games that are reported as broken on the compatibility lists.
- More translations for the new screens.

## Building

You need Android Studio (or the Android SDK with a JDK) and the Android NDK, because the project contains native code.

```bash
./gradlew :app:assembleDevDebug
```

Run the unit tests (including the Memory Debugger tests) with:

```bash
./gradlew :app:testDevDebugUnitTest
```

## Contributing

Bug reports, feature requests and pull requests are welcome through the [issue tracker](https://github.com/ksdevla/NextJ2ME/issues). When you report a bug, please include the game name, your Android version and device, and the steps to reproduce it. For changes to the debugger, please add or update a test in `app/src/test/java/ru/playsoftware/j2meloader/debugger/`.

## Credits and license

NextJ2ME is built on the work of the J2ME Loader authors. Their copyright notices are kept in every source file, and the new code is marked `Copyright 2026 ksdevla`.

> Licensed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0). See the [LICENSE](LICENSE) file for the whole license text.

The rest of this file is the original project's README, kept for reference. Download links in it point to the original app, not to NextJ2ME.

---

# J2ME Loader (upstream project)

J2ME-Loader is a J2ME emulator for Android. It supports most 2D and 3D games (including Mascot Capsule 3D ones). Emulator has a virtual keyboard, individual settings for each application, scaling support.
This project is a fork of [J2meLoader](https://github.com/NaikSoftware/J2meLoader).  
Special thanks to [woesss](https://github.com/woesss), the author of [JL-Mod](https://github.com/woesss/JL-Mod), for creating open-source Mascot Capsule implementation.

System requirements: Android 4.0+  
[4PDA discussion](https://4pda.to/forum/index.php?showtopic=824201)  
[XDA-Developers](https://forum.xda-developers.com/android/apps-games/app-j2me-loader-t3777889)  
[EmuGen wiki](https://emulation.gametechwiki.com/index.php/J2ME_Loader)  
[Discord](https://discord.gg/Ag4rcpz)  
[Automated builds](https://install.appcenter.ms/users/nikita36078/apps/j2me-loader/distribution_groups/testers)

## Compatibility
[List of the tested Java Games (Touchscreen)](https://github.com/nikita36078/J2ME-Loader/wiki/List-of-Tested-Java-Games-(Touchscreen))  
[List of the tested Java Games (Non Touchscreen)](https://github.com/nikita36078/J2ME-Loader/wiki/List-of-Tested-Java-Games-(Non-Touchscreen))  
[List of the Java Games with Bugs](https://github.com/nikita36078/J2ME-Loader/wiki/List-of-Java-Games-with-Bugs)

## Tips
 - Enabling filtering in some cases can greatly reduce performance. Disable this option if game is too slow.
 - Image flickering issues can be fixed by enabling the "Immediate processing mode" option.

## Screenshots

<img src="/screenshots/screen.jpg" width="288" height="512"> <img src="/screenshots/screen2.jpg" width="288" height="512">
<img src="/screenshots/screen3.jpg" width="288" height="512"> <img src="/screenshots/screen4.jpg" width="288" height="512">
* For more screenshots check out the [wiki](https://emulation.gametechwiki.com/index.php/J2ME_Loader#Screenshots)

## License
> Copyright 2017-2024 Nikita Shakarun.
> Licensed under the [Apache License, Version 2.0.](http://www.apache.org/licenses/LICENSE-2.0)  
> (See the [LICENSE](https://github.com/nikita36078/J2ME-Loader/blob/master/LICENSE) file for the whole license text.)
