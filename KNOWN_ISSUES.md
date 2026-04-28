# Known Issues

This file is intentionally honest. Tiny Minecraft OpenGL is a v0.1 prototype, and these are the known rough areas.

## World Generation

- Ocean generation can produce strange coastlines, cliffs, and flooded areas.
- Water simulation is slow and visually buggy in some places.
- Biome transitions are abrupt and can look mixed or noisy.
- Caves can feel inconsistent: some areas are too chaotic, while others are too empty.
- Ore generation exists, but ores are not always easy to find in caves.
- Villages can still look too small or too frequent depending on the seed.
- Some villages may still interact badly with terrain, trees, water, or caves.

## Rendering

- The first-person hand is placeholder art.
- The player model in inventory can pose incorrectly.
- Many blocks and items use flat colors instead of real textures.
- Partial block outlines are improved but may still be wrong for some states.
- Transparency around water can look messy.

## Gameplay

- Combat is basic.
- Mob AI is simple and can look odd.
- Passive mob models are still placeholder-quality.
- Hunger is basic and does not include Minecraft-style saturation/effects.
- Beds are simplified.
- Crafting and furnace logic are functional but not polished.

## UI

- Inventory layout is functional but still rough.
- Crafting table, chest, and furnace screens need visual polish.
- There is no recipe book.
- Some debug/creative tools are developer-oriented rather than player-friendly.

## Audio And Assets

- The game does not have a complete sound set.
- The game does not have a real texture pack.
- Most visuals are generated colors/simple shapes.

## Performance

- Chunk loading is better than earlier versions but can still hitch.
- Water and large cave areas can hurt FPS.
- Mesh rebuilds and transparent rendering need more optimization.

## Save Format

- World saving works, but the format is not a stable public format.
- Future versions may break old saves.
