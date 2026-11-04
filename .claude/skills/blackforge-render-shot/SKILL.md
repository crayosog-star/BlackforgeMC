---
name: blackforge-render-shot
description: Use whenever someone wants to SEE a BlackForgeMC build, map, arena, boss, scene or ability - "what does it look like", "can I see it", "render it", "screenshot", "preview", "show me", "before and after" - or mentions gradle shots, previews, arcadePreview or a camera angle. Picks the right renderer, aims the camera, and closes the loop by looking at the PNG. Use it before claiming anything looks right.
---

# Rendering a BlackForgeMC shot

The repo can render stills of the worlds and the generated builds without a running server. Six entry points already
exist and nothing is written down about them, so the usual failure is an hour spent guessing camera numbers into the
wrong renderer. Pick the entry point first, aim second, and look at the result before judging it.

## What a render is and isn't

Renders are produced by `WorldCam` (a software renderer in `build/`), not by the Minecraft client. They are honest
about **geometry, layout, scale, silhouette and lighting mood**. They cannot show:

- any GUI: inventories, menus, scoreboards, chat, hotbar
- custom item models in a player's hand, or item textures from the resource pack
- real particles or animation - a still only, and VFX are faked with glowing boxes
- anything that only exists while the plugin is running

So a render answers "does this read at a glance, is the scale right, is the silhouette clear". It never answers "does
this work". Keep the project's honesty rule: a rendered feature is **untested**, not done.

## Where it runs

**Everything renders on the Windows machine.** A Claude cloud session cannot render at all: the Maven repos
(`repo.papermc.io`, Maven Central, JitPack) are blocked there and the container's JDK is older than the project's Java 25
toolchain, so every render task fails at the classpath - they all need `paper-api` compiled in.

That makes the cloud session's job: **choose the entry point, write or edit the shot, hand over one exact command, then
pull the PNG back and look at it.** Looking at it yourself is the point - it is much faster than asking someone to
describe what is wrong with a camera angle.

On Windows there is no Gradle wrapper, and Gradle needs `JAVA_HOME` pointed at the bundled JDK 25 - the same thing
`tools/autoupdate.py` does before it builds. Hand over PowerShell, because `cmd.exe` will not expand the version
wildcards and the versions change:

```powershell
cd plugins-src\BlackForgeCore
$env:JAVA_HOME = (Get-Item ..\..\tools\jdk-*).FullName
& (Get-Item ..\..\tools\gradle-*\bin\gradle.bat).FullName <task> <-P flags>
```

To see the result, a folder has to be connected to the session (the repo, or `C:/MinecraftNetwork`). Ask for it, then
stage the PNG and read it. If no folder can be connected, ask them to paste the image into the chat instead - don't
proceed on a description of the picture when the picture is available.

## Pick the entry point

| What you want to show | Task or call | Client jar? | Live world? |
|---|---|---|---|
| A build generated in code (spawn, lobby, factions) | `gradle preview` | no | no |
| An arcade map, isometric | `gradle arcadePreview [-Pgame=bedwars]` | no | no |
| A generated build from a chosen viewpoint | scratch main calling `PreviewMain.pov(canvas, out, x, y, z, yaw, pitch, w, h)` | no | no |
| A place in a live world, real textures | `gradle shots -Pjar=<26.3 client jar> -Pshot=<name>` | yes | yes |
| Mobs, bosses or people posed in a scene | scratch main on the `BossShot` pattern | yes | usually |
| Ability VFX: embers, sparks, glow | `Figures.embers` / `Figures.sparks` into the `Box` list | yes | usually |
| A moving shot | `gradle trailer` / `gradle premiere`, then `tools/make_*.py` | yes | yes |

Two different renderers sit behind that table, and confusing them wastes the most time:

- **`PreviewMain`** draws a `Canvas` with a flat built-in colour palette. No client jar, no world files. Truthful about
  geometry, not about texture. This is the one for anything a `*Builder` draws in code.
- **`WorldCam`** reads real block textures out of the client jar and renders loaded region files. This is the one for
  anything that should look like the game.

`WorldCam` can also render a `Canvas` via `fromCanvas`, which is how you get real textures on a build that isn't in a
world yet - worth it for a build you're about to show players, overkill for checking a layout.

## Aiming the camera

This is where the time actually goes. Four things make it quick:

**Aim at a point, not with angles.** `Cam(x, y, z, lx, ly, lz, fov)` looks from the first triple at the second. You
always know where the thing you're showing is, so this converges; `Cam.yawPitch(x, y, z, yaw, pitch, fov)` makes you
solve for an angle you can't picture. Use `yawPitch` only when you deliberately want a player's eye view.

