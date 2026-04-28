final class WaterRenderer {
    private final VoxelWorld world;
    private int cameraBlockX = Integer.MIN_VALUE;
    private int cameraBlockY = Integer.MIN_VALUE;
    private int cameraBlockZ = Integer.MIN_VALUE;

    WaterRenderer(VoxelWorld world) {
        this.world = world;
    }

    void setCameraBlock(int x, int y, int z) {
        cameraBlockX = x;
        cameraBlockY = y;
        cameraBlockZ = z;
    }

    boolean isRenderableBlockFace(byte block, int x, int y, int z, Face face) {
        int neighborX = x;
        int neighborY = y;
        int neighborZ = z;
        switch (face) {
            case WEST:
                neighborX--;
                break;
            case EAST:
                neighborX++;
                break;
            case TOP:
                neighborY++;
                break;
            case BOTTOM:
                neighborY--;
                break;
            case NORTH:
                neighborZ--;
                break;
            case SOUTH:
                neighborZ++;
                break;
            default:
                break;
        }
        byte neighbor = world.getBlock(neighborX, neighborY, neighborZ);
        if (world.isLiquidBlock(block) && world.isSolidBlock(neighbor)) {
            return false;
        }
        if (world.isFaceVisible(block, neighborX, neighborY, neighborZ)) {
            return true;
        }
        if (neighborX == cameraBlockX
            && neighborY == cameraBlockY
            && neighborZ == cameraBlockZ
            && world.isSolidBlock(neighbor)) {
            return true;
        }
        if (!world.isLiquidBlock(block) || face == Face.TOP || face == Face.BOTTOM) {
            return false;
        }
        if (GameConfig.fluidItemForBlock(neighbor) != GameConfig.fluidItemForBlock(block)) {
            return false;
        }
        return liquidRenderHeight(block, neighborX, neighborY, neighborZ) + 0.01 < liquidRenderHeight(block, x, y, z);
    }

    double liquidSideMinY(double defaultMinY, byte block, Face face, int worldX, int worldY, int worldZ) {
        int neighborX = worldX;
        int neighborZ = worldZ;
        double surfaceOffset = 0.01;
        switch (face) {
            case WEST:
                neighborX--;
                break;
            case EAST:
                neighborX++;
                break;
            case NORTH:
                neighborZ--;
                break;
            case SOUTH:
                neighborZ++;
                break;
            default:
                return defaultMinY;
        }
        if (GameConfig.fluidItemForBlock(world.getBlock(neighborX, worldY, neighborZ)) != GameConfig.fluidItemForBlock(block)) {
            return defaultMinY;
        }
        return defaultMinY + liquidRenderHeight(block, neighborX, worldY, neighborZ) - surfaceOffset;
    }

    double liquidRenderHeight(byte block, int worldX, int worldY, int worldZ) {
        return world.getFluidSurfaceHeight(worldX, worldY, worldZ);
    }
}
