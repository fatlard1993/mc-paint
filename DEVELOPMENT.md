# MC Paint - Development Guide

For what the mod is and how it plays, see [README.md](README.md).

## Source Map

| File | What is in it |
|---|---|
| `Main.java` | Registration, Pandorical content sync, and the events: hitting a painting, tracking frames, disconnects; hooks up Block Tip when it is loaded |
| `EaselBlock.java` | The two-block easel: facing any of eight ways, placing and breaking both halves, and what each click puts on it |
| `EaselBlockEntity.java` | The palette and canvas, the canvas standing on the easel, and what breaking gives back |
| `PaintScreen.java` | Painting: the canvas, palette, brushes, leaving and signing |
| `SizeScreen.java` | Choosing a canvas size as it goes on the easel |
| `Paintings.java` | Signed paintings on walls: drawing them onto maps, hanging, fitting, taking down |
| `Artworks.java` | Unfinished and signed paintings as items |
| `PaintingItem.java` | The signed painting item, hung on the wall it is used against |
| `CopyCounts.java` | Saved data on the overworld counting the copies of each painting, keyed by its first map, so every copy is numbered |
| `integration/PaintingTips.java` | Block Tip naming a hung painting by its title and painter; only loaded when block-tip is |
| `Paint.java` | Colours, which are map colours: dyes, mixing, and reading canvases saved before |
| `CanvasSize.java` | The sizes a canvas can be |
| `mixin/ItemFrameAccessor.java` | Setting an item frame fixed, which vanilla only does from saved data |
| `generate_textures.py` | Draws the item sprites and the mod icon |

## How a painting is shown

- **On the easel**, the canvas is a Pandorical picture (`PandoricalApi.pictures()`): the easel's own cells on a thin panel, standing on the tray and leaning back against the mast at whatever yaw the easel faces, sized to the canvas. It is anchored to an invisible item display in the lower half, tagged `mc-paint.easel`, sent whole to anyone who comes near and patched with the cells each stroke changes. Pandorical keeps no pictures across a restart, so the anchor loading is what stands the canvas again.
- **On a wall**, a signed painting is one map per block, drawn and locked at signing, each in an invisible, fixed item frame tagged `mc-paint.painting`. Each canvas block is 16 × 16 cells, eight map pixels to a cell, every cell a packed map colour written to the map as it is. The frames share a group id in their map's custom data, which is how hitting any one of them finds the rest.

Fixed frames cannot be turned, emptied, pushed or knocked off, and do not check their wall. A server tick every second checks each tracked painting instead, and takes down any whose wall has gone.

Easels placed before pictures showed their canvas as a map in an item frame tagged `mc-paint.easel`. When one of those frames loads it is discarded and its easel given an anchor.

## Facing

An easel faces one of eight ways, stored as `facing` plus `diagonal`, a further eighth of a turn clockwise. The diagonal models are the straight ones with every element turned 45° about the block's centre, which is why the easel is compact: a frame as wide as the block would not fit inside it turned. Kept as two properties rather than one of eight values so the easels placed before diagonals kept their state.

## Colours

Every colour is a packed map colour, 0 to 255: a cell, a palette entry and a map pixel are the same byte, so nothing is translated between painting and hanging. A dye is its map colour at full brightness; a mix is the blend of two, snapped to the nearest map colour by `Paint.mix`, and the mixer previews through the same `EaselBlockEntity.plan` that `mix` applies, so the preview is the result. The palette is paint by colour, in the order colours came.

Canvases and palettes saved before this held palette indices (0 bare canvas, a dye its id plus one) under `pixels` and `paint`; they are read and converted on load, and saved again as `cells` and `palette`.

## The painting screen

The canvas is Pandorical's `pixel_canvas` component. The client paints ahead of the server and reports each stroke; `PaintScreen` applies the same stroke to the easel with `PixelCanvas.apply` and acknowledges it. Both ends run one rule over the same cells and the same supply of paint, so they agree without the cells being sent back. A stroke the server refuses gets the easel's cells sent back to replace the client's guess.

## Installation

Install server-side alongside its declared dependencies (see `fabric.mod.json`); connecting clients need only Pandorical. Version targets live in `gradle.properties` (Minecraft, loader, Fabric API) and `fabric.mod.json` (Java).

`pixel_canvas` and `pictures()` are new in Pandorical 1.3.9, so clients need 1.3.9 or later. An older client draws the painting screen's canvas as an empty panel and is never sent the pictures.
