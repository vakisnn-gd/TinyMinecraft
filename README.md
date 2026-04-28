# Tiny Minecraft OpenGL

Tiny Minecraft OpenGL is a small Java/LWJGL Minecraft-like prototype. It is not a full Minecraft clone; it is an experimental voxel sandbox used to explore chunk generation, OpenGL rendering, survival mechanics, inventory UI, crafting, containers, caves, villages, mobs, and world saving.

The project is intentionally simple: most of the game lives in plain Java files in the repository root.

## Current State

This is a v0.1 prototype. It can generate and render a block world, save/load regions, place and break blocks, use a hotbar/inventory, craft items, open chests/furnaces/crafting tables, spawn mobs, and explore generated villages/caves/mineshafts.

The game is playable as a tech demo, but many systems are rough or incomplete. See [KNOWN_ISSUES.md](KNOWN_ISSUES.md) and [ROADMAP.md](ROADMAP.md).

## Requirements

- Java JDK 17 or newer
- LWJGL jars in `lib/`
- Windows is the currently tested platform

## Build

```powershell
javac -cp "lib/*" -d out *.java
```

## Run

Use the included batch file:

```powershell
.\run-opengl.bat
```

Or run manually if your LWJGL native jars are present in `lib/`:

```powershell
java -cp "out;lib/*" TinyMinecraft
```

## Controls

- `WASD` - move
- `Space` - jump
- `Shift` - sneak
- `Ctrl` - sprint
- Left click - attack / break
- Right click - interact / place
- `E` - inventory
- `Esc` - pause menu
- `/locate structure village`
- `/locate structure mineshaft`
- `/place structure list`

## What Is Implemented

- Chunked voxel world
- Terrain, caves, ores, rivers/oceans, and biome sampling
- Villages, farms, houses, mineshafts, and simple structure templates
- Basic lighting and transparent blocks
- Doors, torches, rails, farmland, crops, beds
- Inventory, creative inventory, survival inventory
- Crafting table, chest, furnace
- Furnace smelting and food
- Mobs, spawn eggs, drops, simple combat
- Hunger and health
- Region/world saving
- Debug overlay and locate commands

## Project Scope

This project is a learning prototype, not a finished game. The goal for v0.1 is to keep it compiling, runnable, and understandable, while documenting the rough edges instead of pretending they are finished.

## License

No license has been selected yet. If you want other people to use or modify this code, add a license before publishing widely.
