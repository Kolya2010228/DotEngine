# DotEngine

A real-time **3D engine for Android that renders the scene as colored ASCII characters** inside a terminal-style interface. The 3D is a software triangle rasterizer (with a depth buffer), so it can render arbitrary meshes (`.obj` models) as well as a procedurally generated infinite cube world.

Package: `com.dot3d.app`. Built with Java + Gradle, no external dependencies.

## How it works

The app starts in a **functional terminal**. You type commands; unknown commands print a plausible "stub" response, but a small set of real commands control the engine:

| Command | Description |
| --- | --- |
| `run` / `start` | Launch the 3D engine |
| `grid <W> <H>` | Set the ASCII grid size (persisted) |
| `palette <name\|chars>` | Set brightness palette: `classic`, `blocks`, or a custom char ramp dark→light (persisted) |
| `seed <n>` | Set the infinite-world seed (persisted) |
| `set color on\|off` | Toggle colored output (persisted) |
| `load [file]` | Load an `.obj` model; no arg opens the system file picker |
| `ls` | List bundled demo models |
| `help` | Show commands |
| `clear` | Clear the terminal |

The terminal output is drawn manually (not a `TextView`), so **long-press never selects or copies** characters.

## Engine

- **Software 3D renderer:** triangles are projected and rasterized into a low-resolution character framebuffer (`W×H` cells) with a per-cell depth buffer. Luminance (from face normal · light) picks the ASCII glyph from the palette; color comes from the surface (terrain biome by height, or model material).
- **Rendering blit:** ASCII glyphs are pre-rasterized once into a glyph atlas; each frame is composed from the atlas on a `SurfaceView` render thread for high FPS.
- **Infinite world:** a deterministic height function (hashed value noise seeded by `seed`) generates a blocky voxel landscape in chunks around the player; only nearby visible chunks are rendered.
- **Physics:** gravity pulls the player down; jump applies an upward impulse; landing on top of a cube stops the fall. Cheap AABB collision against nearby columns.
- **Models:** `.obj` files (vertices `v`, faces `f`, optional normals `vn`) from bundled assets or picked from device storage.

## Controls

- **Left half of screen:** virtual joystick (walk forward/back/strafe), appears under your finger.
- **Right half:** drag to look (yaw/pitch) + a **jump** button.
- Multitouch: walking and looking work simultaneously.

## Pause menu

Pressing the system **Back** button while the engine runs opens an overlay pause menu (it does not exit the app). There you can tune the current session live — grid size, palette/color, look sensitivity, FOV, render distance, seed — and choose **Continue** or **Exit to terminal**.

## Build

CI: `.github/workflows/android-build.yml` sets up JDK 17 + Gradle and runs `gradle assembleDebug`, then publishes the debug APK as a GitHub Release with an auto-incremented tag (`v1.0.<run_number>`). Triggered on push to `main` and via manual `workflow_dispatch`.

See [idea.md](idea.md) for the full design notes.