**Start from a camera that already works.** `Shots.java` holds ~18 cameras with their looks, and the nearest one to your
subject is a better starting point than open space. Move it; don't invent one.

**Render small while you iterate.** `-Pw=640 -Ph=360 -Pss=1` returns quickly. `ss` is supersampling, so cost grows with
its square - save `1920x1080 -Pss=2` for the shot you keep.

**Find the floor instead of guessing it.** `Premiere.groundY(world, x, z, from)` returns the ground height, which is how
`BossShot` places bosses at the right height. Guessing `y` is the most common reason a render comes back as sky or
stone.

Looks available: `Look.day()`, `sunset()`, `inferno()`, `neonNight()`, `frozenNight()`. Pick for the server the shot is
of - `inferno` for hub, `frozenNight` for factions, `neonNight` for arcade - so renders match what players see.

When a picture comes back wrong, change one thing before re-rendering and say which, so the next picture is
attributable. Framing wrong is a camera problem; nothing visible usually means `y` or the load radius.

## A scratch shot

A one-off needs a main class plus a task to run it, which is the boilerplate worth not rediscovering. Register the task
next to the others in `build.gradle.kts`:

```kotlin
// scratch render, remove before shipping: gradle shot -Pjar=<26.3.jar>
tasks.register<JavaExec>("shot") {
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
    mainClass.set("net.blackforge.core.build.ScratchShot")
    maxHeapSize = "6g"
    args((project.findProperty("jar") ?: "").toString(),
         (project.findProperty("dir") ?: "previews/scratch").toString())
}
```

and the class, in `build/ScratchShot.java`:

```java
package net.blackforge.core.build;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** One-off render. Not committed: delete this and its gradle task once the picture is agreed. */
public final class ScratchShot {
    public static void main(String[] a) throws Exception {
        File jar = new File(a[0]);
        File dir = new File(a.length > 1 ? a[1] : "previews/scratch");
        dir.mkdirs();

        WorldCam wc = new WorldCam(jar);
        // the square of world to load: keep it just big enough to cover what the camera sees
        WorldCam.World w = wc.load(new File(Shots.NET, "survival/world/dimensions/minecraft/overworld/region"),
                -80, -80, 80, 80);

        int gy = Premiere.groundY(w, 0, 0, 150) + 1;   // don't guess the floor
        List<WorldCam.Box> boxes = new ArrayList<>();  // figures and VFX go here, empty is fine

        BufferedImage img = wc.render(w, WorldCam.Look.sunset(),
                new WorldCam.Cam(0.5, gy + 3, 16.5, 0.5, gy + 2, 0.5, 74),  // from, at, fov
                boxes, 640, 360, 1);                                        // small while iterating
        ImageIO.write(img, "png", new File(dir, "scratch.png"));
        System.out.println("wrote " + new File(dir, "scratch.png"));
    }
}
```

For a generated build instead of a world, drop the jar and call
`PreviewMain.pov(SomeBuilder.build(), new File(dir, "scratch.png"), x, y, z, yaw, pitch, 640, 360)`.

Adding a lasting camera to `Shots.java` is good when the view is one the team will want again - it documents the angle.
A throwaway belongs in a scratch main, so the shot list stays meaningful.

## Traps

- **Renders are not gitignored here.** `.gitignore` covers `AlloyCore/previews/live/` and `/trailer/` only, so anything
  under `BlackForgeCore/previews/` shows up in `git status`. The repo is public and these are big: never commit them.
  Point scratch renders at `previews/scratch` and leave them untracked.
- **`make_trailer.py` and `make_premiere.py` still read frames from `AlloyCore/previews/`,** while the gradle tasks write
  to `BlackForgeCore/previews/`. Rendering premiere frames and then wondering why the encode found nothing is this
  mismatch. Pass `premiere -Pdir=` the path the Python expects. Don't edit AlloyCore to fix it.
- **"Don't run Gradle locally" doesn't apply to rendering.** That rule is about `build`, `test` and `walkcheck`, which
  the pipeline owns. Render tasks have to run locally because the client jar and the world files are only there. Don't
  run `build` first to check it compiles - the render task compiles what it needs.
- **`Figures.NO_PEOPLE` is `true`.** People are switched off; flipping it changes every other render too, so pass your
  own boxes rather than toggling it.
- **`Shots.NET` is hardcoded to `C:/MinecraftNetwork`.** A different install means changing that constant, not the
  command line.
- `-Pshot=all` renders every shot, which is slow. Name the one you want.

## Reporting

Say "render", not "screenshot from the game", and name what the render can't show if it matters to the question asked -
an ability's particles, an item's texture, anything in a GUI. Then say plainly what still needs trying in game.
