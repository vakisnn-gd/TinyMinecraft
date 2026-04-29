import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import javax.imageio.ImageIO;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_COLOR_ARRAY;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_FOG;
import static org.lwjgl.opengl.GL11.GL_FOG_COLOR;
import static org.lwjgl.opengl.GL11.GL_FOG_END;
import static org.lwjgl.opengl.GL11.GL_FOG_MODE;
import static org.lwjgl.opengl.GL11.GL_FOG_START;
import static org.lwjgl.opengl.GL11.GL_LEQUAL;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINES;
import static org.lwjgl.opengl.GL11.GL_LINE_LOOP;
import static org.lwjgl.opengl.GL11.GL_MODELVIEW;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_PROJECTION;
import static org.lwjgl.opengl.GL11.GL_QUADS;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SMOOTH;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_VERTEX_ARRAY;
import static org.lwjgl.opengl.GL11.glBegin;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glColor3f;
import static org.lwjgl.opengl.GL11.glColorPointer;
import static org.lwjgl.opengl.GL11.glColor4f;
import static org.lwjgl.opengl.GL11.glDepthFunc;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDisableClientState;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glEnableClientState;
import static org.lwjgl.opengl.GL11.glEnd;
import static org.lwjgl.opengl.GL11.glFrustum;
import static org.lwjgl.opengl.GL11.glFogf;
import static org.lwjgl.opengl.GL11.glFogfv;
import static org.lwjgl.opengl.GL11.glFogi;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glLineWidth;
import static org.lwjgl.opengl.GL11.glLoadIdentity;
import static org.lwjgl.opengl.GL11.glMatrixMode;
import static org.lwjgl.opengl.GL11.glOrtho;
import static org.lwjgl.opengl.GL11.glPopMatrix;
import static org.lwjgl.opengl.GL11.glPushMatrix;
import static org.lwjgl.opengl.GL11.glRotatef;
import static org.lwjgl.opengl.GL11.glRotated;
import static org.lwjgl.opengl.GL11.glScalef;
import static org.lwjgl.opengl.GL11.glShadeModel;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glTranslated;
import static org.lwjgl.opengl.GL11.glVertex3d;
import static org.lwjgl.opengl.GL11.glVertexPointer;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.stb.STBEasyFont.stb_easy_font_print;
import static org.lwjgl.stb.STBEasyFont.stb_easy_font_width;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;

final class OpenGlRenderer {
    private static final int FLOATS_PER_VERTEX = 7;
    private static final int BYTES_PER_VERTEX = FLOATS_PER_VERTEX * Float.BYTES;
    private static final int MIN_CHUNK_UPLOADS_PER_FRAME = 2;
    private static final int MAX_CHUNK_UPLOADS_PER_FRAME = 10;
    private static final long MIN_MESH_BUILD_BUDGET_NS = 3_000_000L;
    private static final long MAX_MESH_BUILD_BUDGET_NS = 12_000_000L;
    private static final double LIQUID_SURFACE_Z_OFFSET = 0.01;
    private static final double CAMERA_NEAR_PLANE = 0.08;
    private static final double CAMERA_FAR_PADDING = 64.0;
    private static final int ATLAS_TILE_SIZE = 16;
    private static final int ATLAS_COLUMNS = 4;
    private static final int ATLAS_ROWS = 4;

    private final VoxelWorld world;
    private final SkyRenderer skyRenderer;
    private final WaterRenderer waterRenderer;
    private final UiRenderer uiRenderer;
    private final HashMap<Long, ChunkMesh> chunkMeshes = new HashMap<>();
    private final HashSet<Long> dirtyChunkMeshes = new HashSet<>();
    private final ArrayDeque<FloatArrayBuilder> meshBuilderPool = new ArrayDeque<>();
    private final ArrayList<ChunkMesh> transparentChunkPass = new ArrayList<>();
    private final ArrayList<Long> staleMeshKeys = new ArrayList<>();
    private final ArrayList<Chunk> loadedChunkSnapshot = new ArrayList<>();
    private final PriorityQueue<MeshBuildCandidate> meshBuildQueue = new PriorityQueue<>();
    private final ByteBuffer textBuffer = BufferUtils.createByteBuffer(64 * 1024);

    private int framebufferWidth = GameConfig.WINDOW_WIDTH;
    private int framebufferHeight = GameConfig.WINDOW_HEIGHT;
    private int terrainTextureId;
    private int uploadProbeVboId;
    private double currentFovDegrees = GameConfig.FOV_DEGREES;
    private int currentRenderDistanceChunks = GameConfig.CHUNK_RENDER_DISTANCE;
    private float currentDaylight = 1.0f;
    private float currentSceneBrightness = 1.0f;
    private boolean resourcesReady;
    private boolean vaoSupported;
    private double debugFps;
    private double fpsSampleTime;
    private int fpsSampleFrames;
    private double renderCameraX;
    private double renderCameraY;
    private double renderCameraZ;
    private int cameraBlockX;
    private int cameraBlockY;
    private int cameraBlockZ;
    private int lastCameraSolidBlockX = Integer.MIN_VALUE;
    private int lastCameraSolidBlockY = Integer.MIN_VALUE;
    private int lastCameraSolidBlockZ = Integer.MIN_VALUE;
    private boolean lastCameraInsideSolidBlock;
    private boolean spectatorInsideBlock;
    private boolean lastSpectatorInsideBlock;
    private long lastMeshProfileLogNanos;
    private double frustumForwardX;
    private double frustumForwardY;
    private double frustumForwardZ;
    private double frustumRightX;
    private double frustumRightZ;
    private double frustumUpX;
    private double frustumUpY;
    private double frustumUpZ;
    private double menuPanoramaYaw;

    OpenGlRenderer(VoxelWorld world) {
        this.world = world;
        this.skyRenderer = new SkyRenderer();
        this.waterRenderer = new WaterRenderer(world);
        this.uiRenderer = new UiRenderer();
    }

    void init() {
        GL.createCapabilities();
        verifyOpenGl("GL.createCapabilities");
        vaoSupported = GL.getCapabilities().OpenGL30 || GL.getCapabilities().GL_ARB_vertex_array_object;
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glClearDepth(1.0);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glShadeModel(GL_SMOOTH);
        glClearColor(0.53f, 0.72f, 0.94f, 1.0f);
        verifyOpenGl("OpenGL state init");

        terrainTextureId = loadTerrainTexture();
        uploadProbeVboId = createUploadProbeVbo();
        resourcesReady = terrainTextureId != 0 && uploadProbeVboId != 0;
        if (!resourcesReady) {
            throw new IllegalStateException("Renderer resources were not created.");
        }

        verifyOpenGl("chunk mesh init");
    }

    void updateFramebufferSize(long window) {
        try (MemoryStack stack = stackPush()) {
            var widthBuffer = stack.mallocInt(1);
            var heightBuffer = stack.mallocInt(1);
            glfwGetFramebufferSize(window, widthBuffer, heightBuffer);
            framebufferWidth = Math.max(1, widthBuffer.get(0));
            framebufferHeight = Math.max(1, heightBuffer.get(0));
        }
    }

    int getPauseOptionAt(double cursorX, double cursorY) {
        float uiScale = Math.max(1.0f, getUiScale());
        float wideWidth = 398.0f * uiScale;
        float buttonHeight = 38.0f * uiScale;
        float centerX = framebufferWidth * 0.5f;
        float y = 142.0f * uiScale;
        for (int i = 0; i < GameConfig.PAUSE_OPTIONS.length; i++) {
            float buttonY = y + i * 52.0f * uiScale;
            if (cursorX >= centerX - wideWidth * 0.5f && cursorX <= centerX + wideWidth * 0.5f
                && cursorY >= buttonY && cursorY <= buttonY + buttonHeight) {
                return i;
            }
        }
        return -1;
    }

    int getOptionsSliderAt(double cursorX, double cursorY, int menuScreen) {
        if (menuScreen != GameConfig.MENU_SCREEN_OPTIONS) {
            return -1;
        }
        float uiScale = Math.max(1.0f, getUiScale());
        if (isPointInsideSettingsSlider(cursorX, cursorY, optionsRenderDistanceSliderY(uiScale), uiScale)) {
            return 0;
        }
        if (isPointInsideSettingsSlider(cursorX, cursorY, optionsFovSliderY(uiScale), uiScale)) {
            return 1;
        }
        if (isPointInsideSettingsSlider(cursorX, cursorY, optionsInventoryUiSliderY(uiScale), uiScale)) {
            return 2;
        }
        return -1;
    }

    double sliderPercentAt(double cursorX) {
        float uiScale = Math.max(1.0f, getUiScale());
        float sliderWidth = 320.0f * uiScale;
        float sliderX = framebufferWidth * 0.5f - sliderWidth * 0.5f;
        return clamp((cursorX - sliderX) / sliderWidth, 0.0, 1.0);
    }

    private boolean isPointInsideSettingsSlider(double cursorX, double cursorY, float y, float uiScale) {
        float sliderWidth = 320.0f * uiScale;
        float sliderX = framebufferWidth * 0.5f - sliderWidth * 0.5f;
        return cursorX >= sliderX - 10.0f * uiScale
            && cursorX <= sliderX + sliderWidth + 10.0f * uiScale
            && cursorY >= y - 16.0f * uiScale
            && cursorY <= y + 22.0f * uiScale;
    }

    private float optionsRenderDistanceSliderY(float uiScale) {
        return framebufferHeight * 0.5f - 54.0f * uiScale;
    }

    private float optionsFovSliderY(float uiScale) {
        return framebufferHeight * 0.5f + 18.0f * uiScale;
    }

    private float optionsInventoryUiSliderY(float uiScale) {
        return framebufferHeight * 0.5f + 90.0f * uiScale;
    }

    private float optionsLanguageButtonY(float uiScale) {
        return framebufferHeight * 0.5f + 150.0f * uiScale;
    }

    private float optionsBackButtonY(float uiScale) {
        return framebufferHeight * 0.5f + 214.0f * uiScale;
    }

    int getDeathOptionAt(double cursorX, double cursorY) {
        return uiRenderer.getDeathOptionAt(cursorX, cursorY, framebufferWidth, Math.max(1.0f, getUiScale()));
    }

    int getMainMenuOptionAt(double cursorX, double cursorY, int menuScreen) {
        float uiScale = Math.max(1.0f, getUiScale());
        if (menuScreen == GameConfig.MENU_SCREEN_OPTIONS) {
            float actionWidth = 280.0f * uiScale;
            float actionHeight = 46.0f * uiScale;
            float x = framebufferWidth * 0.5f - actionWidth * 0.5f;
            float languageY = optionsLanguageButtonY(uiScale);
            if (cursorX >= x && cursorX <= x + actionWidth && cursorY >= languageY && cursorY <= languageY + actionHeight) {
                return 3;
            }
            float y = optionsBackButtonY(uiScale);
            return cursorX >= x && cursorX <= x + actionWidth && cursorY >= y && cursorY <= y + actionHeight ? 4 : -1;
        }
        String[] actions = menuScreen == GameConfig.MENU_SCREEN_SINGLEPLAYER ? GameConfig.SINGLEPLAYER_ACTIONS
            : (menuScreen == GameConfig.MENU_SCREEN_CREATE_WORLD ? GameConfig.CREATE_WORLD_ACTIONS
            : (menuScreen == GameConfig.MENU_SCREEN_RENAME_WORLD ? GameConfig.RENAME_WORLD_ACTIONS
            : GameConfig.WORLD_MENU_ACTIONS));
        float actionWidth = menuScreen == GameConfig.MENU_SCREEN_SINGLEPLAYER ? 118.0f * uiScale
            : (menuScreen == GameConfig.MENU_SCREEN_CREATE_WORLD || menuScreen == GameConfig.MENU_SCREEN_RENAME_WORLD ? 240.0f * uiScale : 280.0f * uiScale);
        float actionHeight = menuScreen == GameConfig.MENU_SCREEN_SINGLEPLAYER ? 38.0f * uiScale : 46.0f * uiScale;
        float gap = menuScreen == GameConfig.MENU_SCREEN_SINGLEPLAYER ? 10.0f * uiScale : 12.0f * uiScale;
        boolean horizontal = menuScreen == GameConfig.MENU_SCREEN_SINGLEPLAYER || menuScreen == GameConfig.MENU_SCREEN_CREATE_WORLD || menuScreen == GameConfig.MENU_SCREEN_RENAME_WORLD;
        float actionsX = horizontal
            ? framebufferWidth * 0.5f - (actionWidth * actions.length + gap * (actions.length - 1)) * 0.5f
            : framebufferWidth * 0.5f - actionWidth * 0.5f;
        float firstY = menuScreen == GameConfig.MENU_SCREEN_SINGLEPLAYER ? framebufferHeight - 58.0f * uiScale
            : (menuScreen == GameConfig.MENU_SCREEN_CREATE_WORLD || menuScreen == GameConfig.MENU_SCREEN_RENAME_WORLD ? framebufferHeight - 78.0f * uiScale : framebufferHeight * 0.5f - 18.0f * uiScale);
        for (int i = 0; i < actions.length; i++) {
            float boxX = horizontal ? actionsX + i * (actionWidth + gap) : actionsX;
            float boxY = horizontal ? firstY : firstY + i * 58.0f * uiScale;
            if (cursorX >= boxX && cursorX <= boxX + actionWidth && cursorY >= boxY && cursorY <= boxY + actionHeight) {
                return i;
            }
        }
        return -1;
    }

    int getMainMenuWorldAt(double cursorX, double cursorY, int menuScreen, int worldCount, int scrollOffset) {
        if (menuScreen != GameConfig.MENU_SCREEN_SINGLEPLAYER) {
            return -1;
        }
        float uiScale = Math.max(1.0f, getUiScale());
        float panelWidth = framebufferWidth - 160.0f * uiScale;
        float panelX = framebufferWidth * 0.5f - panelWidth * 0.5f;
        float listX = panelX + 28.0f * uiScale;
        float listY = 96.0f * uiScale;
        float listWidth = panelWidth - 56.0f * uiScale;
        float rowHeight = 36.0f * uiScale;
        int visibleRows = Math.min(GameConfig.WORLD_MENU_VISIBLE_ROWS, Math.max(0, worldCount - scrollOffset));
        for (int row = 0; row < visibleRows; row++) {
            float rowY = listY + row * (rowHeight + 8.0f * uiScale);
            if (cursorX >= listX && cursorX <= listX + listWidth && cursorY >= rowY && cursorY <= rowY + rowHeight) {
                return scrollOffset + row;
            }
        }
        return -1;
    }

    InventorySlotRef getInventorySlotAt(PlayerInventory inventory, boolean creativeMode, int creativeTab, int inventoryScreenMode, double mouseX, double mouseY) {
        UiRenderer.InventoryUiLayout layout = buildInventoryLayout(creativeMode, creativeTab, inventoryScreenMode);
        UiRenderer.SlotBox slot = layout.hitTest(mouseX, mouseY);
        if (slot == null) {
            return null;
        }
        if (slot.ref.group == InventorySlotGroup.CREATIVE && slot.ref.index >= InventoryItems.CREATIVE_ITEMS.length) {
            return null;
        }
        return slot.ref;
    }

    boolean isInventoryPointInside(boolean creativeMode, int creativeTab, int inventoryScreenMode, double mouseX, double mouseY) {
        return buildInventoryLayout(creativeMode, creativeTab, inventoryScreenMode).contains(mouseX, mouseY);
    }

    int getCreativeTabAt(double mouseX, double mouseY, int creativeTab) {
        UiRenderer.InventoryUiLayout layout = buildInventoryLayout(true, creativeTab);
        float uiScale = getInventoryUiScale();
        float gap = 4.0f * uiScale;
        float tabWidth = Math.min(68.0f * uiScale,
            (layout.panelWidth - 32.0f * uiScale - gap * (GameConfig.CREATIVE_TABS.length - 1)) / GameConfig.CREATIVE_TABS.length);
        float tabHeight = 22.0f * uiScale;
        float totalWidth = tabWidth * GameConfig.CREATIVE_TABS.length + gap * (GameConfig.CREATIVE_TABS.length - 1);
        float startX = layout.panelX + layout.panelWidth * 0.5f - totalWidth * 0.5f;
        float y = layout.panelY + 10.0f * uiScale;
        for (int i = 0; i < GameConfig.CREATIVE_TABS.length; i++) {
            float x = startX + i * (tabWidth + gap);
            if (mouseX >= x && mouseX <= x + tabWidth && mouseY >= y && mouseY <= y + tabHeight) {
                return i;
            }
        }
        return -1;
    }

    int getCreateWorldFieldAt(double mouseX, double mouseY) {
        float uiScale = Math.max(1.0f, getUiScale());
        float panelWidth = Math.min(720.0f * uiScale, framebufferWidth - 120.0f * uiScale);
        float x = framebufferWidth * 0.5f - panelWidth * 0.5f;
        float nameY = 126.0f * uiScale;
        float seedY = 330.0f * uiScale;
        float fieldHeight = 36.0f * uiScale;
        if (mouseX >= x && mouseX <= x + panelWidth && mouseY >= nameY && mouseY <= nameY + fieldHeight) {
            return 0;
        }
        if (mouseX >= x && mouseX <= x + panelWidth && mouseY >= seedY && mouseY <= seedY + fieldHeight) {
            return 1;
        }
        return -1;
    }

    int getCreateWorldToggleAt(double mouseX, double mouseY) {
        float uiScale = Math.max(1.0f, getUiScale());
        float buttonWidth = 340.0f * uiScale;
        float buttonHeight = 44.0f * uiScale;
        float gap = 18.0f * uiScale;
        float startX = framebufferWidth * 0.5f - (buttonWidth * 2.0f + gap) * 0.5f;
        float y = 224.0f * uiScale;
        for (int i = 0; i < 2; i++) {
            float x = startX + i * (buttonWidth + gap);
            if (mouseX >= x && mouseX <= x + buttonWidth && mouseY >= y && mouseY <= y + buttonHeight) {
                return i;
            }
        }
        return -1;
    }

    int getRenameWorldFieldAt(double mouseX, double mouseY) {
        float uiScale = Math.max(1.0f, getUiScale());
        float panelWidth = Math.min(720.0f * uiScale, framebufferWidth - 120.0f * uiScale);
        float x = framebufferWidth * 0.5f - panelWidth * 0.5f;
        float y = 246.0f * uiScale;
        float fieldHeight = 36.0f * uiScale;
        return mouseX >= x && mouseX <= x + panelWidth && mouseY >= y && mouseY <= y + fieldHeight ? 0 : -1;
    }

    void renderLoadingScreen(String message, String detail) {
        if (!resourcesReady || framebufferWidth <= 0 || framebufferHeight <= 0) {
            return;
        }
        glViewport(0, 0, framebufferWidth, framebufferHeight);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_TEXTURE_2D);
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0.0, framebufferWidth, framebufferHeight, 0.0, -1.0, 1.0);
        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();

        drawRect(0.0f, 0.0f, framebufferWidth, framebufferHeight, 0.02f, 0.025f, 0.03f, 1.0f);
        float uiScale = Math.max(1.0f, getUiScale());
        drawCenteredShadowText(framebufferHeight * 0.45f, uiScale * 1.15f, message, 0.96f, 0.96f, 0.96f);
        if (detail != null && !detail.isEmpty()) {
            drawCenteredShadowText(framebufferHeight * 0.45f + 34.0f * uiScale, uiScale * 0.78f, detail, 0.72f, 0.74f, 0.78f);
        }

        glPopMatrix();
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
        glEnable(GL_DEPTH_TEST);
    }

    void buildAllChunkMeshes() {
        markAllChunksDirty();
    }

    void clearWorldMeshes() {
        for (ChunkMesh mesh : chunkMeshes.values()) {
            unloadChunkMesh(mesh);
        }
        chunkMeshes.clear();
        dirtyChunkMeshes.clear();
        meshBuildQueue.clear();
        staleMeshKeys.clear();
        transparentChunkPass.clear();
    }

    void rebuildChunksAroundBlock(int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, GameConfig.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(blockZ, GameConfig.CHUNK_SIZE);
        markChunkColumnDirty(chunkX, chunkZ);

        if (Math.floorMod(blockX, GameConfig.CHUNK_SIZE) == 0) {
            markChunkColumnDirty(chunkX - 1, chunkZ);
        }
        if (Math.floorMod(blockX, GameConfig.CHUNK_SIZE) == GameConfig.CHUNK_SIZE - 1) {
            markChunkColumnDirty(chunkX + 1, chunkZ);
        }
        if (Math.floorMod(blockZ, GameConfig.CHUNK_SIZE) == 0) {
            markChunkColumnDirty(chunkX, chunkZ - 1);
        }
        if (Math.floorMod(blockZ, GameConfig.CHUNK_SIZE) == GameConfig.CHUNK_SIZE - 1) {
            markChunkColumnDirty(chunkX, chunkZ + 1);
        }
    }

    void rebuildChunkSectionAroundBlock(int blockX, int blockY, int blockZ) {
        if (!GameConfig.isWorldYInside(blockY)) {
            rebuildChunksAroundBlock(blockX, blockZ);
            return;
        }
        int chunkX = Math.floorDiv(blockX, GameConfig.CHUNK_SIZE);
        int chunkY = GameConfig.sectionIndexForY(blockY);
        int chunkZ = Math.floorDiv(blockZ, GameConfig.CHUNK_SIZE);
        markChunkDirty(chunkX, chunkY, chunkZ);
        int localX = Math.floorMod(blockX, GameConfig.CHUNK_SIZE);
        int localY = GameConfig.localYForWorldY(blockY);
        int localZ = Math.floorMod(blockZ, GameConfig.CHUNK_SIZE);
        if (localX == 0) {
            markChunkDirty(chunkX - 1, chunkY, chunkZ);
        } else if (localX == GameConfig.CHUNK_SIZE - 1) {
            markChunkDirty(chunkX + 1, chunkY, chunkZ);
        }
        if (localZ == 0) {
            markChunkDirty(chunkX, chunkY, chunkZ - 1);
        } else if (localZ == GameConfig.CHUNK_SIZE - 1) {
            markChunkDirty(chunkX, chunkY, chunkZ + 1);
        }
        if (localY == 0 && chunkY > 0) {
            markChunkDirty(chunkX, chunkY - 1, chunkZ);
        } else if (localY == GameConfig.CHUNK_SIZE - 1 && chunkY + 1 < GameConfig.WORLD_CHUNKS_Y) {
            markChunkDirty(chunkX, chunkY + 1, chunkZ);
        }
    }

    void markAllChunksDirty() {
        dirtyChunkMeshes.clear();
        world.fillLoadedChunksSnapshot(loadedChunkSnapshot);
        for (Chunk chunk : loadedChunkSnapshot) {
            dirtyChunkMeshes.add(chunkKey(chunk.chunkX, chunk.chunkY, chunk.chunkZ));
        }
    }

    void render(PlayerState player, PlayerInventory inventory, RayHit hoveredBlock, RayHit breakingBlock, double breakingProgress, boolean paused, boolean inventoryOpen, int inventoryScreenMode, ContainerInventory chestContainer, FurnaceBlockEntity furnace, boolean deathScreenActive, int deathSelection, boolean mainMenuActive, int mainMenuScreen, int mainMenuSelection, boolean mainMenuWorldActionsEnabled, String createWorldName, String createWorldSeed, int createWorldGameMode, int createWorldDifficulty, int activeMenuTextField, String renameWorldName, List<WorldInfo> worlds, int selectedWorldIndex, int mainMenuScrollOffset, String loadedWorldName, boolean showDebugInfo, boolean hideHud, int pauseSelection, boolean gameModeSwitcherActive, int gameModeSelection, byte selectedBlock, int selectedSlot, int creativeTab, boolean creativeMode, boolean thirdPersonView, boolean frontThirdPersonView, boolean sprinting, int renderDistanceChunks, int fovDegrees, double timeOfDay, double mouseX, double mouseY, double deltaTime, ChatSystem chat) {
        if (!resourcesReady || framebufferWidth <= 0 || framebufferHeight <= 0) {
            return;
        }

        currentRenderDistanceChunks = clamp(renderDistanceChunks, GameConfig.MIN_RENDER_DISTANCE, GameConfig.MAX_RENDER_DISTANCE_CHUNKS);
        updateFpsCounter(deltaTime);
        world.fillLoadedChunksSnapshot(loadedChunkSnapshot);
        updateSkyColor(timeOfDay);
        glViewport(0, 0, framebufferWidth, framebufferHeight);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDisable(GL_CULL_FACE);

        updateCameraEffects(sprinting, fovDegrees, deltaTime);
        boolean mainMenuHome = mainMenuActive && mainMenuScreen == GameConfig.MENU_SCREEN_MAIN;
        boolean menuPanorama = mainMenuHome && loadedWorldName != null && !loadedWorldName.isBlank();
        setupProjection();
        if (menuPanorama) {
            setupMenuPanoramaCamera(player, deltaTime);
        } else {
            setupCamera(player, thirdPersonView, frontThirdPersonView);
        }
        if (!paused && (!mainMenuActive || menuPanorama)) {
            ensureChunkMeshesAroundPlayer(player.x, player.y, player.z, getChunkRenderRadius(player));
        }
        renderAtmosphere(player, timeOfDay, deltaTime);
        configureFog(timeOfDay, renderDistanceChunks);
        renderChunks(player, false);
        if (!menuPanorama) {
            renderFallingBlocks(player);
            renderZombies(player);
            renderDroppedItems(player, timeOfDay);
            renderPlayerModel(player, inventory, selectedBlock, thirdPersonView);
        }
        renderChunks(player, true);
        glDisable(GL_FOG);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        if (!mainMenuActive) {
            renderHoveredOutline(hoveredBlock);
            renderBreakingOverlay(breakingBlock, breakingProgress);
            renderWorldTint(player);
        }
        renderOverlay(player, inventory, hoveredBlock, paused, inventoryOpen, inventoryScreenMode, chestContainer, furnace, deathScreenActive, deathSelection, mainMenuActive, mainMenuScreen, mainMenuSelection, mainMenuWorldActionsEnabled, createWorldName, createWorldSeed, createWorldGameMode, createWorldDifficulty, activeMenuTextField, renameWorldName, worlds, selectedWorldIndex, mainMenuScrollOffset, loadedWorldName, showDebugInfo, hideHud, pauseSelection, gameModeSwitcherActive, gameModeSelection, selectedBlock, selectedSlot, creativeTab, creativeMode, thirdPersonView, renderDistanceChunks, fovDegrees, timeOfDay, mouseX, mouseY, chat);
        logOpenGlError("render");
    }

    void cleanup() {
        for (ChunkMesh mesh : chunkMeshes.values()) {
            unloadChunkMesh(mesh);
        }
        chunkMeshes.clear();
        dirtyChunkMeshes.clear();
        meshBuilderPool.clear();
        if (terrainTextureId != 0) {
            glDeleteTextures(terrainTextureId);
            terrainTextureId = 0;
        }
        if (uploadProbeVboId != 0) {
            glDeleteBuffers(uploadProbeVboId);
            uploadProbeVboId = 0;
        }
        resourcesReady = false;
    }

    private void updateSkyColor(double timeOfDay) {
        double sun = Math.sin(timeOfDay * Math.PI * 2.0 - Math.PI * 0.5);
        float daylight = clampColor((float) ((sun + 0.18) / 1.18));
        daylight = Math.max(0.10f, daylight);
        float duskGlow = (float) Math.pow(Math.max(0.0, 1.0 - Math.abs(sun) * 1.5), 2.0);

        float skyRed = clampColor(0.03f + daylight * 0.47f + duskGlow * 0.18f);
        float skyGreen = clampColor(0.05f + daylight * 0.63f + duskGlow * 0.06f);
        float skyBlue = clampColor(0.12f + daylight * 0.80f);

        currentDaylight = daylight;
        currentSceneBrightness = 0.55f + currentDaylight * 0.45f;
        glClearColor(skyRed, skyGreen, skyBlue, 1.0f);
    }

    private void setupProjection() {
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();

        double nearPlane = CAMERA_NEAR_PLANE;
        double farPlane = cameraFarPlane();
        double aspect = (double) framebufferWidth / framebufferHeight;
        double top = Math.tan(Math.toRadians(currentFovDegrees) * 0.5) * nearPlane;
        double rightPlane = top * aspect;
        glFrustum(-rightPlane, rightPlane, -top, top, nearPlane, farPlane);
    }

    private void setupCamera(PlayerState player, boolean thirdPersonView, boolean frontThirdPersonView) {
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();

        double pitchDegrees = Math.toDegrees(player.pitch);
        double yawDegrees = Math.toDegrees(player.yaw);
        double bobOffsetY = 0.0;
        double bobRoll = 0.0;

        double targetX = player.x;
        double targetY = player.y + player.eyeHeight() + bobOffsetY;
        double targetZ = player.z;

        double cameraX = targetX;
        double cameraY = targetY;
        double cameraZ = targetZ;

        if (thirdPersonView) {
            double lookX = Math.cos(player.yaw) * Math.cos(player.pitch);
            double lookY = Math.sin(player.pitch);
            double lookZ = Math.sin(player.yaw) * Math.cos(player.pitch);
            double desiredDistance = 4.5;
            double safeDistance = desiredDistance;
            if (!frontThirdPersonView) {
                for (double sample = 0.4; sample <= desiredDistance; sample += 0.2) {
                    double testX = targetX - lookX * sample;
                    double testY = targetY - lookY * sample + 0.35;
                    double testZ = targetZ - lookZ * sample;
                    if (world.collides(testX, testY - 0.2, testZ, 0.18, 0.18)) {
                        safeDistance = sample - 0.25;
                        break;
                    }
                }
            }
            safeDistance = Math.max(0.9, safeDistance);
            double cameraDirection = frontThirdPersonView ? 1.0 : -1.0;
            cameraX = targetX + lookX * safeDistance * cameraDirection;
            cameraY = targetY + lookY * safeDistance * cameraDirection + 0.35;
            cameraZ = targetZ + lookZ * safeDistance * cameraDirection;
        }

        renderCameraX = cameraX;
        renderCameraY = cameraY;
        renderCameraZ = cameraZ;
        cameraBlockX = (int) Math.floor(renderCameraX);
        cameraBlockY = (int) Math.floor(renderCameraY);
        cameraBlockZ = (int) Math.floor(renderCameraZ);
        waterRenderer.setCameraBlock(cameraBlockX, cameraBlockY, cameraBlockZ);
        spectatorInsideBlock = player.spectatorMode && isCameraInsideSolidBlock();
        refreshCameraInsideBlockMesh();

        double viewPitchDegrees = frontThirdPersonView ? -pitchDegrees : pitchDegrees;
        double viewYawDegrees = frontThirdPersonView ? yawDegrees + 180.0 : yawDegrees;
        updateFrustumBasis(Math.toRadians(viewYawDegrees), Math.toRadians(viewPitchDegrees));
        glRotatef((float) -viewPitchDegrees, 1.0f, 0.0f, 0.0f);
        glRotatef((float) (viewYawDegrees + 90.0), 0.0f, 1.0f, 0.0f);
        glRotatef((float) -bobRoll, 0.0f, 0.0f, 1.0f);
    }

    private void setupMenuPanoramaCamera(PlayerState player, double deltaTime) {
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();

        double step = Math.max(0.0, Math.min(deltaTime, 0.05));
        menuPanoramaYaw += step * 0.13;
        if (menuPanoramaYaw > Math.PI * 2.0) {
            menuPanoramaYaw -= Math.PI * 2.0;
        }

        double yaw = menuPanoramaYaw;
        double pitch = Math.toRadians(-7.0);
        renderCameraX = player.x;
        renderCameraY = player.y + player.eyeHeight() + 5.0;
        renderCameraZ = player.z;
        cameraBlockX = (int) Math.floor(renderCameraX);
        cameraBlockY = (int) Math.floor(renderCameraY);
        cameraBlockZ = (int) Math.floor(renderCameraZ);
        waterRenderer.setCameraBlock(cameraBlockX, cameraBlockY, cameraBlockZ);
        spectatorInsideBlock = false;
        refreshCameraInsideBlockMesh();

        updateFrustumBasis(yaw, pitch);
        glRotatef((float) -Math.toDegrees(pitch), 1.0f, 0.0f, 0.0f);
        glRotatef((float) (Math.toDegrees(yaw) + 90.0), 0.0f, 1.0f, 0.0f);
    }

    private void updateFrustumBasis(double yaw, double pitch) {
        double horizontal = Math.cos(pitch);
        frustumForwardX = Math.cos(yaw) * horizontal;
        frustumForwardY = Math.sin(pitch);
        frustumForwardZ = Math.sin(yaw) * horizontal;
        frustumRightX = -Math.sin(yaw);
        frustumRightZ = Math.cos(yaw);
        frustumUpX = -frustumRightZ * frustumForwardY;
        frustumUpY = frustumRightZ * frustumForwardX - frustumRightX * frustumForwardZ;
        frustumUpZ = frustumRightX * frustumForwardY;
    }

    private void renderAtmosphere(PlayerState player, double timeOfDay, double deltaTime) {
        skyRenderer.render(player, timeOfDay, deltaTime, currentDaylight, renderCameraX, renderCameraY, renderCameraZ);
    }

    private void renderWorldTint(PlayerState player) {
        float nightAlpha = (1.0f - currentDaylight) * 0.42f;
        float underwaterAlpha = player != null && player.headInWater ? 0.32f : 0.0f;
        if (nightAlpha <= 0.01f && underwaterAlpha <= 0.01f) {
            return;
        }

        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0.0, framebufferWidth, framebufferHeight, 0.0, -1.0, 1.0);

        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();
        glDisable(GL_DEPTH_TEST);
        if (nightAlpha > 0.01f) {
            drawRect(0.0f, 0.0f, framebufferWidth, framebufferHeight, 0.02f, 0.03f, 0.10f, nightAlpha);
        }
        if (underwaterAlpha > 0.01f) {
            drawRect(0.0f, 0.0f, framebufferWidth, framebufferHeight, 0.07f, 0.22f, 0.38f, underwaterAlpha);
        }
        glEnable(GL_DEPTH_TEST);
        glPopMatrix();

        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
    }

    private void renderChunks(PlayerState player, boolean transparentPass) {
        int playerChunkX = worldToChunk((int) Math.floor(player.x));
        int playerChunkZ = worldToChunk((int) Math.floor(player.z));
        int chunkRenderRadius = getChunkRenderRadius(player);

        if (transparentPass) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);
        } else {
            glDisable(GL_BLEND);
            glDepthMask(true);
        }

        ArrayList<ChunkMesh> transparentMeshes = null;
        if (transparentPass) {
            transparentChunkPass.clear();
            transparentMeshes = transparentChunkPass;
        }

        for (Chunk chunk : loadedChunkSnapshot) {
            int chunkX = chunk.chunkX;
            int chunkY = chunk.chunkY;
            int chunkZ = chunk.chunkZ;
            if (Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ)) > chunkRenderRadius) {
                continue;
            }
            if (!shouldRenderChunk(player, chunkX, chunkY, chunkZ)) {
                continue;
            }

            ChunkMesh mesh = chunkMeshes.get(chunkKey(chunkX, chunkY, chunkZ));
            if (mesh == null || !mesh.resident) {
                continue;
            }
            int vertexCount = transparentPass ? mesh.transparentVertexCount : mesh.opaqueVertexCount;
            int vaoId = transparentPass ? mesh.transparentVaoId : mesh.opaqueVaoId;
            int vboId = transparentPass ? mesh.transparentVboId : mesh.opaqueVboId;
            if (vertexCount > 0 && vboId != 0) {
                if (transparentPass) {
                    transparentMeshes.add(mesh);
                } else {
                    drawChunkBuffer(mesh, vaoId, vboId, vertexCount);
                }
            }
        }

        if (transparentPass) {
            transparentMeshes.sort((a, b) -> Double.compare(
                chunkDistanceSquaredToCamera(b),
                chunkDistanceSquaredToCamera(a)
            ));
            for (ChunkMesh mesh : transparentMeshes) {
                drawChunkBuffer(mesh, mesh.transparentVaoId, mesh.transparentVboId, mesh.transparentVertexCount);
            }
        }

        glDepthMask(true);
        if (transparentPass) {
            glDisable(GL_BLEND);
        }
    }

    private double chunkDistanceSquaredToCamera(ChunkMesh mesh) {
        double centerX = (mesh.chunkX + 0.5) * GameConfig.CHUNK_SIZE;
        double centerY = GameConfig.sectionYForIndex(mesh.chunkY) + GameConfig.CHUNK_SIZE * 0.5;
        double centerZ = (mesh.chunkZ + 0.5) * GameConfig.CHUNK_SIZE;
        double dx = centerX - renderCameraX;
        double dy = centerY - renderCameraY;
        double dz = centerZ - renderCameraZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private double chunkDistanceSquaredToPoint(int chunkX, int chunkY, int chunkZ, double x, double y, double z) {
        double centerX = (chunkX + 0.5) * GameConfig.CHUNK_SIZE;
        double centerY = GameConfig.sectionYForIndex(chunkY) + GameConfig.CHUNK_SIZE * 0.5;
        double centerZ = (chunkZ + 0.5) * GameConfig.CHUNK_SIZE;
        double dx = centerX - x;
        double dy = centerY - y;
        double dz = centerZ - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean shouldRenderChunk(PlayerState player, int chunkX, int chunkY, int chunkZ) {
        int playerChunkX = worldToChunk((int) Math.floor(player.x));
        int playerChunkZ = worldToChunk((int) Math.floor(player.z));
        return shouldRenderChunk(playerChunkX, playerChunkZ, getChunkRenderRadius(player), chunkX, chunkY, chunkZ);
    }

    private boolean shouldRenderChunk(int playerChunkX, int playerChunkZ, int chunkRenderRadius, int chunkX, int chunkY, int chunkZ) {
        if (world.isChunkEmpty(chunkX, chunkY, chunkZ)) {
            return false;
        }
        if (Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ)) > chunkRenderRadius) {
            return false;
        }

        return true;
    }

    private boolean isAabbInsideCameraFrustum(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double centerX = (minX + maxX) * 0.5;
        double centerY = (minY + maxY) * 0.5;
        double centerZ = (minZ + maxZ) * 0.5;
        double radius = Math.sqrt(
            square((maxX - minX) * 0.5)
                + square((maxY - minY) * 0.5)
                + square((maxZ - minZ) * 0.5)
        );

        double dx = centerX - renderCameraX;
        double dy = centerY - renderCameraY;
        double dz = centerZ - renderCameraZ;
        double depth = dx * frustumForwardX + dy * frustumForwardY + dz * frustumForwardZ;
        double farPlane = cameraFarPlane();
        if (depth < -radius || depth > farPlane + radius) {
            return false;
        }

        double aspect = framebufferHeight <= 0 ? 1.0 : (double) framebufferWidth / framebufferHeight;
        double verticalLimit = Math.tan(Math.toRadians(currentFovDegrees) * 0.5) * Math.max(0.0, depth) + radius;
        double horizontalLimit = verticalLimit * aspect + radius;
        double horizontal = dx * frustumRightX + dz * frustumRightZ;
        double vertical = dx * frustumUpX + dy * frustumUpY + dz * frustumUpZ;
        return Math.abs(horizontal) <= horizontalLimit && Math.abs(vertical) <= verticalLimit;
    }

    private double square(double value) {
        return value * value;
    }

    private double cameraFarPlane() {
        double horizontalDistance = currentRenderDistanceChunks * GameConfig.CHUNK_SIZE;
        return Math.max(Math.hypot(horizontalDistance, GameConfig.WORLD_HEIGHT * 0.5) + CAMERA_FAR_PADDING, GameConfig.WORLD_HEIGHT * 1.45);
    }

    private void configureFog(double timeOfDay, int renderDistanceChunks) {
        float daylight = currentDaylight;
        float night = 1.0f - daylight;
        float red = clampColor(0.05f + daylight * 0.50f + night * 0.03f);
        float green = clampColor(0.07f + daylight * 0.64f + night * 0.04f);
        float blue = clampColor(0.11f + daylight * 0.82f + night * 0.10f);
        FloatBuffer fogColor = BufferUtils.createFloatBuffer(4);
        fogColor.put(red).put(green).put(blue).put(1.0f).flip();

        float visibleBlocks = Math.max(GameConfig.CHUNK_SIZE * GameConfig.MIN_RENDER_DISTANCE, renderDistanceChunks * GameConfig.CHUNK_SIZE);
        glEnable(GL_FOG);
        glFogi(GL_FOG_MODE, GL_LINEAR);
        glFogfv(GL_FOG_COLOR, fogColor);
        glFogf(GL_FOG_START, visibleBlocks * 0.72f);
        glFogf(GL_FOG_END, visibleBlocks * 1.12f);
    }

    private boolean shouldRenderZombie(PlayerState player, Zombie zombie) {
        return isSphereVisible(
            player,
            zombie.x,
            zombie.y + GameConfig.ZOMBIE_HEIGHT * 0.5,
            zombie.z,
            0.9,
            Math.min(GameConfig.MAX_RENDER_DISTANCE, GameConfig.ZOMBIE_RENDER_DISTANCE)
        );
    }

    private boolean isSphereVisible(PlayerState player, double centerX, double centerY, double centerZ, double radius, double maxDistance) {
        double eyeX = player.x;
        double eyeY = player.y + GameConfig.EYE_HEIGHT;
        double eyeZ = player.z;
        double dx = centerX - eyeX;
        double dy = centerY - eyeY;
        double dz = centerZ - eyeZ;
        double maxVisibleDistance = maxDistance + radius;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared > maxVisibleDistance * maxVisibleDistance) {
            return false;
        }

        double horizontal = Math.cos(player.pitch);
        double forwardX = Math.cos(player.yaw) * horizontal;
        double forwardY = Math.sin(player.pitch);
        double forwardZ = Math.sin(player.yaw) * horizontal;
        double forwardProjection = dx * forwardX + dy * forwardY + dz * forwardZ;
        if (forwardProjection < -radius * 1.35) {
            return false;
        }

        double lateralSquared = Math.max(0.0, distanceSquared - forwardProjection * forwardProjection);
        double effectiveProjection = Math.max(0.0, forwardProjection);
        double halfFov = Math.toRadians(currentFovDegrees * 0.5);
        double lateralAllowance = Math.tan(halfFov) * effectiveProjection + radius * 1.7 + 2.5;
        return forwardProjection > 0.0 || distanceSquared <= (radius + 3.0) * (radius + 3.0) || lateralSquared <= lateralAllowance * lateralAllowance;
    }

    private void rebuildChunkMesh(int chunkX, int chunkY, int chunkZ, double sortCameraX, double sortCameraY, double sortCameraZ) {
        if (chunkY < 0 || chunkY >= GameConfig.WORLD_CHUNKS_Y) {
            return;
        }

        long meshKey = chunkKey(chunkX, chunkY, chunkZ);
        ChunkMesh mesh = chunkMeshes.get(meshKey);
        if (mesh == null) {
            mesh = new ChunkMesh(chunkX, chunkY, chunkZ);
            chunkMeshes.put(meshKey, mesh);
        }

        int startX = chunkX * GameConfig.CHUNK_SIZE;
        int startY = GameConfig.sectionYForIndex(chunkY);
        int startZ = chunkZ * GameConfig.CHUNK_SIZE;
        int endX = startX + GameConfig.CHUNK_SIZE;
        int endY = startY + GameConfig.CHUNK_SIZE;
        int endZ = startZ + GameConfig.CHUNK_SIZE;

        FloatArrayBuilder opaqueBuilder = acquireMeshBuilder(4096);
        FloatArrayBuilder transparentBuilder = acquireMeshBuilder(2048);
        try {
            buildChunkDisplayList(opaqueBuilder, transparentBuilder, startX, startY, startZ, endX, endY, endZ);
            transparentBuilder.sortQuadsFarToNear(sortCameraX - startX, sortCameraY - startY, sortCameraZ - startZ);

            deleteMeshBuffer(mesh.opaqueVaoId, mesh.opaqueVboId);
            deleteMeshBuffer(mesh.transparentVaoId, mesh.transparentVboId);
            mesh.opaqueVaoId = 0;
            mesh.opaqueVboId = 0;
            mesh.opaqueVertexCount = 0;
            mesh.transparentVaoId = 0;
            mesh.transparentVboId = 0;
            mesh.transparentVertexCount = 0;

            uploadChunkBuffer(mesh, false, opaqueBuilder);
            uploadChunkBuffer(mesh, true, transparentBuilder);
            mesh.hasOpaqueGeometry = mesh.opaqueVertexCount > 0;
            mesh.hasTransparentGeometry = mesh.transparentVertexCount > 0;
            mesh.resident = mesh.hasOpaqueGeometry || mesh.hasTransparentGeometry;
        } finally {
            releaseMeshBuilder(opaqueBuilder);
            releaseMeshBuilder(transparentBuilder);
        }
        verifyOpenGl("rebuildChunkMesh(" + chunkX + "," + chunkY + "," + chunkZ + ")");
    }

    private FloatArrayBuilder acquireMeshBuilder(int initialCapacity) {
        FloatArrayBuilder builder = meshBuilderPool.pollFirst();
        if (builder == null) {
            return new FloatArrayBuilder(initialCapacity);
        }
        builder.reset(initialCapacity);
        return builder;
    }

    private void releaseMeshBuilder(FloatArrayBuilder builder) {
        builder.clear();
        if (meshBuilderPool.size() < GameConfig.CHUNK_GENERATION_THREADS * 4) {
            meshBuilderPool.addFirst(builder);
        }
    }

    private void buildChunkDisplayList(FloatArrayBuilder opaqueBuilder, FloatArrayBuilder transparentBuilder, int startX, int startY, int startZ, int endX, int endY, int endZ) {
        for (int x = startX; x < endX; x++) {
            for (int y = startY; y < endY; y++) {
                for (int z = startZ; z < endZ; z++) {
                    byte block = world.getBlock(x, y, z);
                    if (block == GameConfig.AIR) {
                        continue;
                    }
                    boolean cameraInsideSolidBlock = x == cameraBlockX
                        && y == cameraBlockY
                        && z == cameraBlockZ
                        && world.isSolidBlock(block);
                    if (cameraInsideSolidBlock && !spectatorInsideBlock) {
                        continue;
                    }
                    if (spectatorInsideBlock && cameraInsideSolidBlock) {
                        continue;
                    }
                    if (spectatorInsideBlock && world.isSolidBlock(block) && isNearSpectatorCutoutBlock(x, y, z)) {
                        continue;
                    }

                    boolean stableOpaqueBlock = block == GameConfig.SNOW_LAYER
                        || block == GameConfig.OAK_LEAVES
                        || block == GameConfig.PINE_LEAVES;
                    FloatArrayBuilder targetBuilder = (!stableOpaqueBlock && world.isTransparentBlock(block)) ? transparentBuilder : opaqueBuilder;
                    int localX = x - startX;
                    int localY = y - startY;
                    int localZ = z - startZ;
                    if (block == GameConfig.OAK_DOOR) {
                        emitDoorBlock(targetBuilder, localX, localY, localZ, x, y, z);
                        continue;
                    }
                    if (world.isCrossPlant(block)) {
                        emitCrossPlant(targetBuilder, localX, localY, localZ, x, y, z, block);
                        continue;
                    }
                    if (!world.isCubeBlock(block)) {
                        continue;
                    }

                    if (waterRenderer.isRenderableBlockFace(block, x, y, z, Face.WEST)) {
                        emitBlockFace(targetBuilder, localX, localY, localZ, x, y, z, Face.WEST, block);
                    }
                    if (waterRenderer.isRenderableBlockFace(block, x, y, z, Face.EAST)) {
                        emitBlockFace(targetBuilder, localX, localY, localZ, x, y, z, Face.EAST, block);
                    }
                    if (waterRenderer.isRenderableBlockFace(block, x, y, z, Face.TOP)) {
                        emitBlockFace(targetBuilder, localX, localY, localZ, x, y, z, Face.TOP, block);
                    }
                    if (waterRenderer.isRenderableBlockFace(block, x, y, z, Face.BOTTOM)) {
                        emitBlockFace(targetBuilder, localX, localY, localZ, x, y, z, Face.BOTTOM, block);
                    }
                    if (waterRenderer.isRenderableBlockFace(block, x, y, z, Face.NORTH)) {
                        emitBlockFace(targetBuilder, localX, localY, localZ, x, y, z, Face.NORTH, block);
                    }
                    if (waterRenderer.isRenderableBlockFace(block, x, y, z, Face.SOUTH)) {
                        emitBlockFace(targetBuilder, localX, localY, localZ, x, y, z, Face.SOUTH, block);
                    }
                }
            }
        }
    }

    private void emitBlockFace(FloatArrayBuilder builder, int x, int y, int z, int worldX, int worldY, int worldZ, Face face, byte block) {
        emitBlockFace(builder, x, y, z, worldX, worldY, worldZ, face, block, -1.0f);
    }

    private void emitBlockFace(FloatArrayBuilder builder, int x, int y, int z, int worldX, int worldY, int worldZ, Face face, byte block, float forcedAlpha) {
        double minX = x;
        double minY = y;
        double minZ = z;
        double maxX = x + 1.0;
        double maxY = y + 1.0;
        double maxZ = z + 1.0;
        boolean transparent = world.isTransparentBlock(block);
        double offset = transparent ? 0.001 : 0.0;
        boolean liquid = world.isLiquidBlock(block);
        if (liquid) {
            maxY = y + waterRenderer.liquidRenderHeight(block, worldX, worldY, worldZ) - LIQUID_SURFACE_Z_OFFSET;
        } else if (block == GameConfig.SNOW_LAYER) {
            maxY = y + 0.125;
        } else if (block == GameConfig.RED_BED) {
            maxY = y + 0.56;
        }
        double sideMinY = world.isLiquidBlock(block) ? waterRenderer.liquidSideMinY(minY, block, face, worldX, worldY, worldZ) : minY;

        float[] color = colorForFace(block, face, world.getAmbientShade(worldX, worldY, worldZ), worldX, worldY, worldZ);
        if (forcedAlpha >= 0.0f) {
            color[3] = forcedAlpha;
        }

        switch (face) {
            case WEST:
                appendQuad(builder, minX - offset, sideMinY, minZ, minX - offset, maxY, minZ, minX - offset, maxY, maxZ, minX - offset, sideMinY, maxZ, color);
                break;
            case EAST:
                appendQuad(builder, maxX + offset, sideMinY, maxZ, maxX + offset, maxY, maxZ, maxX + offset, maxY, minZ, maxX + offset, sideMinY, minZ, color);
                break;
            case TOP:
                appendQuad(builder, minX, maxY + (liquid ? 0.0 : offset), maxZ, minX, maxY + (liquid ? 0.0 : offset), minZ, maxX, maxY + (liquid ? 0.0 : offset), minZ, maxX, maxY + (liquid ? 0.0 : offset), maxZ, color);
                break;
            case BOTTOM:
                appendQuad(builder, minX, minY - offset, minZ, minX, minY - offset, maxZ, maxX, minY - offset, maxZ, maxX, minY - offset, minZ, color);
                break;
            case NORTH:
                appendQuad(builder, maxX, sideMinY, minZ - offset, maxX, maxY, minZ - offset, minX, maxY, minZ - offset, minX, sideMinY, minZ - offset, color);
                break;
            case SOUTH:
                appendQuad(builder, minX, sideMinY, maxZ + offset, minX, maxY, maxZ + offset, maxX, maxY, maxZ + offset, maxX, sideMinY, maxZ + offset, color);
                break;
            default:
                break;
        }
        if (isOreBlock(block) && !liquid && forcedAlpha < 0.0f) {
            emitOreFaceOverlay(builder, minX, minY, minZ, maxX, maxY, maxZ, face, block, ambientShade(worldX, worldY, worldZ));
        }
    }

    private float ambientShade(int worldX, int worldY, int worldZ) {
        return world.getAmbientShade(worldX, worldY, worldZ);
    }

    private void emitOreFaceOverlay(FloatArrayBuilder builder, double minX, double minY, double minZ,
                                    double maxX, double maxY, double maxZ, Face face, byte block, float ambientShade) {
        float[] color = oreAccentColor(block, face.brightness * ambientShade);
        double[][] patches = {
            {0.18, 0.20, 0.20},
            {0.56, 0.16, 0.16},
            {0.34, 0.48, 0.24}
        };
        double lift = 0.004;
        for (double[] patch : patches) {
            double a = patch[0];
            double b = patch[1];
            double size = patch[2];
            appendOrePatch(builder, minX, minY, minZ, maxX, maxY, maxZ, face, a, b, size, lift, color);
        }
    }

    private void appendOrePatch(FloatArrayBuilder builder, double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ, Face face,
                                double a, double b, double size, double lift, float[] color) {
        double a2 = Math.min(0.92, a + size);
        double b2 = Math.min(0.92, b + size);
        switch (face) {
            case WEST: {
                double x = minX - lift;
                appendQuad(builder, x, minY + a, minZ + b, x, minY + a2, minZ + b, x, minY + a2, minZ + b2, x, minY + a, minZ + b2, color);
                break;
            }
            case EAST: {
                double x = maxX + lift;
                appendQuad(builder, x, minY + a, minZ + b2, x, minY + a2, minZ + b2, x, minY + a2, minZ + b, x, minY + a, minZ + b, color);
                break;
            }
            case TOP: {
                double y = maxY + lift;
                appendQuad(builder, minX + a, y, minZ + b2, minX + a, y, minZ + b, minX + a2, y, minZ + b, minX + a2, y, minZ + b2, color);
                break;
            }
            case BOTTOM: {
                double y = minY - lift;
                appendQuad(builder, minX + a, y, minZ + b, minX + a, y, minZ + b2, minX + a2, y, minZ + b2, minX + a2, y, minZ + b, color);
                break;
            }
            case NORTH: {
                double z = minZ - lift;
                appendQuad(builder, minX + a2, minY + b, z, minX + a2, minY + b2, z, minX + a, minY + b2, z, minX + a, minY + b, z, color);
                break;
            }
            case SOUTH: {
                double z = maxZ + lift;
                appendQuad(builder, minX + a, minY + b, z, minX + a, minY + b2, z, minX + a2, minY + b2, z, minX + a2, minY + b, z, color);
                break;
            }
            default:
                break;
        }
    }

    private void emitDoorBlock(FloatArrayBuilder builder, int x, int y, int z, int worldX, int worldY, int worldZ) {
        BlockState state = world.getBlockState(worldX, worldY, worldZ);
        int facing = Blocks.doorFacing(state);
        boolean open = Blocks.isDoorOpen(state);
        double thickness = 0.16;
        double minX = x;
        double maxX = x + 1.0;
        double minZ = z;
        double maxZ = z + 1.0;
        int visualFacing = open ? ((facing + 1) & 3) : facing;
        switch (visualFacing) {
            case 0:
                maxZ = z + thickness;
                break;
            case 1:
                minX = x + 1.0 - thickness;
                break;
            case 2:
                minZ = z + 1.0 - thickness;
                break;
            default:
                maxX = x + thickness;
                break;
        }
        float[] color = colorForFace(GameConfig.OAK_DOOR, Face.TOP, world.getAmbientShade(worldX, worldY, worldZ), worldX, worldY, worldZ);
        appendCuboid(builder, minX, y, minZ, maxX, y + 1.0, maxZ, color);
    }

    private void appendCuboid(FloatArrayBuilder builder, double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ, float[] color) {
        appendQuad(builder, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ, color);
        appendQuad(builder, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, maxX, minY, minZ, color);
        appendQuad(builder, minX, maxY, maxZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, color);
        appendQuad(builder, minX, minY, minZ, minX, minY, maxZ, maxX, minY, maxZ, maxX, minY, minZ, color);
        appendQuad(builder, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, minX, minY, minZ, color);
        appendQuad(builder, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, minY, maxZ, color);
    }

    private boolean isNearSpectatorCutoutBlock(int worldX, int worldY, int worldZ) {
        int dx = worldX - cameraBlockX;
        int dy = worldY - cameraBlockY;
        int dz = worldZ - cameraBlockZ;
        return dx * dx + dy * dy + dz * dz <= 5;
    }

    private void emitCrossPlant(FloatArrayBuilder builder, int x, int y, int z, int worldX, int worldY, int worldZ, byte block) {
        if (block == GameConfig.TORCH) {
            emitTorchBlock(builder, x, y, z, worldX, worldY, worldZ);
            return;
        }
        float[] color = getBlockFaceColor(block, Face.TOP);
        if (block == GameConfig.TALL_GRASS) {
            color = applyColdGrassTint(color, world.getColdSurfaceTint(worldX, worldY, worldZ));
        }
        float ambient = world.getAmbientShade(worldX, worldY, worldZ);
        float alpha = block == GameConfig.SEAGRASS ? 0.72f : (block == GameConfig.TALL_GRASS ? 0.92f : 0.96f);
        float[] tintedColor = new float[]{
            clampColor(color[0] * ambient),
            clampColor(color[1] * ambient),
            clampColor(color[2] * ambient),
            alpha
        };

        boolean smallFlower = block == GameConfig.RED_FLOWER || block == GameConfig.YELLOW_FLOWER;
        boolean crop = block == GameConfig.WHEAT_CROP;
        boolean torch = block == GameConfig.TORCH;
        double halfWidth = torch ? 0.12 : (smallFlower ? 0.21 : (crop ? 0.30 : 0.42));
        double height = torch ? 0.76 : (block == GameConfig.SEAGRASS ? 0.72 : (smallFlower ? 0.49 : (crop ? 0.78 : 0.98)));
        double minX = x + 0.5 - halfWidth;
        double minY = y;
        double minZ = z + 0.5 - halfWidth;
        double maxX = x + 0.5 + halfWidth;
        double maxY = y + height;
        double maxZ = z + 0.5 + halfWidth;

        appendQuad(builder, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, tintedColor);
        appendQuad(builder, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, minZ, minX, minY, minZ, tintedColor);
        appendQuad(builder, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ, tintedColor);
        appendQuad(builder, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, minZ, maxX, minY, minZ, tintedColor);
    }

    private void emitTorchBlock(FloatArrayBuilder builder, int x, int y, int z, int worldX, int worldY, int worldZ) {
        float ambient = Math.max(0.82f, world.getAmbientShade(worldX, worldY, worldZ));
        float[] wood = new float[]{0.50f * ambient, 0.32f * ambient, 0.16f * ambient, 1.0f};
        float[] flame = new float[]{1.0f, 0.72f, 0.18f, 1.0f};
        double cx = x + 0.5;
        double cz = z + 0.5;
        appendCuboid(builder, cx - 0.055, y, cz - 0.055, cx + 0.055, y + 0.62, cz + 0.055, wood);
        appendCuboid(builder, cx - 0.105, y + 0.58, cz - 0.105, cx + 0.105, y + 0.82, cz + 0.105, flame);
    }

    private void appendQuad(FloatArrayBuilder builder,
                            double x1, double y1, double z1,
                            double x2, double y2, double z2,
                            double x3, double y3, double z3,
                            double x4, double y4, double z4,
                            float[] color) {
        appendVertex(builder, x1, y1, z1, color);
        appendVertex(builder, x2, y2, z2, color);
        appendVertex(builder, x3, y3, z3, color);
        appendVertex(builder, x4, y4, z4, color);
    }

    private void appendVertex(FloatArrayBuilder builder, double x, double y, double z, float[] color) {
        builder.put((float) x);
        builder.put((float) y);
        builder.put((float) z);
        builder.put(color[0]);
        builder.put(color[1]);
        builder.put(color[2]);
        builder.put(color[3]);
    }

    private float[] colorForFace(byte block, Face face, float ambientShade, int worldX, int worldY, int worldZ) {
        float[] color = getBlockFaceColor(block, face);
        if (block == GameConfig.GRASS && face == Face.TOP) {
            color = applyColdGrassTint(color, world.getColdSurfaceTint(worldX, worldY, worldZ));
            color = applyTaigaGrassTint(color, world.getTaigaSurfaceTint(worldX, worldZ));
        }
        float brightness = face.brightness * ambientShade;
        if (block == GameConfig.OAK_LEAVES || block == GameConfig.PINE_LEAVES) {
            brightness = Math.max(0.82f, face.brightness * (0.90f + ambientShade * 0.10f));
        } else if (block == GameConfig.SNOW_LAYER) {
            brightness *= 0.88f;
        }
        float alpha = 1.0f;
        if (GameConfig.isWaterBlock(block)) {
            alpha = 0.50f;
        } else if (GameConfig.isLavaBlock(block)) {
            alpha = 0.85f;
        }
        return new float[]{
            clampColor(color[0] * brightness),
            clampColor(color[1] * brightness),
            clampColor(color[2] * brightness),
            alpha
        };
    }

    private float[] applyColdGrassTint(float[] color, float coldTint) {
        if (coldTint <= 0.01f) {
            return color;
        }
        float amount = Math.min(0.82f, coldTint);
        return new float[]{
            lerp(color[0], 0.48f, amount),
            lerp(color[1], 0.62f, amount),
            lerp(color[2], 0.54f, amount)
        };
    }

    private float[] applyTaigaGrassTint(float[] color, float taigaTint) {
        if (taigaTint <= 0.01f) {
            return color;
        }
        float amount = Math.min(0.75f, taigaTint);
        return new float[]{
            lerp(color[0], 0.22f, amount),
            lerp(color[1], 0.46f, amount),
            lerp(color[2], 0.24f, amount)
        };
    }

    private void uploadChunkBuffer(ChunkMesh mesh, boolean transparentPass, FloatArrayBuilder builder) {
        if (builder.isEmpty()) {
            return;
        }

        int vboId = glGenBuffers();
        if (vboId == 0) {
            throw new IllegalStateException("Failed to allocate chunk VBO");
        }

        FloatBuffer buffer = builder.toFloatBuffer();
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        int vaoId = createVertexArrayObject();
        if (vaoId != 0) {
            bindVertexArrayObject(vaoId);
            glBindBuffer(GL_ARRAY_BUFFER, vboId);
            glEnableClientState(GL_VERTEX_ARRAY);
            glEnableClientState(GL_COLOR_ARRAY);
            glVertexPointer(3, GL_FLOAT, BYTES_PER_VERTEX, 0L);
            glColorPointer(4, GL_FLOAT, BYTES_PER_VERTEX, 3L * Float.BYTES);
            bindVertexArrayObject(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }

        int vertexCount = builder.vertexCount();
        if (transparentPass) {
            mesh.transparentVaoId = vaoId;
            mesh.transparentVboId = vboId;
            mesh.transparentVertexCount = vertexCount;
        } else {
            mesh.opaqueVaoId = vaoId;
            mesh.opaqueVboId = vboId;
            mesh.opaqueVertexCount = vertexCount;
        }
    }

    private void drawChunkBuffer(ChunkMesh mesh, int vaoId, int vboId, int vertexCount) {
        glPushMatrix();
        glTranslated(
            mesh.chunkX * GameConfig.CHUNK_SIZE - renderCameraX,
            GameConfig.sectionYForIndex(mesh.chunkY) - renderCameraY,
            mesh.chunkZ * GameConfig.CHUNK_SIZE - renderCameraZ
        );

        if (vaoId != 0) {
            bindVertexArrayObject(vaoId);
            glDrawArrays(GL_QUADS, 0, vertexCount);
            bindVertexArrayObject(0);
        } else {
            glBindBuffer(GL_ARRAY_BUFFER, vboId);
            glEnableClientState(GL_VERTEX_ARRAY);
            glEnableClientState(GL_COLOR_ARRAY);
            glVertexPointer(3, GL_FLOAT, BYTES_PER_VERTEX, 0L);
            glColorPointer(4, GL_FLOAT, BYTES_PER_VERTEX, 3L * Float.BYTES);
            glDrawArrays(GL_QUADS, 0, vertexCount);
            glDisableClientState(GL_COLOR_ARRAY);
            glDisableClientState(GL_VERTEX_ARRAY);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }

        glPopMatrix();
    }

    private void unloadChunkMesh(ChunkMesh mesh) {
        deleteMeshBuffer(mesh.opaqueVaoId, mesh.opaqueVboId);
        deleteMeshBuffer(mesh.transparentVaoId, mesh.transparentVboId);
        mesh.opaqueVaoId = 0;
        mesh.opaqueVboId = 0;
        mesh.opaqueVertexCount = 0;
        mesh.transparentVaoId = 0;
        mesh.transparentVboId = 0;
        mesh.transparentVertexCount = 0;
        mesh.hasOpaqueGeometry = false;
        mesh.hasTransparentGeometry = false;
        mesh.resident = false;
    }

    private void deleteMeshBuffer(int vaoId, int vboId) {
        if (vaoId != 0) {
            deleteVertexArrayObject(vaoId);
        }
        if (vboId != 0) {
            glDeleteBuffers(vboId);
        }
    }

    private int createVertexArrayObject() {
        if (!vaoSupported) {
            return 0;
        }
        if (GL.getCapabilities().OpenGL30) {
            return org.lwjgl.opengl.GL30.glGenVertexArrays();
        }
        return org.lwjgl.opengl.ARBVertexArrayObject.glGenVertexArrays();
    }

    private void bindVertexArrayObject(int vaoId) {
        if (!vaoSupported) {
            return;
        }
        if (GL.getCapabilities().OpenGL30) {
            org.lwjgl.opengl.GL30.glBindVertexArray(vaoId);
        } else {
            org.lwjgl.opengl.ARBVertexArrayObject.glBindVertexArray(vaoId);
        }
    }

    private void deleteVertexArrayObject(int vaoId) {
        if (!vaoSupported || vaoId == 0) {
            return;
        }
        if (GL.getCapabilities().OpenGL30) {
            org.lwjgl.opengl.GL30.glDeleteVertexArrays(vaoId);
        } else {
            org.lwjgl.opengl.ARBVertexArrayObject.glDeleteVertexArrays(vaoId);
        }
    }

    private void ensureChunkMeshesAroundPlayer(double playerX, double playerY, double playerZ, int activeChunkRadius) {
        int playerChunkX = worldToChunk((int) Math.floor(playerX));
        int playerChunkZ = worldToChunk((int) Math.floor(playerZ));
        int unloadChunkRadius = activeChunkRadius + GameConfig.RENDER_CHUNK_UNLOAD_PADDING;
        int uploadBudget = dynamicChunkUploadBudget();
        long buildBudgetNs = dynamicMeshBuildBudgetNs();
        long buildStartNs = System.nanoTime();
        long meshProfileStartNs = buildStartNs;
        int visibleChunkCount = 0;
        int rebuiltMeshCount = 0;
        meshBuildQueue.clear();

        for (Chunk chunk : loadedChunkSnapshot) {
            int horizontalDistance = Math.max(Math.abs(chunk.chunkX - playerChunkX), Math.abs(chunk.chunkZ - playerChunkZ));
            long meshKey = chunkKey(chunk.chunkX, chunk.chunkY, chunk.chunkZ);
            ChunkMesh mesh = chunkMeshes.get(meshKey);
            if (world.isChunkEmpty(chunk.chunkX, chunk.chunkY, chunk.chunkZ)) {
                if (mesh != null && mesh.resident) {
                    unloadChunkMesh(mesh);
                }
                continue;
            }

            if (horizontalDistance <= activeChunkRadius) {
                boolean visible = shouldRenderChunk(playerChunkX, playerChunkZ, activeChunkRadius, chunk.chunkX, chunk.chunkY, chunk.chunkZ);
                if (visible) {
                    visibleChunkCount++;
                }
                boolean needsUpload = mesh == null || !mesh.resident || dirtyChunkMeshes.contains(meshKey);
                if (needsUpload) {
                    double distanceSquared = chunkDistanceSquaredToPoint(chunk.chunkX, chunk.chunkY, chunk.chunkZ, playerX, playerY, playerZ);
                    meshBuildQueue.add(new MeshBuildCandidate(chunk.chunkX, chunk.chunkY, chunk.chunkZ, meshKey, visible, distanceSquared));
                }
            } else if (horizontalDistance > unloadChunkRadius && mesh != null && mesh.resident) {
                unloadChunkMesh(mesh);
            }
        }

        while (uploadBudget > 0 && !meshBuildQueue.isEmpty()) {
            MeshBuildCandidate candidate = meshBuildQueue.poll();
            rebuildChunkMesh(candidate.chunkX, candidate.chunkY, candidate.chunkZ, playerX, playerY, playerZ);
            dirtyChunkMeshes.remove(candidate.meshKey);
            rebuiltMeshCount++;
            uploadBudget--;
            if (System.nanoTime() - buildStartNs >= buildBudgetNs) {
                break;
            }
        }
        logMeshProfile(rebuiltMeshCount, visibleChunkCount, System.nanoTime() - meshProfileStartNs);
        meshBuildQueue.clear();

        staleMeshKeys.clear();
        for (ChunkMesh mesh : chunkMeshes.values()) {
            int horizontalDistance = Math.max(Math.abs(mesh.chunkX - playerChunkX), Math.abs(mesh.chunkZ - playerChunkZ));
            if (!world.isChunkLoaded(mesh.chunkX, mesh.chunkZ) || horizontalDistance > unloadChunkRadius) {
                unloadChunkMesh(mesh);
                staleMeshKeys.add(chunkKey(mesh.chunkX, mesh.chunkY, mesh.chunkZ));
            }
        }
        for (long meshKey : staleMeshKeys) {
            chunkMeshes.remove(meshKey);
            dirtyChunkMeshes.remove(meshKey);
        }
        staleMeshKeys.clear();
    }

    private void logMeshProfile(int rebuiltMeshCount, int visibleChunkCount, long elapsedNs) {
        if (!GameConfig.ENABLE_FRAME_PROFILING) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastMeshProfileLogNanos < 1_000_000_000L) {
            return;
        }
        lastMeshProfileLogNanos = now;
        if (rebuiltMeshCount <= 0) {
            return;
        }
        int residentCount = 0;
        for (ChunkMesh mesh : chunkMeshes.values()) {
            if (mesh.resident) {
                residentCount++;
            }
        }
        System.out.println(String.format(Locale.ROOT,
            "OpenGlRenderer mesh rebuilt=%d resident=%d visibleChunks=%d ms=%.3f",
            rebuiltMeshCount,
            residentCount,
            visibleChunkCount,
            elapsedNs / 1_000_000.0));
    }

    private int getChunkRenderRadius(PlayerState player) {
        int radius = player.spectatorMode
            ? Math.max(currentRenderDistanceChunks, GameConfig.SPECTATOR_CHUNK_RENDER_DISTANCE)
            : currentRenderDistanceChunks;
        return clamp(radius, GameConfig.MIN_RENDER_DISTANCE, GameConfig.MAX_RENDER_DISTANCE_CHUNKS);
    }

    private int dynamicChunkUploadBudget() {
        if (debugFps >= 58.0 || debugFps <= 0.0) {
            return MAX_CHUNK_UPLOADS_PER_FRAME;
        }
        if (debugFps >= 48.0) {
            return 8;
        }
        if (debugFps >= 36.0) {
            return 5;
        }
        if (debugFps >= 26.0) {
            return 3;
        }
        return MIN_CHUNK_UPLOADS_PER_FRAME;
    }

    private long dynamicMeshBuildBudgetNs() {
        if (debugFps >= 58.0 || debugFps <= 0.0) {
            return MAX_MESH_BUILD_BUDGET_NS;
        }
        if (debugFps >= 42.0) {
            return 9_000_000L;
        }
        if (debugFps >= 30.0) {
            return 6_000_000L;
        }
        return MIN_MESH_BUILD_BUDGET_NS;
    }

    private void updateFpsCounter(double deltaTime) {
        fpsSampleFrames++;
        fpsSampleTime += deltaTime;
        if (fpsSampleTime >= 0.25) {
            debugFps = fpsSampleFrames / fpsSampleTime;
            fpsSampleFrames = 0;
            fpsSampleTime = 0.0;
        }
    }

    private long chunkKey(int chunkX, int chunkY, int chunkZ) {
        long packedX = ((long) chunkX) & 0x3FFFFFFL;
        long packedZ = ((long) chunkZ) & 0x3FFFFFFL;
        long packedY = chunkY & 0xFFFL;
        return (packedX << 38) | (packedZ << 12) | packedY;
    }

    private int worldToChunk(int coordinate) {
        return Math.floorDiv(coordinate, GameConfig.CHUNK_SIZE);
    }

    private void renderZombies(PlayerState player) {
        for (Zombie zombie : world.getZombies()) {
            if (!shouldRenderZombie(player, zombie)) {
                continue;
            }
            glPushMatrix();
            glTranslated(zombie.x - renderCameraX, zombie.y - renderCameraY, zombie.z - renderCameraZ);
            glRotated(-Math.toDegrees(zombie.bodyYaw) - 90.0, 0.0, 1.0, 0.0);

            double armSwing = Math.sin(zombie.walkCycle) * 28.0;
            double legSwing = Math.sin(zombie.walkCycle) * 34.0;
            double headTurn = Math.toDegrees(Math.atan2(
                Math.sin(zombie.targetBodyYaw - zombie.bodyYaw),
                Math.cos(zombie.targetBodyYaw - zombie.bodyYaw)
            ));
            headTurn = Math.max(-35.0, Math.min(35.0, headTurn));
            float[] skinColor = mobSkinColor(zombie.kind);
            float[] torsoColor = mobTorsoColor(zombie.kind);
            float[] limbColor = mobLimbColor(zombie.kind);
            float[] legColor = mobLegColor(zombie.kind);
            boolean skeleton = zombie.kind == MobKind.SKELETON;

            if (zombie.kind == MobKind.PIG || zombie.kind == MobKind.SHEEP || zombie.kind == MobKind.COW) {
                renderQuadrupedMob(zombie, legSwing, headTurn, skinColor, torsoColor, legColor);
            } else if (zombie.kind == MobKind.VILLAGER) {
                renderVillagerMob(armSwing, legSwing, headTurn, skinColor, torsoColor, limbColor, legColor);
            } else {
                renderHumanoidMob(armSwing, legSwing, headTurn, skinColor, torsoColor, limbColor, legColor, skeleton);
            }

            if (zombie.fireTimer > 0.0) {
                drawCuboid(-0.30, 0.02, -0.30, 0.30, 1.65, 0.30, 1.0f, 0.34f, 0.06f);
            }

            glPopMatrix();
        }
    }

    private void renderHumanoidMob(double armSwing, double legSwing, double headTurn, float[] skinColor, float[] torsoColor, float[] limbColor, float[] legColor, boolean skeleton) {
        glPushMatrix();
        glTranslated(0.0, 1.585, 0.0);
        glRotated(-headTurn, 0.0, 1.0, 0.0);
        drawCuboid(-0.23, -0.235, -0.23, 0.23, 0.235, 0.23, skinColor[0], skinColor[1], skinColor[2]);
        drawCuboid(-0.12, -0.015, -0.245, -0.04, 0.065, -0.205, 0.04f, 0.04f, 0.04f);
        drawCuboid(0.04, -0.015, -0.245, 0.12, 0.065, -0.205, 0.04f, 0.04f, 0.04f);
        glPopMatrix();
        drawCuboid(-0.25, 0.73, -0.17, 0.25, 1.35, 0.17, torsoColor[0], torsoColor[1], torsoColor[2]);

        glPushMatrix();
        glTranslated(-0.30, 1.23, 0.0);
        glRotated(armSwing, 1.0, 0.0, 0.0);
        drawCuboid(-0.08, -0.50, -0.08, 0.08, 0.16, 0.08, limbColor[0], limbColor[1], limbColor[2]);
        glPopMatrix();

        glPushMatrix();
        glTranslated(0.30, 1.23, 0.0);
        glRotated(-armSwing, 1.0, 0.0, 0.0);
        drawCuboid(-0.08, -0.50, -0.08, 0.08, 0.16, 0.08, limbColor[0], limbColor[1], limbColor[2]);
        glPopMatrix();

        glPushMatrix();
        glTranslated(-0.12, 0.74, 0.0);
        glRotated(-legSwing, 1.0, 0.0, 0.0);
        drawCuboid(-0.09, -0.74, -0.09, 0.09, 0.0, 0.09, legColor[0], legColor[1], legColor[2]);
        glPopMatrix();

        glPushMatrix();
        glTranslated(0.12, 0.74, 0.0);
        glRotated(legSwing, 1.0, 0.0, 0.0);
        drawCuboid(-0.09, -0.74, -0.09, 0.09, 0.0, 0.09, legColor[0], legColor[1], legColor[2]);
        glPopMatrix();

        if (skeleton) {
            drawCuboid(-0.02, 0.76, -0.03, 0.02, 1.60, 0.03, 0.88f, 0.88f, 0.90f);
        }
    }

    private void renderVillagerMob(double armSwing, double legSwing, double headTurn, float[] skinColor, float[] torsoColor, float[] limbColor, float[] legColor) {
        glPushMatrix();
        glTranslated(0.0, 1.62, 0.0);
        glRotated(-headTurn, 0.0, 1.0, 0.0);
        drawCuboid(-0.27, -0.28, -0.25, 0.27, 0.27, 0.25, skinColor[0], skinColor[1], skinColor[2]);
        drawCuboid(-0.06, -0.06, -0.38, 0.06, 0.08, -0.22, 0.58f, 0.42f, 0.34f);
        drawCuboid(-0.13, 0.02, -0.265, -0.05, 0.10, -0.235, 0.04f, 0.04f, 0.04f);
        drawCuboid(0.05, 0.02, -0.265, 0.13, 0.10, -0.235, 0.04f, 0.04f, 0.04f);
        glPopMatrix();

        drawCuboid(-0.28, 0.58, -0.20, 0.28, 1.36, 0.20, torsoColor[0], torsoColor[1], torsoColor[2]);
        drawCuboid(-0.33, 0.86, -0.22, 0.33, 1.05, -0.02, limbColor[0], limbColor[1], limbColor[2]);
        drawCuboid(-0.18, 0.66, -0.24, 0.18, 0.92, -0.06, limbColor[0], limbColor[1], limbColor[2]);

        glPushMatrix();
        glTranslated(-0.10, 0.72, 0.0);
        glRotated(-legSwing, 1.0, 0.0, 0.0);
        drawCuboid(-0.09, -0.72, -0.09, 0.09, 0.0, 0.09, legColor[0], legColor[1], legColor[2]);
        glPopMatrix();

        glPushMatrix();
        glTranslated(0.10, 0.72, 0.0);
        glRotated(legSwing, 1.0, 0.0, 0.0);
        drawCuboid(-0.09, -0.72, -0.09, 0.09, 0.0, 0.09, legColor[0], legColor[1], legColor[2]);
        glPopMatrix();
    }

    private void renderQuadrupedMob(Zombie zombie, double legSwing, double headTurn, float[] skinColor, float[] torsoColor, float[] legColor) {
        double bodyMinY = zombie.kind == MobKind.SHEEP ? 0.56 : 0.52;
        double bodyMaxY = zombie.kind == MobKind.SHEEP ? 1.18 : 1.08;
        double bodyMinX = zombie.kind == MobKind.COW ? -0.36 : -0.31;
        double bodyMaxX = zombie.kind == MobKind.COW ? 0.36 : 0.31;
        double bodyMinZ = zombie.kind == MobKind.PIG ? -0.52 : -0.58;
        double bodyMaxZ = zombie.kind == MobKind.PIG ? 0.42 : 0.48;

        drawCuboid(bodyMinX, bodyMinY, bodyMinZ, bodyMaxX, bodyMaxY, bodyMaxZ, torsoColor[0], torsoColor[1], torsoColor[2]);
        if (zombie.kind == MobKind.SHEEP) {
            drawCuboid(-0.40, 0.54, -0.62, 0.40, 1.23, 0.50, 0.95f, 0.95f, 0.92f);
        }

        glPushMatrix();
        glTranslated(0.0, zombie.kind == MobKind.COW ? 0.98 : 0.94, zombie.kind == MobKind.PIG ? -0.66 : -0.72);
        glRotated(-headTurn * 0.75, 0.0, 1.0, 0.0);
        if (zombie.kind == MobKind.PIG) {
            drawCuboid(-0.22, -0.18, -0.20, 0.22, 0.18, 0.20, skinColor[0], skinColor[1], skinColor[2]);
            drawCuboid(-0.12, -0.08, -0.34, 0.12, 0.07, -0.19, 0.96f, 0.70f, 0.76f);
            drawCuboid(-0.11, -0.01, -0.35, -0.06, 0.04, -0.345, 0.54f, 0.22f, 0.28f);
            drawCuboid(0.06, -0.01, -0.35, 0.11, 0.04, -0.345, 0.54f, 0.22f, 0.28f);
        } else if (zombie.kind == MobKind.SHEEP) {
            drawCuboid(-0.19, -0.18, -0.18, 0.19, 0.18, 0.20, 0.25f, 0.23f, 0.20f);
            drawCuboid(-0.23, -0.22, -0.22, 0.23, 0.22, 0.23, 0.94f, 0.94f, 0.90f);
        } else {
            drawCuboid(-0.23, -0.20, -0.22, 0.23, 0.20, 0.22, skinColor[0], skinColor[1], skinColor[2]);
            drawCuboid(-0.14, -0.09, -0.35, 0.14, 0.07, -0.18, 0.66f, 0.52f, 0.38f);
            drawCuboid(-0.19, 0.11, -0.11, -0.11, 0.25, 0.03, 0.84f, 0.82f, 0.76f);
            drawCuboid(0.11, 0.11, -0.11, 0.19, 0.25, 0.03, 0.84f, 0.82f, 0.76f);
            drawCuboid(-0.33, -0.10, -0.06, -0.21, 0.06, 0.10, 0.28f, 0.18f, 0.10f);
            drawCuboid(0.21, -0.10, -0.06, 0.33, 0.06, 0.10, 0.28f, 0.18f, 0.10f);
        }
        drawCuboid(-0.11, 0.00, -0.225, -0.04, 0.07, -0.205, 0.03f, 0.03f, 0.03f);
        drawCuboid(0.04, 0.00, -0.225, 0.11, 0.07, -0.205, 0.03f, 0.03f, 0.03f);
        glPopMatrix();

        renderQuadrupedLeg(-0.21, 0.58, -0.38, -legSwing, legColor);
        renderQuadrupedLeg(0.21, 0.58, -0.38, legSwing, legColor);
        renderQuadrupedLeg(-0.21, 0.58, 0.28, legSwing, legColor);
        renderQuadrupedLeg(0.21, 0.58, 0.28, -legSwing, legColor);
    }

    private void renderQuadrupedLeg(double x, double y, double z, double swing, float[] color) {
        glPushMatrix();
        glTranslated(x, y, z);
        glRotated(swing, 1.0, 0.0, 0.0);
        drawCuboid(-0.075, -0.54, -0.075, 0.075, 0.0, 0.075, color[0], color[1], color[2]);
        glPopMatrix();
    }

    private float[] mobSkinColor(MobKind kind) {
        switch (kind) {
            case SKELETON:
                return new float[]{0.88f, 0.88f, 0.90f};
            case PIG:
                return new float[]{0.92f, 0.66f, 0.72f};
            case SHEEP:
                return new float[]{0.92f, 0.92f, 0.88f};
            case COW:
                return new float[]{0.40f, 0.28f, 0.16f};
            case VILLAGER:
                return new float[]{0.76f, 0.62f, 0.48f};
            case ZOMBIE:
            default:
                return new float[]{0.38f, 0.69f, 0.33f};
        }
    }

    private float[] mobTorsoColor(MobKind kind) {
        switch (kind) {
            case SKELETON:
                return new float[]{0.82f, 0.82f, 0.84f};
            case PIG:
                return new float[]{0.90f, 0.58f, 0.66f};
            case SHEEP:
                return new float[]{0.94f, 0.94f, 0.90f};
            case COW:
                return new float[]{0.32f, 0.20f, 0.12f};
            case VILLAGER:
                return new float[]{0.52f, 0.34f, 0.18f};
            case ZOMBIE:
            default:
                return new float[]{0.23f, 0.51f, 0.57f};
        }
    }

    private float[] mobLimbColor(MobKind kind) {
        switch (kind) {
            case SKELETON:
                return new float[]{0.84f, 0.84f, 0.86f};
            case VILLAGER:
                return new float[]{0.68f, 0.54f, 0.42f};
            default:
                return mobSkinColor(kind);
        }
    }

    private float[] mobLegColor(MobKind kind) {
        switch (kind) {
            case SKELETON:
                return new float[]{0.80f, 0.80f, 0.82f};
            case PIG:
                return new float[]{0.86f, 0.56f, 0.62f};
            case SHEEP:
                return new float[]{0.88f, 0.88f, 0.84f};
            case COW:
                return new float[]{0.24f, 0.16f, 0.10f};
            case VILLAGER:
                return new float[]{0.42f, 0.24f, 0.12f};
            case ZOMBIE:
            default:
                return new float[]{0.25f, 0.27f, 0.53f};
        }
    }

    private void renderDroppedItems(PlayerState player, double timeOfDay) {
        for (DroppedItem droppedItem : world.getDroppedItems()) {
            double dx = droppedItem.x - player.x;
            double dz = droppedItem.z - player.z;
            if (dx * dx + dz * dz > GameConfig.MAX_RENDER_DISTANCE * GameConfig.MAX_RENDER_DISTANCE) {
                continue;
            }
            float[] color = getHeldBlockColor(droppedItem.itemId);
            double bob = Math.sin(timeOfDay * Math.PI * 12.0 + droppedItem.spinDegrees * 0.04) * 0.04;
            glPushMatrix();
            glTranslated(
                droppedItem.x - renderCameraX,
                droppedItem.y - renderCameraY + 0.14 + bob,
                droppedItem.z - renderCameraZ
            );
            glRotated(droppedItem.spinDegrees, 0.0, 1.0, 0.0);
            drawCuboid(-0.12, -0.12, -0.12, 0.12, 0.12, 0.12, color[0], color[1], color[2]);
            glPopMatrix();
        }
    }

    private void renderFallingBlocks(PlayerState player) {
        for (FallingBlock fallingBlock : world.getFallingBlocks()) {
            if (!isSphereVisible(
                player,
                fallingBlock.x,
                fallingBlock.y + 0.5,
                fallingBlock.z,
                0.9,
                GameConfig.MAX_RENDER_DISTANCE
            )) {
                continue;
            }

            float[] color = getHeldBlockColor(fallingBlock.blockId);
            glPushMatrix();
            glTranslated(
                fallingBlock.x - renderCameraX,
                fallingBlock.y - renderCameraY,
                fallingBlock.z - renderCameraZ
            );
            drawCuboid(-0.49, 0.0, -0.49, 0.49, 0.98, 0.49, color[0], color[1], color[2]);
            glPopMatrix();
        }
    }

    private void renderPlayerModel(PlayerState player, PlayerInventory inventory, byte selectedBlock, boolean thirdPersonView) {
        if (!thirdPersonView) {
            return;
        }

        double swing = Math.sin(player.cameraBobPhase) * 26.0 * player.cameraBobAmount;
        glPushMatrix();
        glTranslated(player.x - renderCameraX, player.y - renderCameraY, player.z - renderCameraZ);
        glRotated(-Math.toDegrees(player.yaw) - 90.0, 0.0, 1.0, 0.0);
        drawPlayerModelParts(inventory, selectedBlock, swing, player.pitch, true, player.sneaking);
        if (player.fireTimer > 0.0) {
            drawCuboid(-0.30, 0.02, -0.30, 0.30, 1.65, 0.30, 1.0f, 0.34f, 0.06f);
        }
        glPopMatrix();
    }

    private void drawPlayerModelParts(PlayerInventory inventory, byte selectedBlock, double swing, double pitch, boolean showHeldItem, boolean sneakingPose) {
        float[] helmet = inventory == null ? null : armorColor(inventory.getArmorStack(0));
        float[] chest = inventory == null ? null : armorColor(inventory.getArmorStack(1));
        float[] legs = inventory == null ? null : armorColor(inventory.getArmorStack(2));
        float[] boots = inventory == null ? null : armorColor(inventory.getArmorStack(3));
        double crouch = sneakingPose ? -0.23 : 0.0;
        double legTop = sneakingPose ? 0.62 : 0.74;
        double legBottom = sneakingPose ? -0.60 : -0.74;

        glPushMatrix();
        glTranslated(0.0, 1.59 + crouch, sneakingPose ? -0.04 : 0.0);
        glRotated(Math.toDegrees(pitch), 1.0, 0.0, 0.0);
        drawCuboid(-0.23, -0.23, -0.23, 0.23, 0.23, 0.23, 0.91f, 0.78f, 0.63f);
        if (helmet != null) {
            drawCuboid(-0.255, -0.255, -0.255, 0.255, 0.07, 0.255, helmet[0], helmet[1], helmet[2]);
        }
        glPopMatrix();

        float bodyR = chest == null ? 0.22f : chest[0];
        float bodyG = chest == null ? 0.47f : chest[1];
        float bodyB = chest == null ? 0.85f : chest[2];
        drawCuboid(-0.25, 0.73 + crouch, -0.17, 0.25, 1.36 + crouch, 0.17, bodyR, bodyG, bodyB);

        glPushMatrix();
        glTranslated(-0.30, 1.23 + crouch, 0.0);
        glRotated(swing, 1.0, 0.0, 0.0);
        drawCuboid(-0.08, -0.50, -0.08, 0.08, 0.16, 0.08, bodyR, bodyG, bodyB);
        glPopMatrix();

        glPushMatrix();
        glTranslated(0.30, 1.23 + crouch, 0.0);
        glRotated(-swing, 1.0, 0.0, 0.0);
        drawCuboid(-0.08, -0.50, -0.08, 0.08, 0.16, 0.08, bodyR, bodyG, bodyB);
        if (showHeldItem && selectedBlock != GameConfig.AIR) {
            float[] heldColor = getHeldBlockColor(selectedBlock);
            glPushMatrix();
            glTranslated(0.0, -0.40, -0.08);
            drawCuboid(-0.06, -0.06, -0.06, 0.06, 0.06, 0.06, heldColor[0], heldColor[1], heldColor[2]);
            glPopMatrix();
        }
        glPopMatrix();

        float legR = legs == null ? 0.24f : legs[0];
        float legG = legs == null ? 0.30f : legs[1];
        float legB = legs == null ? 0.66f : legs[2];
        float footR = boots == null ? legR : boots[0];
        float footG = boots == null ? legG : boots[1];
        float footB = boots == null ? legB : boots[2];

        glPushMatrix();
        glTranslated(-0.12, legTop, 0.0);
        glRotated(-swing * 1.2, 1.0, 0.0, 0.0);
        drawCuboid(-0.09, legBottom, -0.09, 0.09, 0.0, 0.09, legR, legG, legB);
        drawCuboid(-0.095, legBottom, -0.095, 0.095, legBottom + 0.22, 0.095, footR, footG, footB);
        glPopMatrix();

        glPushMatrix();
        glTranslated(0.12, legTop, 0.0);
        glRotated(swing * 1.2, 1.0, 0.0, 0.0);
        drawCuboid(-0.09, legBottom, -0.09, 0.09, 0.0, 0.09, legR, legG, legB);
        drawCuboid(-0.095, legBottom, -0.095, 0.095, legBottom + 0.22, 0.095, footR, footG, footB);
        glPopMatrix();
    }

    private void drawCuboid(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, float red, float green, float blue) {
        glBegin(GL_QUADS);
        shadeColor(red, green, blue, 0.80f);
        glVertex3d(minX, minY, minZ);
        glVertex3d(minX, maxY, minZ);
        glVertex3d(minX, maxY, maxZ);
        glVertex3d(minX, minY, maxZ);

        shadeColor(red, green, blue, 0.80f);
        glVertex3d(maxX, minY, maxZ);
        glVertex3d(maxX, maxY, maxZ);
        glVertex3d(maxX, maxY, minZ);
        glVertex3d(maxX, minY, minZ);

        shadeColor(red, green, blue, 1.00f);
        glVertex3d(minX, maxY, maxZ);
        glVertex3d(minX, maxY, minZ);
        glVertex3d(maxX, maxY, minZ);
        glVertex3d(maxX, maxY, maxZ);

        shadeColor(red, green, blue, 0.56f);
        glVertex3d(minX, minY, minZ);
        glVertex3d(minX, minY, maxZ);
        glVertex3d(maxX, minY, maxZ);
        glVertex3d(maxX, minY, minZ);

        shadeColor(red, green, blue, 0.72f);
        glVertex3d(maxX, minY, minZ);
        glVertex3d(maxX, maxY, minZ);
        glVertex3d(minX, maxY, minZ);
        glVertex3d(minX, minY, minZ);

        shadeColor(red, green, blue, 0.72f);
        glVertex3d(minX, minY, maxZ);
        glVertex3d(minX, maxY, maxZ);
        glVertex3d(maxX, maxY, maxZ);
        glVertex3d(maxX, minY, maxZ);
        glEnd();
    }

    private void renderHoveredOutline(RayHit hoveredBlock) {
        if (hoveredBlock == null) {
            return;
        }
        byte hoveredType = world.getBlock(hoveredBlock.x, hoveredBlock.y, hoveredBlock.z);
        if (world.isLiquidBlock(hoveredType)) {
            return;
        }

        glLineWidth(2.0f);
        glColor3f(1.0f, 1.0f, 1.0f);

        double outlineOffset = 0.0025;
        BlockState state = world.getBlockState(hoveredBlock.x, hoveredBlock.y, hoveredBlock.z);
        if (hoveredType == GameConfig.OAK_DOOR) {
            int lowerY = Blocks.isDoorUpper(state) ? hoveredBlock.y - 1 : hoveredBlock.y;
            BlockState lower = world.getBlockState(hoveredBlock.x, lowerY, hoveredBlock.z);
            double[] bounds = doorSelectionBounds(lower);
            drawAabbOutline(
                hoveredBlock.x + bounds[0] - outlineOffset,
                lowerY - outlineOffset,
                hoveredBlock.z + bounds[2] - outlineOffset,
                hoveredBlock.x + bounds[3] + outlineOffset,
                lowerY + 2.0 + outlineOffset,
                hoveredBlock.z + bounds[5] + outlineOffset
            );
            return;
        }
        if (hoveredType == GameConfig.RED_BED) {
            int facing = Blocks.bedFacing(state);
            int footX = Blocks.isBedHead(state) ? hoveredBlock.x - facingDx(facing) : hoveredBlock.x;
            int footZ = Blocks.isBedHead(state) ? hoveredBlock.z - facingDz(facing) : hoveredBlock.z;
            int headX = footX + facingDx(facing);
            int headZ = footZ + facingDz(facing);
            drawAabbOutline(
                Math.min(footX, headX) - outlineOffset,
                hoveredBlock.y - outlineOffset,
                Math.min(footZ, headZ) - outlineOffset,
                Math.max(footX, headX) + 1.0 + outlineOffset,
                hoveredBlock.y + 0.56 + outlineOffset,
                Math.max(footZ, headZ) + 1.0 + outlineOffset
            );
            return;
        }
        double[] bounds = selectionBounds(hoveredType, state);
        double minX = hoveredBlock.x + bounds[0] - outlineOffset - renderCameraX;
        double minY = hoveredBlock.y + bounds[1] - outlineOffset - renderCameraY;
        double minZ = hoveredBlock.z + bounds[2] - outlineOffset - renderCameraZ;
        double maxX = hoveredBlock.x + bounds[3] + outlineOffset - renderCameraX;
        double maxY = hoveredBlock.y + bounds[4] + outlineOffset - renderCameraY;
        double maxZ = hoveredBlock.z + bounds[5] + outlineOffset - renderCameraZ;

        glBegin(GL_LINE_LOOP);
        glVertex3d(minX, minY, minZ);
        glVertex3d(maxX, minY, minZ);
        glVertex3d(maxX, minY, maxZ);
        glVertex3d(minX, minY, maxZ);
        glEnd();

        glBegin(GL_LINE_LOOP);
        glVertex3d(minX, maxY, minZ);
        glVertex3d(maxX, maxY, minZ);
        glVertex3d(maxX, maxY, maxZ);
        glVertex3d(minX, maxY, maxZ);
        glEnd();

        glBegin(GL_LINES);
        glVertex3d(minX, minY, minZ);
        glVertex3d(minX, maxY, minZ);
        glVertex3d(maxX, minY, minZ);
        glVertex3d(maxX, maxY, minZ);
        glVertex3d(maxX, minY, maxZ);
        glVertex3d(maxX, maxY, maxZ);
        glVertex3d(minX, minY, maxZ);
        glVertex3d(minX, maxY, maxZ);
        glEnd();
    }

    private void drawAabbOutline(double worldMinX, double worldMinY, double worldMinZ,
                                 double worldMaxX, double worldMaxY, double worldMaxZ) {
        double minX = worldMinX - renderCameraX;
        double minY = worldMinY - renderCameraY;
        double minZ = worldMinZ - renderCameraZ;
        double maxX = worldMaxX - renderCameraX;
        double maxY = worldMaxY - renderCameraY;
        double maxZ = worldMaxZ - renderCameraZ;

        glBegin(GL_LINE_LOOP);
        glVertex3d(minX, minY, minZ);
        glVertex3d(maxX, minY, minZ);
        glVertex3d(maxX, minY, maxZ);
        glVertex3d(minX, minY, maxZ);
        glEnd();

        glBegin(GL_LINE_LOOP);
        glVertex3d(minX, maxY, minZ);
        glVertex3d(maxX, maxY, minZ);
        glVertex3d(maxX, maxY, maxZ);
        glVertex3d(minX, maxY, maxZ);
        glEnd();

        glBegin(GL_LINES);
        glVertex3d(minX, minY, minZ);
        glVertex3d(minX, maxY, minZ);
        glVertex3d(maxX, minY, minZ);
        glVertex3d(maxX, maxY, minZ);
        glVertex3d(maxX, minY, maxZ);
        glVertex3d(maxX, maxY, maxZ);
        glVertex3d(minX, minY, maxZ);
        glVertex3d(minX, maxY, maxZ);
        glEnd();
    }

    private double[] selectionBounds(byte block, BlockState state) {
        switch (block) {
            case GameConfig.SNOW_LAYER:
                return new double[]{0.0, 0.0, 0.0, 1.0, 0.125, 1.0};
            case GameConfig.FARMLAND:
                return new double[]{0.0, 0.0, 0.0, 1.0, 0.9375, 1.0};
            case GameConfig.WHEAT_CROP:
            case GameConfig.TALL_GRASS:
            case GameConfig.RED_FLOWER:
            case GameConfig.YELLOW_FLOWER:
            case GameConfig.SEAGRASS:
                return new double[]{0.18, 0.0, 0.18, 0.82, 0.86, 0.82};
            case GameConfig.RAIL:
                return new double[]{0.0, 0.0, 0.0, 1.0, 0.075, 1.0};
            case GameConfig.TORCH:
                return new double[]{0.40, 0.0, 0.40, 0.60, 0.82, 0.60};
            case GameConfig.OAK_FENCE:
                return new double[]{0.25, 0.0, 0.25, 0.75, 1.0, 0.75};
            case GameConfig.RED_BED:
                return new double[]{0.0, 0.0, 0.0, 1.0, 0.56, 1.0};
            case GameConfig.OAK_DOOR:
                return doorSelectionBounds(state);
            default:
                return new double[]{0.0, 0.0, 0.0, 1.0, 1.0, 1.0};
        }
    }

    private double[] doorSelectionBounds(BlockState state) {
        int facing = Blocks.doorFacing(state);
        boolean open = Blocks.isDoorOpen(state);
        double t = 0.16;
        int visualFacing = open ? ((facing + 1) & 3) : facing;
        switch (visualFacing) {
            case 0:
                return new double[]{0.0, 0.0, 0.0, 1.0, 1.0, t};
            case 1:
                return new double[]{1.0 - t, 0.0, 0.0, 1.0, 1.0, 1.0};
            case 2:
                return new double[]{0.0, 0.0, 1.0 - t, 1.0, 1.0, 1.0};
            default:
                return new double[]{0.0, 0.0, 0.0, t, 1.0, 1.0};
        }
    }

    private int facingDx(int facing) {
        return facing == 1 ? 1 : (facing == 3 ? -1 : 0);
    }

    private int facingDz(int facing) {
        return facing == 2 ? 1 : (facing == 0 ? -1 : 0);
    }

    private void renderBreakingOverlay(RayHit breakingBlock, double breakingProgress) {
        if (breakingBlock == null || breakingProgress <= 0.0) {
            return;
        }

        double offset = 0.004 + breakingProgress * 0.004;
        byte block = world.getBlock(breakingBlock.x, breakingBlock.y, breakingBlock.z);
        BlockState state = world.getBlockState(breakingBlock.x, breakingBlock.y, breakingBlock.z);
        double[] bounds = selectionBounds(block, state);
        double baseX = breakingBlock.x;
        double baseY = breakingBlock.y;
        double baseZ = breakingBlock.z;
        if (block == GameConfig.OAK_DOOR) {
            if (Blocks.isDoorUpper(state)) {
                baseY -= 1.0;
            }
            bounds = doorSelectionBounds(world.getBlockState((int) baseX, (int) baseY, (int) baseZ));
            bounds[4] = 2.0;
        } else if (block == GameConfig.RED_BED) {
            int facing = Blocks.bedFacing(state);
            int footX = Blocks.isBedHead(state) ? breakingBlock.x - facingDx(facing) : breakingBlock.x;
            int footZ = Blocks.isBedHead(state) ? breakingBlock.z - facingDz(facing) : breakingBlock.z;
            int headX = footX + facingDx(facing);
            int headZ = footZ + facingDz(facing);
            baseX = Math.min(footX, headX);
            baseZ = Math.min(footZ, headZ);
            bounds = new double[]{0.0, 0.0, 0.0, Math.abs(headX - footX) + 1.0, 0.56, Math.abs(headZ - footZ) + 1.0};
        }
        double minX = baseX + bounds[0] - offset - renderCameraX;
        double minY = baseY + bounds[1] - offset - renderCameraY;
        double minZ = baseZ + bounds[2] - offset - renderCameraZ;
        double maxX = baseX + bounds[3] + offset - renderCameraX;
        double maxY = baseY + bounds[4] + offset - renderCameraY;
        double maxZ = baseZ + bounds[5] + offset - renderCameraZ;

        glColor4f(1.0f, 0.95f, 0.72f, (float) (0.05 + breakingProgress * 0.22));
        glBegin(GL_QUADS);
        glVertex3d(minX, minY, minZ);
        glVertex3d(minX, maxY, minZ);
        glVertex3d(minX, maxY, maxZ);
        glVertex3d(minX, minY, maxZ);

        glVertex3d(maxX, minY, maxZ);
        glVertex3d(maxX, maxY, maxZ);
        glVertex3d(maxX, maxY, minZ);
        glVertex3d(maxX, minY, minZ);

        glVertex3d(minX, maxY, maxZ);
        glVertex3d(minX, maxY, minZ);
        glVertex3d(maxX, maxY, minZ);
        glVertex3d(maxX, maxY, maxZ);

        glVertex3d(minX, minY, minZ);
        glVertex3d(minX, minY, maxZ);
        glVertex3d(maxX, minY, maxZ);
        glVertex3d(maxX, minY, minZ);

        glVertex3d(maxX, minY, minZ);
        glVertex3d(maxX, maxY, minZ);
        glVertex3d(minX, maxY, minZ);
        glVertex3d(minX, minY, minZ);

        glVertex3d(minX, minY, maxZ);
        glVertex3d(minX, maxY, maxZ);
        glVertex3d(maxX, maxY, maxZ);
        glVertex3d(maxX, minY, maxZ);
        glEnd();
    }

    private void renderOverlay(PlayerState player, PlayerInventory inventory, RayHit hoveredBlock, boolean paused, boolean inventoryOpen, int inventoryScreenMode, ContainerInventory chestContainer, FurnaceBlockEntity furnace, boolean deathScreenActive, int deathSelection, boolean mainMenuActive, int mainMenuScreen, int mainMenuSelection, boolean mainMenuWorldActionsEnabled, String createWorldName, String createWorldSeed, int createWorldGameMode, int createWorldDifficulty, int activeMenuTextField, String renameWorldName, List<WorldInfo> worlds, int selectedWorldIndex, int mainMenuScrollOffset, String loadedWorldName, boolean showDebugInfo, boolean hideHud, int pauseSelection, boolean gameModeSwitcherActive, int gameModeSelection, byte selectedBlock, int selectedSlot, int creativeTab, boolean creativeMode, boolean thirdPersonView, int renderDistanceChunks, int fovDegrees, double timeOfDay, double mouseX, double mouseY, ChatSystem chat) {
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0.0, framebufferWidth, framebufferHeight, 0.0, -1.0, 1.0);

        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();

        glDisable(GL_DEPTH_TEST);

        boolean minimalHud = player.spectatorMode;
        boolean blockingOverlay = paused || inventoryOpen || deathScreenActive || mainMenuActive;
        if (!hideHud && !minimalHud && !blockingOverlay) {
            drawCrosshair();
            if (!thirdPersonView) {
                drawFirstPersonHand(player, selectedBlock);
            }
            drawHotbar(inventory, selectedSlot);
            if (!player.creativeMode) {
                drawHealthBar(player.health, inventory);
                drawHungerBar(player.hunger);
                drawArmorBar(inventory);
                drawAirBar(player);
            }
        }
        if (inventoryOpen) {
            boolean creativeInventory = creativeMode && inventoryScreenMode == GameConfig.INVENTORY_SCREEN_PLAYER;
            renderInventoryScreen(player, inventory, creativeInventory, selectedSlot, creativeTab, inventoryScreenMode, chestContainer, furnace, mouseX, mouseY);
        }
        if (!blockingOverlay && player.fireTimer > 0.0) {
            renderFireOverlay(player.fireTimer);
        }
        if (!hideHud && showDebugInfo) {
            renderDebugOverlay(player, selectedBlock, creativeMode, timeOfDay);
        }
        if (!mainMenuActive && !deathScreenActive) {
            renderChat(chat);
        }
        if (mainMenuActive) {
            renderMainMenu(mainMenuScreen, mainMenuSelection, mainMenuWorldActionsEnabled, createWorldName, createWorldSeed, createWorldGameMode, createWorldDifficulty, activeMenuTextField, renameWorldName, worlds, selectedWorldIndex, mainMenuScrollOffset, loadedWorldName, renderDistanceChunks, fovDegrees);
        }
        if (deathScreenActive) {
            renderDeathScreen(deathSelection);
        }
        if (paused) {
            renderPauseMenu(pauseSelection);
        }
        if (gameModeSwitcherActive) {
            renderGameModeSwitcher(gameModeSelection);
        }

        glEnable(GL_DEPTH_TEST);

        glMatrixMode(GL_MODELVIEW);
        glPopMatrix();
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
    }

    private void renderChat(ChatSystem chat) {
        if (chat == null) {
            return;
        }
        if (!chat.isActive()) {
            return;
        }
        List<String> messages = chat.visibleMessages();

        float uiScale = getUiScale();
        float lineHeight = 18.0f * uiScale;
        float x = 14.0f * uiScale;
        float textScale = uiScale * 0.78f;
        float maxTextWidth = Math.max(96.0f * uiScale, framebufferWidth - 36.0f * uiScale);
        List<String> wrappedMessages = wrapChatMessages(messages, textScale, maxTextWidth);
        float y = framebufferHeight - 78.0f * uiScale - wrappedMessages.size() * lineHeight;
        for (String message : wrappedMessages) {
            float width = Math.min(framebufferWidth - 28.0f * uiScale, measureTextWidth(message, textScale) + 12.0f * uiScale);
            drawRect(x - 5.0f * uiScale, y - 12.0f * uiScale, width, 17.0f * uiScale, 0.02f, 0.02f, 0.03f, 0.44f);
            drawShadowText(x, y, textScale, message, 0.92f, 0.94f, 0.98f);
            y += lineHeight;
        }

        if (chat.isActive()) {
            String input = "> " + chat.inputText() + "_";
            float inputY = framebufferHeight - 34.0f * uiScale;
            drawRect(10.0f * uiScale, inputY - 18.0f * uiScale, framebufferWidth - 20.0f * uiScale, 26.0f * uiScale, 0.02f, 0.02f, 0.03f, 0.74f);
            drawOutline(10.0f * uiScale, inputY - 18.0f * uiScale, framebufferWidth - 20.0f * uiScale, 26.0f * uiScale, uiScale, 0.50f, 0.64f, 0.82f, 0.88f);
            drawShadowText(18.0f * uiScale, inputY, uiScale * 0.86f, trimTextToWidth(input, uiScale * 0.86f, framebufferWidth - 36.0f * uiScale), 1.0f, 1.0f, 1.0f);
        }
    }

    private List<String> wrapChatMessages(List<String> messages, float scale, float maxWidth) {
        ArrayList<String> wrapped = new ArrayList<>();
        for (String message : messages) {
            wrapChatLine(message == null ? "" : message, scale, maxWidth, wrapped);
        }
        int maxLines = 8;
        if (wrapped.size() <= maxLines) {
            return wrapped;
        }
        return new ArrayList<>(wrapped.subList(wrapped.size() - maxLines, wrapped.size()));
    }

    private void wrapChatLine(String line, float scale, float maxWidth, ArrayList<String> output) {
        if (measureTextWidth(line, scale) <= maxWidth) {
            output.add(line);
            return;
        }
        String[] words = line.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (measureTextWidth(candidate, scale) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (current.length() > 0) {
                output.add(current.toString());
                current.setLength(0);
            }
            if (measureTextWidth(word, scale) <= maxWidth) {
                current.append(word);
            } else {
                splitLongChatWord(word, scale, maxWidth, output);
            }
        }
        if (current.length() > 0) {
            output.add(current.toString());
        }
    }

    private void splitLongChatWord(String word, float scale, float maxWidth, ArrayList<String> output) {
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            String candidate = part.toString() + c;
            if (part.length() > 0 && measureTextWidth(candidate, scale) > maxWidth) {
                output.add(part.toString());
                part.setLength(0);
            }
            part.append(c);
        }
        if (part.length() > 0) {
            output.add(part.toString());
        }
    }

    private String trimTextToWidth(String text, float scale, float maxWidth) {
        if (measureTextWidth(text, scale) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int end = Math.max(0, text.length());
        while (end > 0 && measureTextWidth(text.substring(0, end) + ellipsis, scale) > maxWidth) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    private void drawHotbar(PlayerInventory inventory, int selectedSlot) {
        float uiScale = getUiScale();
        float slotSize = 28.0f * uiScale;
        float spacing = 6.0f * uiScale;
        float totalWidth = slotSize * 9.0f + spacing * 8.0f;
        float startX = framebufferWidth * 0.5f - totalWidth * 0.5f;
        float y = framebufferHeight - 42.0f * uiScale;

        for (int slot = 0; slot < 9; slot++) {
            float x = startX + slot * (slotSize + spacing);
            drawItemSlot(x, y, slotSize, inventory.getHotbarStack(slot), slot == selectedSlot, false, null);
        }
    }

    private void drawCrosshair() {
        float uiScale = getUiScale();
        float centerX = framebufferWidth * 0.5f;
        float centerY = framebufferHeight * 0.5f;
        float halfSize = 9.0f * uiScale;
        glLineWidth(Math.max(1.4f, 1.8f * uiScale));
        glColor4f(1.0f, 1.0f, 1.0f, 0.95f);
        glBegin(GL_LINES);
        glVertex3d(centerX - halfSize, centerY, 0.0);
        glVertex3d(centerX + halfSize, centerY, 0.0);
        glVertex3d(centerX, centerY - halfSize, 0.0);
        glVertex3d(centerX, centerY + halfSize, 0.0);
        glEnd();
    }

    private void renderFireOverlay(double fireTimer) {
        float alpha = clampColor((float) Math.min(0.42, 0.16 + fireTimer * 0.04));
        float flameHeight = framebufferHeight * 0.30f;
        drawRect(0.0f, framebufferHeight - flameHeight, framebufferWidth, flameHeight, 1.0f, 0.24f, 0.02f, alpha);
        drawRect(0.0f, 0.0f, framebufferWidth * 0.08f, framebufferHeight, 0.95f, 0.08f, 0.01f, alpha * 0.55f);
        drawRect(framebufferWidth * 0.92f, 0.0f, framebufferWidth * 0.08f, framebufferHeight, 0.95f, 0.08f, 0.01f, alpha * 0.55f);
    }

    private void drawHealthBar(int health, PlayerInventory inventory) {
        float uiScale = getUiScale();
        float heartScale = 1.52f * uiScale;
        float spacing = 0.9f * uiScale;
        float slotSize = 28.0f * uiScale;
        float slotSpacing = 6.0f * uiScale;
        float hotbarWidth = slotSize * 9.0f + slotSpacing * 8.0f;
        float startX = framebufferWidth * 0.5f - hotbarWidth * 0.5f;
        float y = framebufferHeight - 58.0f * uiScale;

        for (int heart = 0; heart < 10; heart++) {
            boolean filled = health >= (heart + 1) * 2;
            drawHeart(startX + heart * (heartScale * 8.0f + spacing), y, heartScale, filled);
        }
    }

    private void drawArmorBar(PlayerInventory inventory) {
        int armor = inventory.getArmorProtection();
        if (armor <= 0) {
            return;
        }

        float uiScale = getUiScale();
        float scale = 1.42f * uiScale;
        float spacing = 1.1f * uiScale;
        float slotSize = 28.0f * uiScale;
        float slotSpacing = 6.0f * uiScale;
        float hotbarWidth = slotSize * 9.0f + slotSpacing * 8.0f;
        float startX = framebufferWidth * 0.5f - hotbarWidth * 0.5f;
        float y = framebufferHeight - 76.0f * uiScale;

        for (int shield = 0; shield < 10; shield++) {
            int point = (shield + 1) * 2;
            boolean filled = armor >= point;
            boolean half = !filled && armor == point - 1;
            drawArmorShield(startX + shield * (scale * 8.0f + spacing), y, scale, filled, half);
        }
    }

    private void drawHungerBar(int hunger) {
        float uiScale = getUiScale();
        float scale = 1.32f * uiScale;
        float spacing = 1.2f * uiScale;
        float slotSize = 28.0f * uiScale;
        float slotSpacing = 6.0f * uiScale;
        float hotbarWidth = slotSize * 9.0f + slotSpacing * 8.0f;
        float startX = framebufferWidth * 0.5f + hotbarWidth * 0.5f - 10.0f * (scale * 7.0f + spacing);
        float y = framebufferHeight - 58.0f * uiScale;
        for (int food = 0; food < 10; food++) {
            boolean filled = hunger >= (food + 1) * 2;
            drawFoodIcon(startX + food * (scale * 7.0f + spacing), y, scale, filled);
        }
    }

    private void drawFoodIcon(float x, float y, float scale, boolean filled) {
        float r = filled ? 0.86f : 0.24f;
        float g = filled ? 0.46f : 0.24f;
        float b = filled ? 0.18f : 0.26f;
        drawRect(x + 1.0f * scale, y + 1.0f * scale, 5.0f * scale, 5.0f * scale, r, g, b, 0.96f);
        drawRect(x + 4.0f * scale, y - 1.0f * scale, 2.0f * scale, 3.0f * scale, 0.72f, 0.36f, 0.14f, 0.96f);
        drawRect(x + 2.0f * scale, y + 6.0f * scale, 3.0f * scale, 1.0f * scale, r * 0.75f, g * 0.75f, b * 0.75f, 0.96f);
    }

    private void drawFirstPersonHand(PlayerState player, byte selectedItem) {
        float uiScale = getUiScale();
        double bob = Math.sin(player.cameraBobPhase * 1.7) * 5.0 * player.cameraBobAmount;
        double swingAmount = player.handSwingTimer <= 0.0 ? 0.0 : Math.sin((1.0 - player.handSwingTimer / 0.22) * Math.PI);
        float handWidth = 30.0f * uiScale;
        float handHeight = 78.0f * uiScale;
        float x = framebufferWidth - 128.0f * uiScale + (float) bob - (float) (swingAmount * 20.0 * uiScale);
        float y = framebufferHeight - 92.0f * uiScale + (player.sneaking ? 8.0f * uiScale : 0.0f) + (float) (swingAmount * 15.0 * uiScale);
        drawRect(x, y, handWidth, handHeight, 0.86f, 0.66f, 0.48f, 0.98f);
        drawRect(x + handWidth * 0.14f, y + handHeight * 0.18f, handWidth * 0.72f, handHeight * 0.58f, 0.74f, 0.54f, 0.38f, 0.98f);
        drawRect(x - handWidth * 0.08f, y + handHeight * 0.74f, handWidth * 1.16f, handHeight * 0.20f, 0.12f, 0.34f, 0.70f, 0.98f);
        if (selectedItem != GameConfig.AIR) {
            drawItemIcon(selectedItem, x - 11.0f * uiScale, y - 20.0f * uiScale, 26.0f * uiScale);
        }
    }

    private void drawAirBar(PlayerState player) {
        if (!player.headInWater && player.airUnits >= GameConfig.MAX_AIR_UNITS) {
            return;
        }

        float uiScale = getUiScale();
        float bubbleSize = 6.0f * uiScale;
        float spacing = 2.0f * uiScale;
        float slotSize = 28.0f * uiScale;
        float slotSpacing = 6.0f * uiScale;
        float hotbarWidth = slotSize * 9.0f + slotSpacing * 8.0f;
        float startX = framebufferWidth * 0.5f - hotbarWidth * 0.5f;
        float y = framebufferHeight - 93.0f * uiScale;

        for (int bubble = 0; bubble < GameConfig.MAX_AIR_UNITS; bubble++) {
            boolean filled = bubble < player.airUnits;
            float x = startX + bubble * (bubbleSize + spacing);
            drawRect(x + bubbleSize * 0.15f, y, bubbleSize * 0.70f, bubbleSize, 0.30f, 0.74f, 0.94f, filled ? 0.96f : 0.24f);
            drawRect(x, y + bubbleSize * 0.20f, bubbleSize, bubbleSize * 0.58f, 0.36f, 0.84f, 1.0f, filled ? 0.96f : 0.24f);
        }
    }

    private void drawArmorShield(float x, float y, float scale, boolean filled, boolean half) {
        float alpha = filled || half ? 0.98f : 0.30f;
        float red = filled || half ? 0.72f : 0.36f;
        float green = filled || half ? 0.76f : 0.38f;
        float blue = filled || half ? 0.82f : 0.42f;
        drawRect(x + scale * 1.0f, y, scale * 5.0f, scale * 2.0f, red, green, blue, alpha);
        drawRect(x, y + scale * 2.0f, scale * 7.0f, scale * 3.0f, red * 0.92f, green * 0.92f, blue * 0.92f, alpha);
        drawRect(x + scale * 2.0f, y + scale * 5.0f, scale * 3.0f, scale * 2.0f, red * 0.82f, green * 0.82f, blue * 0.82f, alpha);
        if (half) {
            drawRect(x + scale * 4.0f, y, scale * 3.5f, scale * 7.0f, 0.05f, 0.05f, 0.06f, 0.45f);
        }
    }

    private void drawHeart(float x, float y, float scale, boolean filled) {
        String[] pattern = {
            "0110110",
            "1111111",
            "1111111",
            "0111110",
            "0011100",
            "0001000"
        };
        float[] color = filled
            ? new float[]{0.92f, 0.18f, 0.22f}
            : new float[]{0.28f, 0.10f, 0.12f};

        for (int row = 0; row < pattern.length; row++) {
            for (int column = 0; column < pattern[row].length(); column++) {
                if (pattern[row].charAt(column) != '1') {
                    continue;
                }
                drawRect(x + column * scale, y + row * scale, scale, scale, color[0], color[1], color[2], 0.98f);
            }
        }
    }

    private void renderDebugOverlay(PlayerState player, byte selectedBlock, boolean creativeMode, double timeOfDay) {
        float uiScale = getUiScale();
        float panelX = 12.0f * uiScale;
        float panelY = 12.0f * uiScale;
        float panelWidth = 610.0f * uiScale;
        float panelHeight = 288.0f * uiScale;
        drawRect(panelX, panelY, panelWidth, panelHeight, 0.0f, 0.0f, 0.0f, 0.58f);

        int blockX = (int) Math.floor(player.x);
        int blockY = (int) Math.floor(player.y);
        int blockZ = (int) Math.floor(player.z);
        int chunkX = Math.floorDiv(blockX, GameConfig.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(blockZ, GameConfig.CHUNK_SIZE);
        int sectionIndex = GameConfig.sectionIndexForY(blockY);
        int localX = Math.floorMod(blockX, GameConfig.CHUNK_SIZE);
        int localY = GameConfig.localYForWorldY(blockY);
        int localZ = Math.floorMod(blockZ, GameConfig.CHUNK_SIZE);
        int underY = (int) Math.floor(player.y - 0.12);
        BlockType underType = Blocks.typeFromLegacyId(world.getBlock(blockX, underY, blockZ));
        String facing = facingName(player.yaw);

        drawText(20.0f * uiScale, 28.0f * uiScale, uiScale, "TinyMinecraft OpenGL", 1.0f, 1.0f, 1.0f);
        drawText(20.0f * uiScale, 50.0f * uiScale, uiScale, "FPS: " + format(debugFps), 1.0f, 1.0f, 1.0f);
        drawText(20.0f * uiScale, 72.0f * uiScale, uiScale, "XYZ: " + format(player.x) + " / " + format(player.y) + " / " + format(player.z), 1.0f, 1.0f, 1.0f);
        drawText(20.0f * uiScale, 94.0f * uiScale, uiScale, "Chunk: " + chunkX + " / " + sectionIndex + " / " + chunkZ + " local " + localX + " / " + localY + " / " + localZ, 1.0f, 1.0f, 1.0f);
        drawText(20.0f * uiScale, 116.0f * uiScale, uiScale, "Facing: " + facing + " yaw " + format(Math.toDegrees(player.yaw)) + " pitch " + format(Math.toDegrees(player.pitch)), 1.0f, 1.0f, 1.0f);
        drawText(20.0f * uiScale, 138.0f * uiScale, uiScale, "Biome: " + world.getBiomeName(blockX, blockZ), 1.0f, 1.0f, 1.0f);
        drawText(20.0f * uiScale, 160.0f * uiScale, uiScale, "Surface: approx " + world.getGeneratedSurfaceHeight(blockX, blockZ) + " actual " + world.getActualSurfaceHeight(blockX, blockZ), 1.0f, 1.0f, 1.0f);
        drawText(20.0f * uiScale, 182.0f * uiScale, uiScale, "Seed: " + Long.toUnsignedString(world.getSeed(), 16), 1.0f, 1.0f, 1.0f);
        drawText(20.0f * uiScale, 204.0f * uiScale, uiScale, "Status: " + world.getChunkStatus(chunkX, chunkZ) + " Section: " + sectionIndex, 1.0f, 1.0f, 1.0f);
        drawText(20.0f * uiScale, 226.0f * uiScale, uiScale, "Region: " + world.getRegionFileName(chunkX, chunkZ), 1.0f, 1.0f, 1.0f);
        drawText(20.0f * uiScale, 248.0f * uiScale, uiScale, "Under: " + underType.namespacedId + " y=" + underY, 1.0f, 1.0f, 1.0f);
        drawText(20.0f * uiScale, 270.0f * uiScale, uiScale, world.getDensityDebugInfo(blockX, blockY, blockZ), 1.0f, 1.0f, 1.0f);
    }

    private String facingName(double yaw) {
        double degrees = Math.toDegrees(yaw);
        double normalized = ((degrees % 360.0) + 360.0) % 360.0;
        if (normalized >= 45.0 && normalized < 135.0) {
            return "south (+Z)";
        }
        if (normalized >= 135.0 && normalized < 225.0) {
            return "west (-X)";
        }
        if (normalized >= 225.0 && normalized < 315.0) {
            return "north (-Z)";
        }
        return "east (+X)";
    }

    private void renderPauseMenu(int pauseSelection) {
        float uiScale = Math.max(1.0f, getUiScale());
        drawRect(0.0f, 0.0f, framebufferWidth, framebufferHeight, 0.0f, 0.0f, 0.0f, 0.56f);
        drawCenteredShadowText(74.0f * uiScale, uiScale * 1.2f, "Game menu", 1.0f, 1.0f, 1.0f);

        float wideWidth = 398.0f * uiScale;
        float buttonHeight = 38.0f * uiScale;
        float centerX = framebufferWidth * 0.5f;
        float y = 142.0f * uiScale;
        for (int i = 0; i < GameConfig.PAUSE_OPTIONS.length; i++) {
            drawMenuButton(centerX - wideWidth * 0.5f, y + i * 52.0f * uiScale,
                wideWidth, buttonHeight, GameConfig.PAUSE_OPTIONS[i], pauseSelection == i, true, uiScale * 0.82f);
        }
    }

    private void drawSettingsSlider(float y, float uiScale, String label, int value, int min, int max, boolean selected) {
        float sliderWidth = 320.0f * uiScale;
        float sliderHeight = 8.0f * uiScale;
        float x = framebufferWidth * 0.5f - sliderWidth * 0.5f;
        float knobT = (value - min) / (float) Math.max(1, max - min);
        float knobX = x + knobT * sliderWidth;
        String text = label + ": " + value;
        drawCenteredShadowText(y - 12.0f * uiScale, uiScale * 0.85f, text, selected ? 1.0f : 0.86f, selected ? 0.94f : 0.86f, selected ? 0.68f : 0.86f);
        drawRect(x, y, sliderWidth, sliderHeight, 0.20f, 0.22f, 0.24f, 0.95f);
        drawRect(x, y, sliderWidth * knobT, sliderHeight, 0.82f, 0.78f, 0.52f, 0.98f);
        drawRect(knobX - 5.0f * uiScale, y - 7.0f * uiScale, 10.0f * uiScale, 22.0f * uiScale, 0.92f, 0.90f, 0.72f, 0.98f);
        drawOutline(x, y, sliderWidth, sliderHeight, uiScale, selected ? 0.94f : 0.0f, selected ? 0.86f : 0.0f, selected ? 0.55f : 0.0f, selected ? 0.98f : 0.9f);
    }

    private void renderDeathScreen(int deathSelection) {
        float uiScale = Math.max(1.0f, getUiScale());
        drawRect(0.0f, 0.0f, framebufferWidth, framebufferHeight, 0.09f, 0.0f, 0.0f, 0.72f);
        drawCenteredShadowText(118.0f * uiScale, uiScale * 2.0f, "You Died!", 0.96f, 0.22f, 0.22f);
        drawCenteredShadowText(176.0f * uiScale, uiScale, "Survival run ended. Choose what to do next.", 0.95f, 0.95f, 0.95f);
        drawCenteredMenuButtons(GameConfig.DEATH_OPTIONS, deathSelection, 236.0f, 0.88f, 0.88f, 0.88f);
    }

    private void renderMainMenu(int menuScreen, int mainMenuSelection, boolean mainMenuWorldActionsEnabled, String createWorldName, String createWorldSeed, int createWorldGameMode, int createWorldDifficulty, int activeMenuTextField, String renameWorldName, List<WorldInfo> worlds, int selectedWorldIndex, int scrollOffset, String loadedWorldName, int renderDistanceChunks, int fovDegrees) {
        float uiScale = Math.max(1.0f, getUiScale());
        if (menuScreen == GameConfig.MENU_SCREEN_MAIN && loadedWorldName != null && !loadedWorldName.isBlank()) {
            drawRect(0.0f, 0.0f, framebufferWidth, framebufferHeight, 0.02f, 0.025f, 0.03f, 0.36f);
        } else {
            drawRect(0.0f, 0.0f, framebufferWidth, framebufferHeight, 0.02f, 0.025f, 0.03f, 1.0f);
        }

        if (menuScreen == GameConfig.MENU_SCREEN_SINGLEPLAYER) {
            renderSingleplayerMenu(mainMenuSelection, mainMenuWorldActionsEnabled, worlds, selectedWorldIndex, scrollOffset);
        } else if (menuScreen == GameConfig.MENU_SCREEN_CREATE_WORLD) {
            renderCreateWorldMenu(mainMenuSelection, createWorldName, createWorldSeed, createWorldGameMode, createWorldDifficulty, activeMenuTextField);
        } else if (menuScreen == GameConfig.MENU_SCREEN_RENAME_WORLD) {
            renderRenameWorldMenu(mainMenuSelection, renameWorldName, activeMenuTextField);
        } else if (menuScreen == GameConfig.MENU_SCREEN_OPTIONS) {
            renderOptionsMenu(mainMenuSelection, renderDistanceChunks, fovDegrees);
        } else {
            drawCenteredShadowText(116.0f * uiScale, uiScale * 2.4f, "TinyMinecraft", 1.0f, 1.0f, 1.0f);
            renderMainMenuHome(mainMenuSelection, worlds, selectedWorldIndex, loadedWorldName);
        }
    }

    private void renderMainMenuHome(int mainMenuSelection, List<WorldInfo> worlds, int selectedWorldIndex, String loadedWorldName) {
        float uiScale = Math.max(1.0f, getUiScale());
        float actionWidth = 280.0f * uiScale;
        float actionHeight = 46.0f * uiScale;
        float actionsX = framebufferWidth * 0.5f - actionWidth * 0.5f;
        float actionsY = framebufferHeight * 0.5f - 18.0f * uiScale;
        for (int i = 0; i < GameConfig.WORLD_MENU_ACTIONS.length; i++) {
            float boxY = actionsY + i * 58.0f * uiScale;
            boolean selected = i == mainMenuSelection;
            drawRect(actionsX, boxY, actionWidth, actionHeight,
                selected ? 0.86f : 0.58f,
                selected ? 0.82f : 0.58f,
                selected ? 0.58f : 0.58f,
                0.98f);
            drawOutline(actionsX, boxY, actionWidth, actionHeight, uiScale, 0.0f, 0.0f, 0.0f, 1.0f);
            drawText(actionsX + actionWidth * 0.5f - measureTextWidth(GameConfig.WORLD_MENU_ACTIONS[i], uiScale * 0.9f) * 0.5f, boxY + 29.0f * uiScale, uiScale * 0.9f, GameConfig.WORLD_MENU_ACTIONS[i], 0.10f, 0.10f, 0.12f);
        }

    }

    private void renderSingleplayerMenu(int mainMenuSelection, boolean mainMenuWorldActionsEnabled, List<WorldInfo> worlds, int selectedWorldIndex, int scrollOffset) {
        float uiScale = Math.max(1.0f, getUiScale());
        float headerHeight = 66.0f * uiScale;
        float footerHeight = 82.0f * uiScale;
        drawRect(0.0f, 0.0f, framebufferWidth, headerHeight, 0.03f, 0.035f, 0.04f, 1.0f);
        drawRect(0.0f, headerHeight, framebufferWidth, framebufferHeight - headerHeight - footerHeight, 0.0f, 0.0f, 0.0f, 0.38f);
        drawRect(0.0f, framebufferHeight - footerHeight, framebufferWidth, footerHeight, 0.03f, 0.035f, 0.04f, 1.0f);
        drawCenteredShadowText(32.0f * uiScale, uiScale * 1.0f, "Select World", 0.96f, 0.96f, 0.96f);

        float panelWidth = framebufferWidth - 160.0f * uiScale;
        float panelX = framebufferWidth * 0.5f - panelWidth * 0.5f;
        float listX = panelX + 28.0f * uiScale;
        float listY = 96.0f * uiScale;
        float listWidth = panelWidth - 56.0f * uiScale;
        float rowHeight = 36.0f * uiScale;

        int visibleRows = Math.min(GameConfig.WORLD_MENU_VISIBLE_ROWS, Math.max(0, worlds.size() - scrollOffset));
        if (worlds.isEmpty()) {
            drawShadowText(listX, listY + 30.0f * uiScale, uiScale * 0.84f, "No worlds yet. Play will create a new one.", 0.86f, 0.88f, 0.90f);
        }
        for (int row = 0; row < visibleRows; row++) {
            int worldIndex = scrollOffset + row;
            WorldInfo info = worlds.get(worldIndex);
            float rowY = listY + row * (rowHeight + 8.0f * uiScale);
            boolean selected = worldIndex == selectedWorldIndex;
            drawRect(listX, rowY, listWidth, rowHeight,
                selected ? 0.38f : 0.17f,
                selected ? 0.42f : 0.18f,
                selected ? 0.48f : 0.20f,
                selected ? 0.96f : 0.88f);
            drawOutline(listX, rowY, listWidth, rowHeight, uiScale, selected ? 0.92f : 0.38f, selected ? 0.88f : 0.40f, selected ? 0.68f : 0.44f, 0.92f);
            drawShadowText(listX + 12.0f * uiScale, rowY + 15.0f * uiScale, uiScale * 0.74f, info.name, 0.98f, 0.98f, 0.98f);
            drawShadowText(listX + 12.0f * uiScale, rowY + 30.0f * uiScale, uiScale * 0.50f, "Modified: " + formatWorldDate(info.lastModifiedMillis) + "  " + gameModeName(info.gameMode), 0.72f, 0.76f, 0.80f);
        }

        float actionWidth = 118.0f * uiScale;
        float actionHeight = 38.0f * uiScale;
        float gap = 10.0f * uiScale;
        float startX = framebufferWidth * 0.5f - (actionWidth * GameConfig.SINGLEPLAYER_ACTIONS.length + gap * (GameConfig.SINGLEPLAYER_ACTIONS.length - 1)) * 0.5f;
        float actionY = framebufferHeight - 58.0f * uiScale;
        for (int i = 0; i < GameConfig.SINGLEPLAYER_ACTIONS.length; i++) {
            float x = startX + i * (actionWidth + gap);
            boolean selected = i == mainMenuSelection;
            boolean enabled = i == 1 || i == 4 || (mainMenuWorldActionsEnabled && !worlds.isEmpty());
            drawRect(x, actionY, actionWidth, actionHeight,
                enabled ? (selected ? 0.86f : 0.58f) : 0.28f,
                enabled ? (selected ? 0.82f : 0.58f) : 0.29f,
                enabled ? (selected ? 0.58f : 0.58f) : 0.31f,
                enabled ? 0.98f : 0.70f);
            drawOutline(x, actionY, actionWidth, actionHeight, uiScale, enabled ? 0.0f : 0.12f, enabled ? 0.0f : 0.12f, enabled ? 0.0f : 0.14f, enabled ? 1.0f : 0.72f);
            String label = GameConfig.SINGLEPLAYER_ACTIONS[i];
            float labelScale = label.length() > 8 ? uiScale * 0.68f : uiScale * 0.78f;
            drawText(x + actionWidth * 0.5f - measureTextWidth(label, labelScale) * 0.5f, actionY + 24.0f * uiScale, labelScale, label, enabled ? 0.10f : 0.50f, enabled ? 0.10f : 0.52f, enabled ? 0.12f : 0.56f);
        }
    }

    private void renderOptionsMenu(int mainMenuSelection, int renderDistanceChunks, int fovDegrees) {
        float uiScale = Math.max(1.0f, getUiScale());
        drawCenteredShadowText(Math.max(72.0f * uiScale, framebufferHeight * 0.22f), uiScale * 1.2f, Settings.isRussian() ? "Настройки" : "Options", 0.96f, 0.96f, 0.96f);

        drawSettingsSlider(optionsRenderDistanceSliderY(uiScale), uiScale, Settings.isRussian() ? "Дальность прорисовки" : "Render Distance", renderDistanceChunks, GameConfig.MIN_RENDER_DISTANCE, GameConfig.MAX_RENDER_DISTANCE_CHUNKS, mainMenuSelection == 0);
        drawSettingsSlider(optionsFovSliderY(uiScale), uiScale, Settings.isRussian() ? "Поле зрения" : "FOV", fovDegrees, 55, 100, mainMenuSelection == 1);
        drawSettingsSlider(optionsInventoryUiSliderY(uiScale), uiScale, Settings.isRussian() ? "Размер инвентаря" : "Inventory UI Size", Settings.inventoryUiSize, 1, 4, mainMenuSelection == 2);

        float actionWidth = 280.0f * uiScale;
        float actionHeight = 46.0f * uiScale;
        float actionsX = framebufferWidth * 0.5f - actionWidth * 0.5f;
        float languageY = optionsLanguageButtonY(uiScale);
        drawMenuButton(actionsX, languageY, actionWidth, actionHeight,
            (Settings.isRussian() ? "Язык: Русский" : "Language: English"),
            mainMenuSelection == 3, true, uiScale * 0.80f);

        float boxY = optionsBackButtonY(uiScale);
        boolean selected = mainMenuSelection == 4;
        drawRect(actionsX, boxY, actionWidth, actionHeight,
            selected ? 0.86f : 0.58f,
            selected ? 0.82f : 0.58f,
            selected ? 0.58f : 0.58f,
            0.98f);
        drawOutline(actionsX, boxY, actionWidth, actionHeight, uiScale, 0.0f, 0.0f, 0.0f, 1.0f);
        String backLabel = Settings.isRussian() ? "Назад" : "Back";
        drawText(actionsX + actionWidth * 0.5f - measureTextWidth(backLabel, uiScale * 0.9f) * 0.5f, boxY + 29.0f * uiScale, uiScale * 0.9f, backLabel, 0.10f, 0.10f, 0.12f);
    }

    private void renderCreateWorldMenu(int mainMenuSelection, String worldName, String seedText, int gameMode, int difficulty, int activeTextField) {
        float uiScale = Math.max(1.0f, getUiScale());
        drawCenteredShadowText(46.0f * uiScale, uiScale * 1.0f, "Create New World", 0.96f, 0.96f, 0.96f);

        float panelWidth = Math.min(720.0f * uiScale, framebufferWidth - 120.0f * uiScale);
        float panelX = framebufferWidth * 0.5f - panelWidth * 0.5f;
        drawShadowText(panelX, 104.0f * uiScale, uiScale * 0.78f, "World Name", 0.82f, 0.82f, 0.82f);
        drawTextField(panelX, 126.0f * uiScale, panelWidth, 36.0f * uiScale, worldName, activeTextField == 0);

        float buttonWidth = 340.0f * uiScale;
        float buttonHeight = 44.0f * uiScale;
        float gap = 18.0f * uiScale;
        float startX = framebufferWidth * 0.5f - (buttonWidth * 2.0f + gap) * 0.5f;
        drawMenuButton(startX, 224.0f * uiScale, buttonWidth, buttonHeight, "Game Mode: " + gameModeName(gameMode), false, true, uiScale * 0.80f);
        drawMenuButton(startX + buttonWidth + gap, 224.0f * uiScale, buttonWidth, buttonHeight, "Difficulty: " + GameConfig.DIFFICULTY_OPTIONS[Math.max(0, Math.min(difficulty, GameConfig.DIFFICULTY_OPTIONS.length - 1))], false, true, uiScale * 0.80f);
        drawShadowText(startX, 286.0f * uiScale, uiScale * 0.62f, gameModeHelp(gameMode), 0.70f, 0.72f, 0.76f);

        drawShadowText(panelX, 308.0f * uiScale, uiScale * 0.78f, "Seed for the World Generator", 0.82f, 0.82f, 0.82f);
        drawTextField(panelX, 330.0f * uiScale, panelWidth, 36.0f * uiScale, seedText, activeTextField == 1);
        drawShadowText(panelX, 382.0f * uiScale, uiScale * 0.66f, "Leave blank for a random seed", 0.72f, 0.72f, 0.74f);

        drawBottomButtons(GameConfig.CREATE_WORLD_ACTIONS, mainMenuSelection, 240.0f * uiScale, 46.0f * uiScale, uiScale);
    }

    private void renderRenameWorldMenu(int mainMenuSelection, String renameWorldName, int activeTextField) {
        float uiScale = Math.max(1.0f, getUiScale());
        drawCenteredShadowText(76.0f * uiScale, uiScale * 1.0f, "Rename World", 0.96f, 0.96f, 0.96f);
        float panelWidth = Math.min(720.0f * uiScale, framebufferWidth - 120.0f * uiScale);
        float panelX = framebufferWidth * 0.5f - panelWidth * 0.5f;
        drawShadowText(panelX, 224.0f * uiScale, uiScale * 0.78f, "World Name", 0.82f, 0.82f, 0.82f);
        drawTextField(panelX, 246.0f * uiScale, panelWidth, 36.0f * uiScale, renameWorldName, activeTextField == 0);
        drawBottomButtons(GameConfig.RENAME_WORLD_ACTIONS, mainMenuSelection, 240.0f * uiScale, 46.0f * uiScale, uiScale);
    }

    private void drawTextField(float x, float y, float width, float height, String text, boolean active) {
        float uiScale = Math.max(1.0f, getUiScale());
        drawRect(x, y, width, height, 0.02f, 0.02f, 0.02f, 0.92f);
        drawOutline(x, y, width, height, uiScale, active ? 0.94f : 0.58f, active ? 0.94f : 0.58f, active ? 0.96f : 0.60f, 0.95f);
        String display = text == null ? "" : text;
        if (active) {
            display += "_";
        }
        drawShadowText(x + 8.0f * uiScale, y + 23.0f * uiScale, uiScale * 0.78f, display, 0.96f, 0.96f, 0.96f);
    }

    private void drawBottomButtons(String[] actions, int selectedIndex, float buttonWidth, float buttonHeight, float uiScale) {
        float gap = 12.0f * uiScale;
        float startX = framebufferWidth * 0.5f - (buttonWidth * actions.length + gap * (actions.length - 1)) * 0.5f;
        float y = framebufferHeight - 78.0f * uiScale;
        for (int i = 0; i < actions.length; i++) {
            drawMenuButton(startX + i * (buttonWidth + gap), y, buttonWidth, buttonHeight, actions[i], i == selectedIndex, true, uiScale * 0.78f);
        }
    }

    private void drawMenuButton(float x, float y, float width, float height, String label, boolean selected, boolean enabled, float textScale) {
        drawRect(x, y, width, height,
            enabled ? (selected ? 0.86f : 0.58f) : 0.28f,
            enabled ? (selected ? 0.82f : 0.58f) : 0.29f,
            enabled ? (selected ? 0.58f : 0.58f) : 0.31f,
            enabled ? 0.98f : 0.70f);
        drawOutline(x, y, width, height, Math.max(1.0f, textScale), 0.0f, 0.0f, 0.0f, enabled ? 1.0f : 0.72f);
        drawText(x + width * 0.5f - measureTextWidth(label, textScale) * 0.5f, y + height * 0.63f, textScale, label, enabled ? 0.10f : 0.50f, enabled ? 0.10f : 0.52f, enabled ? 0.12f : 0.56f);
    }

    private String gameModeName(int gameMode) {
        return GameConfig.GAME_MODE_OPTIONS[Math.max(0, Math.min(gameMode, GameConfig.GAME_MODE_OPTIONS.length - 1))];
    }

    private String gameModeHelp(int gameMode) {
        if (gameMode == 1) {
            return "Unlimited blocks, flying, and no survival damage.";
        }
        if (gameMode == 2) {
            return "No clipping and observation only.";
        }
        return "Search for resources, craft, gain levels, health and hunger.";
    }

    private String formatWorldDate(long millis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(millis));
    }

    private void renderGameModeSwitcher(int gameModeSelection) {
        float uiScale = Math.max(1.0f, getUiScale());
        float boxWidth = 360.0f * uiScale;
        float boxHeight = 124.0f * uiScale;
        float boxX = framebufferWidth * 0.5f - boxWidth * 0.5f;
        float boxY = 74.0f * uiScale;
        drawRect(boxX, boxY, boxWidth, boxHeight, 0.04f, 0.05f, 0.06f, 0.82f);
        drawOutline(boxX, boxY, boxWidth, boxHeight, 2.0f * uiScale, 0.86f, 0.88f, 0.90f, 0.92f);
        drawCenteredShadowText(boxY + 28.0f * uiScale, uiScale, "Game Mode", 0.98f, 0.98f, 0.98f);

        float slotWidth = 104.0f * uiScale;
        float slotHeight = 42.0f * uiScale;
        float slotY = boxY + 62.0f * uiScale;
        float startX = framebufferWidth * 0.5f - (slotWidth * 3.0f + 14.0f * uiScale * 2.0f) * 0.5f;
        for (int i = 0; i < GameConfig.GAME_MODE_OPTIONS.length; i++) {
            float slotX = startX + i * (slotWidth + 14.0f * uiScale);
            boolean selected = i == gameModeSelection;
            drawRect(slotX, slotY, slotWidth, slotHeight,
                selected ? 0.92f : 0.22f,
                selected ? 0.88f : 0.24f,
                selected ? 0.62f : 0.27f,
                selected ? 0.98f : 0.92f);
            float labelScale = uiScale * 0.86f;
            float labelX = slotX + slotWidth * 0.5f - measureTextWidth(GameConfig.GAME_MODE_OPTIONS[i], labelScale) * 0.5f;
            drawText(labelX, slotY + 26.0f * uiScale, labelScale, GameConfig.GAME_MODE_OPTIONS[i],
                selected ? 0.08f : 0.94f,
                selected ? 0.08f : 0.94f,
                selected ? 0.08f : 0.94f);
        }
    }

    private void drawCenteredMenuButtons(String[] options, int selectedIndex, float firstOptionY, float selectedRed, float selectedGreen, float selectedBlue) {
        float uiScale = Math.max(1.0f, getUiScale());
        float boxWidth = 260.0f * uiScale;
        float boxHeight = 48.0f * uiScale;
        float boxX = framebufferWidth * 0.5f - boxWidth * 0.5f;
        for (int i = 0; i < options.length; i++) {
            float boxY = firstOptionY * uiScale + i * 64.0f * uiScale;
            boolean selected = i == selectedIndex;
            if (selected) {
                drawRect(boxX, boxY, boxWidth, boxHeight, 0.76f, 0.76f, 0.76f, 0.98f);
                drawOutline(boxX, boxY, boxWidth, boxHeight, uiScale, 0.0f, 0.0f, 0.0f, 1.0f);
                drawCenteredButtonLabel(boxY, boxHeight, uiScale, options[i], 0.10f, 0.10f, 0.10f);
            } else {
                drawRect(boxX, boxY, boxWidth, boxHeight, 0.62f, 0.62f, 0.62f, 0.95f);
                drawOutline(boxX, boxY, boxWidth, boxHeight, uiScale, 0.0f, 0.0f, 0.0f, 1.0f);
                drawCenteredButtonLabel(boxY, boxHeight, uiScale, options[i], 0.16f, 0.16f, 0.16f);
            }
        }
    }

    private void drawCenteredButtonLabel(float boxY, float boxHeight, float textScale, String text, float red, float green, float blue) {
        float textX = framebufferWidth * 0.5f - measureTextWidth(text, textScale) * 0.5f;
        float textY = boxY + boxHeight * 0.63f;
        drawText(textX, textY, textScale, text, red, green, blue);
    }

    private void renderInventoryScreen(PlayerState player, PlayerInventory inventory, boolean creativeMode, int selectedSlot, int creativeTab, int inventoryScreenMode, ContainerInventory chestContainer, FurnaceBlockEntity furnace, double mouseX, double mouseY) {
        UiRenderer.InventoryUiLayout layout = buildInventoryLayout(creativeMode, creativeTab, inventoryScreenMode);
        UiRenderer.SlotBox hovered = layout.hitTest(mouseX, mouseY);
        float uiScale = getInventoryUiScale();

        drawRect(0.0f, 0.0f, framebufferWidth, framebufferHeight, 0.0f, 0.0f, 0.0f, 0.34f);
        drawRect(layout.panelX, layout.panelY, layout.panelWidth, layout.panelHeight, 0.09f, 0.09f, 0.10f, 0.78f);
        drawOutline(layout.panelX, layout.panelY, layout.panelWidth, layout.panelHeight, 2.4f * uiScale, 0.90f, 0.90f, 0.95f, 0.92f);

        if (!creativeMode && (inventoryScreenMode == GameConfig.INVENTORY_SCREEN_PLAYER || inventoryScreenMode == GameConfig.INVENTORY_SCREEN_WORKBENCH)) {
            float titleX = layout.craftX;
            float titleY = layout.craftY - 14.0f * uiScale;
            if (inventoryScreenMode == GameConfig.INVENTORY_SCREEN_WORKBENCH) {
                for (UiRenderer.SlotBox slot : layout.slots) {
                    if (slot.ref.group == InventorySlotGroup.CRAFT_3X3 && slot.ref.index == 0) {
                        titleX = slot.x;
                        titleY = slot.y - 14.0f * uiScale;
                        break;
                    }
                }
            }
            drawShadowText(titleX, titleY, uiScale * 0.78f, "Crafting", 0.92f, 0.92f, 0.92f);
        }
        if (!creativeMode && inventoryScreenMode == GameConfig.INVENTORY_SCREEN_PLAYER) {
            renderInventoryPlayerPreview(
                player,
                inventory,
                layout.armorX + layout.slotSize + 34.0f * uiScale,
                layout.armorY,
                layout.slotSize * 4.25f
            );
        } else if (creativeMode) {
            renderCreativeTabs(layout, creativeTab, mouseX, mouseY);
        }

        if (creativeMode) {
            int[] tabIndices = InventoryItems.CREATIVE_TAB_INDICES[layout.activeCreativeTab];
            for (int row = 0; row < layout.creativeRows; row++) {
                for (int column = 0; column < layout.creativeColumns; column++) {
                    int visibleIndex = row * layout.creativeColumns + column;
                    int globalIndex = visibleIndex < tabIndices.length ? tabIndices[visibleIndex] : -1;
                    ItemStack stack = globalIndex >= 0
                        ? new ItemStack(InventoryItems.CREATIVE_ITEMS[globalIndex], InventoryItems.maxStackSize(InventoryItems.CREATIVE_ITEMS[globalIndex]))
                        : null;
                    drawItemSlot(
                        layout.creativeX + column * (layout.slotSize + layout.slotGap),
                        layout.creativeY + row * (layout.slotSize + layout.slotGap),
                        layout.slotSize,
                        stack,
                        false,
                        hovered != null && hovered.ref.group == InventorySlotGroup.CREATIVE && hovered.ref.index == globalIndex,
                        null
                    );
                }
            }
        }

        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 9; column++) {
                int index = row * 9 + column;
                drawItemSlot(
                    layout.storageX + column * (layout.slotSize + layout.slotGap),
                    layout.storageY + row * (layout.slotSize + layout.slotGap),
                    layout.slotSize,
                    inventory.getStorageStack(index),
                    false,
                    hovered != null && hovered.ref.group == InventorySlotGroup.STORAGE && hovered.ref.index == index,
                    null
                );
            }
        }

        for (int slot = 0; slot < 9; slot++) {
            drawItemSlot(
                layout.hotbarX + slot * (layout.slotSize + layout.slotGap),
                layout.hotbarY,
                layout.slotSize,
                inventory.getHotbarStack(slot),
                slot == selectedSlot,
                hovered != null && hovered.ref.group == InventorySlotGroup.HOTBAR && hovered.ref.index == slot,
                null
            );
        }

        if (!creativeMode && inventoryScreenMode == GameConfig.INVENTORY_SCREEN_PLAYER) {
            String[] armorHints = {"H", "C", "L", "B"};
            for (int slot = 0; slot < 4; slot++) {
                drawItemSlot(
                    layout.armorX,
                    layout.armorY + slot * (layout.slotSize + layout.slotGap),
                    layout.slotSize,
                    inventory.getArmorStack(slot),
                    false,
                    hovered != null && hovered.ref.group == InventorySlotGroup.ARMOR && hovered.ref.index == slot,
                    armorHints[slot]
                );
            }

            drawItemSlot(
                layout.offhandX,
                layout.offhandY,
                layout.slotSize,
                inventory.getOffhandStack(),
                false,
                hovered != null && hovered.ref.group == InventorySlotGroup.OFFHAND,
                "O"
            );

            for (int row = 0; row < 2; row++) {
                for (int column = 0; column < 2; column++) {
                    int index = row * 2 + column;
                    drawItemSlot(
                        layout.craftX + column * (layout.slotSize + layout.slotGap),
                        layout.craftY + row * (layout.slotSize + layout.slotGap),
                        layout.slotSize,
                        inventory.getCraftStack(index),
                        false,
                        hovered != null && hovered.ref.group == InventorySlotGroup.CRAFT && hovered.ref.index == index,
                        null
                    );
                }
            }

            float arrowX = layout.craftX + 2.0f * (layout.slotSize + layout.slotGap) + 5.0f * uiScale;
            float arrowY = layout.craftY + layout.slotSize * 0.78f;
            drawShadowText(arrowX, arrowY, uiScale * 1.2f, "->", 0.90f, 0.90f, 0.90f);
            drawItemSlot(
                layout.resultX,
                layout.resultY,
                layout.slotSize,
                inventory.getCraftResultStack(),
                false,
                hovered != null && hovered.ref.group == InventorySlotGroup.CRAFT_RESULT,
                null
            );
        }

        if (!creativeMode && inventoryScreenMode == GameConfig.INVENTORY_SCREEN_WORKBENCH) {
            for (UiRenderer.SlotBox slot : layout.slots) {
                if (slot.ref.group == InventorySlotGroup.CRAFT_3X3) {
                    drawItemSlot(slot.x, slot.y, slot.size, inventory.getWorkbenchCraftStack(slot.ref.index), false,
                        hovered != null && hovered.ref.group == slot.ref.group && hovered.ref.index == slot.ref.index, null);
                } else if (slot.ref.group == InventorySlotGroup.CRAFT_3X3_RESULT) {
                    drawShadowText(slot.x - 28.0f * uiScale, slot.y + slot.size * 0.62f, uiScale * 1.2f, "->", 0.90f, 0.90f, 0.90f);
                    drawItemSlot(slot.x, slot.y, slot.size, inventory.getWorkbenchCraftResultStack(), false,
                        hovered != null && hovered.ref.group == InventorySlotGroup.CRAFT_3X3_RESULT, null);
                }
            }
        } else if (!creativeMode && inventoryScreenMode == GameConfig.INVENTORY_SCREEN_CHEST) {
            for (UiRenderer.SlotBox slot : layout.slots) {
                if (slot.ref.group == InventorySlotGroup.CHEST_CONTAINER) {
                    drawItemSlot(slot.x, slot.y, slot.size, chestContainer == null ? null : chestContainer.getStack(slot.ref.index), false,
                        hovered != null && hovered.ref.group == slot.ref.group && hovered.ref.index == slot.ref.index, null);
                }
            }
        } else if (!creativeMode && inventoryScreenMode == GameConfig.INVENTORY_SCREEN_FURNACE) {
            for (UiRenderer.SlotBox slot : layout.slots) {
                ItemStack stack = null;
                String hint = null;
                if (slot.ref.group == InventorySlotGroup.FURNACE_INPUT) {
                    stack = furnace == null ? null : furnace.input;
                    hint = "I";
                } else if (slot.ref.group == InventorySlotGroup.FURNACE_FUEL) {
                    stack = furnace == null ? null : furnace.fuel;
                    hint = "F";
                } else if (slot.ref.group == InventorySlotGroup.FURNACE_OUTPUT) {
                    stack = furnace == null ? null : furnace.output;
                } else {
                    continue;
                }
                drawItemSlot(slot.x, slot.y, slot.size, stack, false,
                    hovered != null && hovered.ref.group == slot.ref.group, hint);
            }
            if (furnace != null) {
                float barX = layout.panelX + layout.panelWidth * 0.5f - 44.0f * uiScale;
                float barY = layout.panelY + 80.0f * uiScale;
                drawRect(barX, barY, 88.0f * uiScale, 7.0f * uiScale, 0.04f, 0.04f, 0.04f, 0.90f);
                float cook = furnace.cookTotal <= 0.0 ? 0.0f : (float) Math.min(1.0, furnace.cookProgress / furnace.cookTotal);
                drawRect(barX, barY, 88.0f * uiScale * cook, 7.0f * uiScale, 0.90f, 0.58f, 0.20f, 0.96f);
                float burn = furnace.burnTotal <= 0.0 ? 0.0f : (float) Math.min(1.0, furnace.burnRemaining / furnace.burnTotal);
                drawRect(barX, barY + 13.0f * uiScale, 88.0f * uiScale * burn, 5.0f * uiScale, 0.95f, 0.28f, 0.08f, 0.92f);
            }
        }

        if (!inventory.getCursorStack().isEmpty()) {
            drawItemSlot((float) mouseX - layout.slotSize * 0.32f, (float) mouseY - layout.slotSize * 0.32f, layout.slotSize, inventory.getCursorStack(), false, false, null);
        }

        if (hovered != null) {
            ItemStack tooltipStack = inventory.peekSlot(hovered.ref, creativeMode, chestContainer, furnace);
            if (tooltipStack != null && !tooltipStack.isEmpty()) {
                drawTooltip((float) mouseX + 14.0f, (float) mouseY + 18.0f, InventoryItems.name(tooltipStack.itemId));
            }
        }
    }

    private void renderCreativeTabs(UiRenderer.InventoryUiLayout layout, int creativeTab, double mouseX, double mouseY) {
        float uiScale = getInventoryUiScale();
        float gap = 4.0f * uiScale;
        float tabWidth = Math.min(68.0f * uiScale,
            (layout.panelWidth - 32.0f * uiScale - gap * (GameConfig.CREATIVE_TABS.length - 1)) / GameConfig.CREATIVE_TABS.length);
        float tabHeight = 22.0f * uiScale;
        float totalWidth = tabWidth * GameConfig.CREATIVE_TABS.length + gap * (GameConfig.CREATIVE_TABS.length - 1);
        float startX = layout.panelX + layout.panelWidth * 0.5f - totalWidth * 0.5f;
        float y = layout.panelY + 10.0f * uiScale;
        int activeTab = Math.max(0, Math.min(creativeTab, GameConfig.CREATIVE_TABS.length - 1));
        for (int i = 0; i < GameConfig.CREATIVE_TABS.length; i++) {
            float x = startX + i * (tabWidth + gap);
            boolean active = i == activeTab;
            boolean hovered = mouseX >= x && mouseX <= x + tabWidth && mouseY >= y && mouseY <= y + tabHeight;
            drawRect(x, y, tabWidth, tabHeight,
                active ? 0.84f : (hovered ? 0.52f : 0.32f),
                active ? 0.78f : (hovered ? 0.54f : 0.34f),
                active ? 0.56f : (hovered ? 0.58f : 0.38f),
                0.96f);
            drawOutline(x, y, tabWidth, tabHeight, uiScale, active ? 0.96f : 0.18f, active ? 0.92f : 0.18f, active ? 0.72f : 0.20f, 0.92f);
            String label = GameConfig.CREATIVE_TABS[i];
            float labelScale = Math.min(uiScale * 0.58f, tabWidth / Math.max(1.0f, measureTextWidth(label, 1.0f)) * 0.82f);
            drawText(x + tabWidth * 0.5f - measureTextWidth(label, labelScale) * 0.5f, y + 15.0f * uiScale, labelScale, label, active ? 0.08f : 0.94f, active ? 0.08f : 0.94f, active ? 0.08f : 0.94f);
        }
    }

    private void renderInventoryPlayerPreview(PlayerState player, PlayerInventory inventory, float x, float y, float height) {
        float centerX = x + height * 0.34f;
        float feetY = y + height;
        float scale = height * 0.43f;

        drawRect(x - height * 0.08f, y - height * 0.04f, height * 0.84f, height * 1.08f, 0.05f, 0.05f, 0.06f, 0.34f);

        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0.0, framebufferWidth, framebufferHeight, 0.0, -1000.0, 1000.0);

        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        glTranslated(centerX, feetY, 0.0);
        glScalef(scale, -scale, scale);
        glRotated(-Math.toDegrees(player.yaw) - 25.0, 0.0, 1.0, 0.0);
        drawPlayerModelParts(inventory, GameConfig.AIR, 0.0, player.pitch, false, player.sneaking);
        glDisable(GL_DEPTH_TEST);
        glPopMatrix();

        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
    }

    private float[] armorColor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return getBlockFaceColor(stack.itemId, Face.TOP);
    }

    private void drawItemSlot(float x, float y, float size, ItemStack stack, boolean selected, boolean hovered, String placeholder) {
        float background = hovered ? 0.28f : 0.18f;
        drawRect(x, y, size, size, background, background, background + 0.03f, 0.92f);
        if (selected) {
            drawOutline(x, y, size, size, 2.4f, 1.0f, 0.92f, 0.52f, 0.98f);
        } else if (hovered) {
            drawOutline(x, y, size, size, 2.0f, 0.98f, 0.98f, 0.98f, 0.94f);
        } else {
            drawOutline(x, y, size, size, 1.3f, 0.58f, 0.58f, 0.60f, 0.90f);
        }

        if (stack != null && !stack.isEmpty()) {
            drawItemIcon(stack.itemId, x + size * 0.16f, y + size * 0.16f, size * 0.68f);
            if (stack.count > 1) {
                drawShadowText(x + size * 0.46f, y + size * 0.78f, getUiScale() * 0.62f, Integer.toString(stack.count), 1.0f, 1.0f, 1.0f);
            }
        } else if (placeholder != null) {
            drawShadowText(x + size * 0.33f, y + size * 0.62f, getUiScale() * 0.70f, placeholder, 0.66f, 0.66f, 0.70f);
        }
    }

    private void drawItemIcon(byte itemId, float x, float y, float size) {
        float[] color = getHeldBlockColor(itemId);
        switch (itemId) {
            case InventoryItems.STICK:
                drawRect(x + size * 0.42f, y + size * 0.08f, size * 0.16f, size * 0.84f, 0.56f, 0.38f, 0.21f, 0.98f);
                drawRect(x + size * 0.34f, y + size * 0.08f, size * 0.32f, size * 0.14f, 0.70f, 0.54f, 0.32f, 0.98f);
                break;
            case InventoryItems.IRON_INGOT:
                drawRect(x + size * 0.10f, y + size * 0.34f, size * 0.80f, size * 0.32f, 0.78f, 0.80f, 0.84f, 0.98f);
                drawOutline(x + size * 0.10f, y + size * 0.34f, size * 0.80f, size * 0.32f, 1.2f, 0.55f, 0.57f, 0.60f, 0.98f);
                break;
            case InventoryItems.IRON_HELMET:
                drawRect(x + size * 0.12f, y + size * 0.18f, size * 0.76f, size * 0.34f, 0.74f, 0.76f, 0.80f, 0.98f);
                drawRect(x + size * 0.22f, y + size * 0.50f, size * 0.56f, size * 0.18f, 0.62f, 0.64f, 0.68f, 0.98f);
                break;
            case InventoryItems.IRON_CHESTPLATE:
                drawRect(x + size * 0.18f, y + size * 0.14f, size * 0.64f, size * 0.72f, 0.70f, 0.72f, 0.76f, 0.98f);
                drawRect(x + size * 0.08f, y + size * 0.22f, size * 0.16f, size * 0.28f, 0.60f, 0.62f, 0.66f, 0.98f);
                drawRect(x + size * 0.76f, y + size * 0.22f, size * 0.16f, size * 0.28f, 0.60f, 0.62f, 0.66f, 0.98f);
                break;
            case InventoryItems.IRON_LEGGINGS:
                drawRect(x + size * 0.22f, y + size * 0.14f, size * 0.56f, size * 0.28f, 0.70f, 0.72f, 0.76f, 0.98f);
                drawRect(x + size * 0.24f, y + size * 0.42f, size * 0.18f, size * 0.40f, 0.62f, 0.64f, 0.68f, 0.98f);
                drawRect(x + size * 0.58f, y + size * 0.42f, size * 0.18f, size * 0.40f, 0.62f, 0.64f, 0.68f, 0.98f);
                break;
            case InventoryItems.IRON_BOOTS:
                drawRect(x + size * 0.18f, y + size * 0.54f, size * 0.22f, size * 0.26f, 0.64f, 0.66f, 0.70f, 0.98f);
                drawRect(x + size * 0.50f, y + size * 0.54f, size * 0.22f, size * 0.26f, 0.64f, 0.66f, 0.70f, 0.98f);
                drawRect(x + size * 0.14f, y + size * 0.72f, size * 0.34f, size * 0.10f, 0.82f, 0.84f, 0.88f, 0.98f);
                drawRect(x + size * 0.46f, y + size * 0.72f, size * 0.34f, size * 0.10f, 0.82f, 0.84f, 0.88f, 0.98f);
                break;
            case InventoryItems.SHIELD:
                drawRect(x + size * 0.24f, y + size * 0.08f, size * 0.52f, size * 0.74f, 0.60f, 0.42f, 0.22f, 0.98f);
                drawRect(x + size * 0.42f, y + size * 0.14f, size * 0.16f, size * 0.62f, 0.82f, 0.82f, 0.84f, 0.98f);
                break;
            case InventoryItems.TOTEM:
                drawRect(x + size * 0.38f, y + size * 0.10f, size * 0.24f, size * 0.72f, 0.88f, 0.70f, 0.22f, 0.98f);
                drawRect(x + size * 0.18f, y + size * 0.24f, size * 0.20f, size * 0.18f, 0.45f, 0.72f, 0.28f, 0.98f);
                drawRect(x + size * 0.62f, y + size * 0.24f, size * 0.20f, size * 0.18f, 0.45f, 0.72f, 0.28f, 0.98f);
                drawRect(x + size * 0.30f, y + size * 0.62f, size * 0.12f, size * 0.14f, 0.88f, 0.82f, 0.28f, 0.98f);
                drawRect(x + size * 0.58f, y + size * 0.62f, size * 0.12f, size * 0.14f, 0.88f, 0.82f, 0.28f, 0.98f);
                break;
            case InventoryItems.WOODEN_PICKAXE:
            case InventoryItems.STONE_PICKAXE:
            case InventoryItems.IRON_PICKAXE:
            case InventoryItems.DIAMOND_PICKAXE:
            case InventoryItems.NETHERITE_PICKAXE:
                drawToolIcon(itemId, x, y, size, 0);
                break;
            case InventoryItems.WOODEN_SWORD:
            case InventoryItems.STONE_SWORD:
            case InventoryItems.IRON_SWORD:
            case InventoryItems.DIAMOND_SWORD:
            case InventoryItems.NETHERITE_SWORD:
                drawToolIcon(itemId, x, y, size, 1);
                break;
            case InventoryItems.WOODEN_AXE:
            case InventoryItems.STONE_AXE:
            case InventoryItems.IRON_AXE:
            case InventoryItems.DIAMOND_AXE:
            case InventoryItems.NETHERITE_AXE:
                drawToolIcon(itemId, x, y, size, 2);
                break;
            case InventoryItems.WOODEN_SHOVEL:
            case InventoryItems.STONE_SHOVEL:
            case InventoryItems.IRON_SHOVEL:
            case InventoryItems.DIAMOND_SHOVEL:
            case InventoryItems.NETHERITE_SHOVEL:
                drawToolIcon(itemId, x, y, size, 3);
                break;
            case InventoryItems.WOODEN_HOE:
            case InventoryItems.STONE_HOE:
            case InventoryItems.IRON_HOE:
            case InventoryItems.DIAMOND_HOE:
            case InventoryItems.NETHERITE_HOE:
                drawToolIcon(itemId, x, y, size, 4);
                break;
            case InventoryItems.ZOMBIE_SPAWN_EGG:
            case InventoryItems.SKELETON_SPAWN_EGG:
            case InventoryItems.PIG_SPAWN_EGG:
            case InventoryItems.SHEEP_SPAWN_EGG:
            case InventoryItems.COW_SPAWN_EGG:
            case InventoryItems.VILLAGER_SPAWN_EGG:
                drawEggIcon(itemId, x, y, size);
                break;
            case InventoryItems.RAW_PORK:
            case InventoryItems.RAW_BEEF:
            case InventoryItems.RAW_MUTTON:
            case InventoryItems.LEATHER:
            case InventoryItems.WOOL:
            case InventoryItems.ROTTEN_FLESH:
            case InventoryItems.BONE:
                drawRect(x + size * 0.16f, y + size * 0.22f, size * 0.68f, size * 0.50f, color[0], color[1], color[2], 0.98f);
                drawRect(x + size * 0.28f, y + size * 0.34f, size * 0.44f, size * 0.24f, clampColor(color[0] * 1.12f), clampColor(color[1] * 1.12f), clampColor(color[2] * 1.12f), 0.98f);
                break;
            default:
                drawRect(x, y, size, size, color[0], color[1], color[2], 0.98f);
                drawRect(x + size * 0.14f, y + size * 0.14f, size * 0.72f, size * 0.72f, clampColor(color[0] * 0.82f), clampColor(color[1] * 0.82f), clampColor(color[2] * 0.82f), 0.98f);
                if (isOreBlock(itemId)) {
                    drawOreItemOverlay(itemId, x, y, size);
                }
                break;
        }
    }

    private void drawEggIcon(byte itemId, float x, float y, float size) {
        float[] color = getHeldBlockColor(itemId);
        drawRect(x + size * 0.24f, y + size * 0.14f, size * 0.52f, size * 0.70f, color[0], color[1], color[2], 0.98f);
        drawRect(x + size * 0.34f, y + size * 0.08f, size * 0.32f, size * 0.18f, color[0], color[1], color[2], 0.98f);
        drawRect(x + size * 0.36f, y + size * 0.32f, size * 0.14f, size * 0.12f, 0.96f, 0.96f, 0.90f, 0.98f);
        drawRect(x + size * 0.58f, y + size * 0.54f, size * 0.12f, size * 0.10f, 0.96f, 0.96f, 0.90f, 0.98f);
    }

    private void drawToolIcon(byte itemId, float x, float y, float size, int shape) {
        float[] head = getHeldBlockColor(itemId);
        float handleRed = 0.55f;
        float handleGreen = 0.36f;
        float handleBlue = 0.18f;
        drawRect(x + size * 0.45f, y + size * 0.34f, size * 0.12f, size * 0.52f, handleRed, handleGreen, handleBlue, 0.98f);
        if (shape == 1) {
            drawRect(x + size * 0.42f, y + size * 0.08f, size * 0.18f, size * 0.56f, head[0], head[1], head[2], 0.98f);
            drawRect(x + size * 0.37f, y + size * 0.16f, size * 0.28f, size * 0.10f, clampColor(head[0] * 1.18f), clampColor(head[1] * 1.18f), clampColor(head[2] * 1.18f), 0.98f);
        } else if (shape == 2) {
            drawRect(x + size * 0.28f, y + size * 0.14f, size * 0.38f, size * 0.22f, head[0], head[1], head[2], 0.98f);
            drawRect(x + size * 0.58f, y + size * 0.26f, size * 0.16f, size * 0.22f, head[0], head[1], head[2], 0.98f);
        } else if (shape == 3) {
            drawRect(x + size * 0.36f, y + size * 0.10f, size * 0.30f, size * 0.22f, head[0], head[1], head[2], 0.98f);
            drawRect(x + size * 0.42f, y + size * 0.28f, size * 0.18f, size * 0.10f, head[0], head[1], head[2], 0.98f);
        } else if (shape == 4) {
            drawRect(x + size * 0.28f, y + size * 0.12f, size * 0.48f, size * 0.13f, head[0], head[1], head[2], 0.98f);
            drawRect(x + size * 0.28f, y + size * 0.24f, size * 0.13f, size * 0.20f, head[0], head[1], head[2], 0.98f);
        } else {
            drawRect(x + size * 0.24f, y + size * 0.12f, size * 0.54f, size * 0.16f, head[0], head[1], head[2], 0.98f);
            drawRect(x + size * 0.22f, y + size * 0.26f, size * 0.16f, size * 0.20f, head[0], head[1], head[2], 0.98f);
            drawRect(x + size * 0.64f, y + size * 0.26f, size * 0.16f, size * 0.20f, head[0], head[1], head[2], 0.98f);
        }
    }

    private void drawOreItemOverlay(byte itemId, float x, float y, float size) {
        float[] accent = oreAccentColor(itemId, 1.0f);
        drawRect(x + size * 0.24f, y + size * 0.24f, size * 0.16f, size * 0.15f, accent[0], accent[1], accent[2], 0.98f);
        drawRect(x + size * 0.56f, y + size * 0.22f, size * 0.18f, size * 0.14f, accent[0], accent[1], accent[2], 0.98f);
        drawRect(x + size * 0.36f, y + size * 0.52f, size * 0.22f, size * 0.18f, accent[0], accent[1], accent[2], 0.98f);
        drawRect(x + size * 0.64f, y + size * 0.62f, size * 0.12f, size * 0.12f, accent[0], accent[1], accent[2], 0.98f);
    }

    private void drawTooltip(float x, float y, String text) {
        float uiScale = getUiScale();
        float scale = uiScale * 0.86f;
        float width = Math.max(84.0f * uiScale, text.length() * 8.2f * scale);
        float height = 22.0f * uiScale;
        float clampedX = Math.min(x, framebufferWidth - width - 8.0f * uiScale);
        float clampedY = Math.min(y, framebufferHeight - height - 8.0f * uiScale);
        drawRect(clampedX, clampedY, width, height, 0.04f, 0.04f, 0.05f, 0.92f);
        drawOutline(clampedX, clampedY, width, height, 1.4f * uiScale, 0.94f, 0.94f, 0.98f, 0.94f);
        drawShadowText(clampedX + 8.0f * uiScale, clampedY + 15.0f * uiScale, scale, text, 1.0f, 1.0f, 1.0f);
    }

    private void drawOutline(float x, float y, float width, float height, float thickness, float red, float green, float blue, float alpha) {
        drawRect(x, y, width, thickness, red, green, blue, alpha);
        drawRect(x, y + height - thickness, width, thickness, red, green, blue, alpha);
        drawRect(x, y, thickness, height, red, green, blue, alpha);
        drawRect(x + width - thickness, y, thickness, height, red, green, blue, alpha);
    }

    private void drawRect(float x, float y, float width, float height, float red, float green, float blue, float alpha) {
        glColor4f(red, green, blue, alpha);
        glBegin(GL_QUADS);
        glVertex3d(x, y, 0.0);
        glVertex3d(x + width, y, 0.0);
        glVertex3d(x + width, y + height, 0.0);
        glVertex3d(x, y + height, 0.0);
        glEnd();
    }

    private void drawText(float x, float y, float scale, String text, float red, float green, float blue) {
        textBuffer.clear();
        int quads = stb_easy_font_print(0, 0, text, null, textBuffer);
        glColor3f(red, green, blue);
        glPushMatrix();
        glTranslated(x, y, 0.0);
        org.lwjgl.opengl.GL11.glScalef(scale, scale, 1.0f);
        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(2, GL_FLOAT, 16, textBuffer);
        glDrawArrays(GL_QUADS, 0, quads * 4);
        glDisableClientState(GL_VERTEX_ARRAY);
        glPopMatrix();
    }

    private float measureTextWidth(String text, float scale) {
        return text == null ? 0.0f : stb_easy_font_width(text) * scale;
    }

    private void drawCenteredShadowText(float y, float scale, String text, float red, float green, float blue) {
        float x = framebufferWidth * 0.5f - measureTextWidth(text, scale) * 0.5f;
        drawShadowText(x, y, scale, text, red, green, blue);
    }

    private void drawShadowText(float x, float y, float scale, String text, float red, float green, float blue) {
        drawText(x + Math.max(1.0f, scale), y + Math.max(1.0f, scale), scale, text, 0.0f, 0.0f, 0.0f);
        drawText(x, y, scale, text, red, green, blue);
    }

    private int loadTerrainTexture() {
        try {
            BufferedImage atlas = loadOrCreateTerrainAtlas();
            int textureId = glGenTextures();
            if (textureId == 0) {
                throw new IllegalStateException("glGenTextures returned 0");
            }
            glBindTexture(GL_TEXTURE_2D, textureId);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

            int width = atlas.getWidth();
            int height = atlas.getHeight();
            int[] pixels = new int[width * height];
            atlas.getRGB(0, 0, width, height, pixels, 0, width);

            ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
            for (int y = height - 1; y >= 0; y--) {
                for (int x = 0; x < width; x++) {
                    int pixel = pixels[y * width + x];
                    buffer.put((byte) ((pixel >> 16) & 0xFF));
                    buffer.put((byte) ((pixel >> 8) & 0xFF));
                    buffer.put((byte) (pixel & 0xFF));
                    buffer.put((byte) ((pixel >> 24) & 0xFF));
                }
            }
            buffer.flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
            verifyOpenGl("loadTerrainTexture");
            return textureId;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load terrain atlas", exception);
        }
    }

    private int createUploadProbeVbo() {
        int vboId = glGenBuffers();
        if (vboId == 0) {
            throw new IllegalStateException("glGenBuffers returned 0");
        }

        ByteBuffer probe = BufferUtils.createByteBuffer(16);
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glBufferData(GL_ARRAY_BUFFER, probe, GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        verifyOpenGl("createUploadProbeVbo");
        return vboId;
    }

    private BufferedImage loadOrCreateTerrainAtlas() throws IOException {
        File file = new File("terrain.png");
        if (file.exists()) {
            BufferedImage existing = ImageIO.read(file);
            if (existing != null) {
                return existing;
            }
        }

        BufferedImage atlas = new BufferedImage(ATLAS_TILE_SIZE * ATLAS_COLUMNS, ATLAS_TILE_SIZE * ATLAS_ROWS, BufferedImage.TYPE_INT_ARGB);
        fillSolidTile(atlas, 0, 0xFF4CB545);
        fillSolidTile(atlas, 1, 0xFF7D593B);
        fillGrassSideTile(atlas, 2);
        fillSpeckledTile(atlas, 3, 0xFF7B7B7B, 0xFF9A9A9A, 0xFF606060, 22);
        fillSpeckledTile(atlas, 4, 0xFF3D3D3D, 0xFF545454, 0xFF1B1B1B, 26);
        fillSpeckledTile(atlas, 5, 0xFF7B7B7B, 0xFFC09574, 0xFF5C5C5C, 16);
        fillSpeckledTile(atlas, 6, 0xFF7B7B7B, 0xFF4DD5DE, 0xFF5C5C5C, 16);
        fillSpeckledTile(atlas, 7, 0xFF7B7B7B, 0xFF1A1A1A, 0xFF5C5C5C, 18);
        fillSolidTile(atlas, 8, 0xFF61B056);
        fillSolidTile(atlas, 9, 0xFF3A8291);
        fillSolidTile(atlas, 10, 0xFF40458A);
        fillSolidTile(atlas, 11, 0xFF111111);
        fillWaterTile(atlas, 12, 0xA04887E6);
        fillLavaTile(atlas, 13);
        fillSolidTile(atlas, 14, 0xFFD1B286);
        fillSolidTile(atlas, 15, 0xFF8A6A43);
        ImageIO.write(atlas, "png", file);
        return atlas;
    }

    private void fillSolidTile(BufferedImage atlas, int tileIndex, int argb) {
        int startX = (tileIndex % ATLAS_COLUMNS) * ATLAS_TILE_SIZE;
        int startY = (tileIndex / ATLAS_COLUMNS) * ATLAS_TILE_SIZE;
        for (int y = 0; y < ATLAS_TILE_SIZE; y++) {
            for (int x = 0; x < ATLAS_TILE_SIZE; x++) {
                atlas.setRGB(startX + x, startY + y, argb);
            }
        }
    }

    private void fillSpeckledTile(BufferedImage atlas, int tileIndex, int baseArgb, int accentArgb, int shadowArgb, int accentCount) {
        fillSolidTile(atlas, tileIndex, baseArgb);
        int startX = (tileIndex % ATLAS_COLUMNS) * ATLAS_TILE_SIZE;
        int startY = (tileIndex / ATLAS_COLUMNS) * ATLAS_TILE_SIZE;
        for (int i = 0; i < accentCount; i++) {
            int x = (i * 7 + tileIndex * 3) % ATLAS_TILE_SIZE;
            int y = (i * 11 + tileIndex * 5) % ATLAS_TILE_SIZE;
            atlas.setRGB(startX + x, startY + y, accentArgb);
            atlas.setRGB(startX + ((x + 3) % ATLAS_TILE_SIZE), startY + ((y + 5) % ATLAS_TILE_SIZE), shadowArgb);
        }
    }

    private void fillGrassSideTile(BufferedImage atlas, int tileIndex) {
        fillSolidTile(atlas, tileIndex, 0xFF7D593B);
        int startX = (tileIndex % ATLAS_COLUMNS) * ATLAS_TILE_SIZE;
        int startY = (tileIndex / ATLAS_COLUMNS) * ATLAS_TILE_SIZE;
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < ATLAS_TILE_SIZE; x++) {
                atlas.setRGB(startX + x, startY + y, 0xFF4CB545);
            }
        }
        for (int x = 0; x < ATLAS_TILE_SIZE; x += 2) {
            atlas.setRGB(startX + x, startY + 3, 0xFF63C95B);
        }
    }

    private void fillWaterTile(BufferedImage atlas, int tileIndex, int argb) {
        fillSolidTile(atlas, tileIndex, argb);
        int startX = (tileIndex % ATLAS_COLUMNS) * ATLAS_TILE_SIZE;
        int startY = (tileIndex / ATLAS_COLUMNS) * ATLAS_TILE_SIZE;
        for (int y = 0; y < ATLAS_TILE_SIZE; y += 4) {
            for (int x = 0; x < ATLAS_TILE_SIZE; x++) {
                atlas.setRGB(startX + x, startY + y, 0xB06AA6FF);
            }
        }
    }

    private void fillLavaTile(BufferedImage atlas, int tileIndex) {
        fillSolidTile(atlas, tileIndex, 0xFFE97122);
        int startX = (tileIndex % ATLAS_COLUMNS) * ATLAS_TILE_SIZE;
        int startY = (tileIndex / ATLAS_COLUMNS) * ATLAS_TILE_SIZE;
        for (int y = 0; y < ATLAS_TILE_SIZE; y++) {
            for (int x = 0; x < ATLAS_TILE_SIZE; x += 3) {
                atlas.setRGB(startX + x, startY + y, (y + x) % 2 == 0 ? 0xFFFFB347 : 0xFFD95519);
            }
        }
    }

    private float[] getHeldBlockColor(byte block) {
        return getBlockFaceColor(block, Face.TOP);
    }

    private float[] getBlockFaceColor(byte block, Face face) {
        switch (block) {
            case GameConfig.GRASS:
                if (face == Face.TOP) {
                    return new float[]{0.37f, 0.76f, 0.34f};
                }
                return new float[]{0.50f, 0.36f, 0.24f};
            case GameConfig.DIRT:
                return new float[]{0.50f, 0.36f, 0.24f};
            case GameConfig.SAND:
                return new float[]{0.84f, 0.77f, 0.51f};
            case GameConfig.GRAVEL:
                return new float[]{0.44f, 0.45f, 0.43f};
            case GameConfig.CLAY:
                return new float[]{0.58f, 0.66f, 0.68f};
            case GameConfig.STONE:
                return new float[]{0.58f, 0.58f, 0.60f};
            case GameConfig.DEEPSLATE:
                return new float[]{0.28f, 0.29f, 0.32f};
            case GameConfig.COBBLESTONE:
                return new float[]{0.48f, 0.48f, 0.48f};
            case GameConfig.OBSIDIAN:
                return new float[]{0.12f, 0.08f, 0.18f};
            case GameConfig.BEDROCK:
                return new float[]{0.17f, 0.17f, 0.17f};
            case GameConfig.IRON_ORE:
            case GameConfig.DIAMOND_ORE:
            case GameConfig.COAL_ORE:
                return new float[]{0.58f, 0.58f, 0.60f};
            case GameConfig.DEEPSLATE_IRON_ORE:
            case GameConfig.DEEPSLATE_DIAMOND_ORE:
            case GameConfig.DEEPSLATE_COAL_ORE:
                return new float[]{0.28f, 0.29f, 0.32f};
            case GameConfig.OAK_LOG:
                return face == Face.TOP || face == Face.BOTTOM
                    ? new float[]{0.66f, 0.54f, 0.31f}
                    : new float[]{0.54f, 0.38f, 0.20f};
            case GameConfig.OAK_LEAVES:
                return new float[]{0.30f, 0.58f, 0.24f};
            case GameConfig.PINE_LOG:
                return face == Face.TOP || face == Face.BOTTOM
                    ? new float[]{0.58f, 0.48f, 0.30f}
                    : new float[]{0.38f, 0.27f, 0.16f};
            case GameConfig.PINE_LEAVES:
                return new float[]{0.14f, 0.38f, 0.18f};
            case GameConfig.SNOW_LAYER:
                return new float[]{0.78f, 0.86f, 0.92f};
            case GameConfig.CACTUS:
                return face == Face.TOP || face == Face.BOTTOM
                    ? new float[]{0.38f, 0.62f, 0.25f}
                    : new float[]{0.22f, 0.54f, 0.20f};
            case GameConfig.TALL_GRASS:
                return new float[]{0.42f, 0.77f, 0.31f};
            case GameConfig.SEAGRASS:
                return new float[]{0.18f, 0.55f, 0.36f};
            case GameConfig.RED_FLOWER:
                return new float[]{0.91f, 0.18f, 0.22f};
            case GameConfig.YELLOW_FLOWER:
                return new float[]{0.95f, 0.82f, 0.20f};
            case InventoryItems.OAK_PLANKS:
                return new float[]{0.73f, 0.58f, 0.34f};
            case GameConfig.OAK_FENCE:
                return new float[]{0.56f, 0.38f, 0.20f};
            case GameConfig.CHEST:
                return new float[]{0.62f, 0.39f, 0.17f};
            case GameConfig.CRAFTING_TABLE:
                return face == Face.TOP ? new float[]{0.63f, 0.46f, 0.25f} : new float[]{0.48f, 0.31f, 0.16f};
            case GameConfig.FURNACE:
                return face == Face.NORTH ? new float[]{0.40f, 0.40f, 0.38f} : new float[]{0.33f, 0.33f, 0.32f};
            case GameConfig.GLASS:
                return new float[]{0.72f, 0.90f, 0.95f};
            case GameConfig.WHEAT_CROP:
                return new float[]{0.86f, 0.72f, 0.26f};
            case GameConfig.RAIL:
                return new float[]{0.58f, 0.50f, 0.42f};
            case GameConfig.OAK_DOOR:
                return new float[]{0.50f, 0.30f, 0.13f};
            case GameConfig.FARMLAND:
                return face == Face.TOP ? new float[]{0.36f, 0.24f, 0.14f} : new float[]{0.44f, 0.31f, 0.20f};
            case GameConfig.TORCH:
                return new float[]{0.96f, 0.72f, 0.24f};
            case GameConfig.RED_BED:
                return face == Face.TOP ? new float[]{0.78f, 0.12f, 0.12f} : new float[]{0.76f, 0.62f, 0.38f};
            case GameConfig.STRUCTURE_BLOCK:
                return face == Face.TOP ? new float[]{0.70f, 0.55f, 0.86f} : new float[]{0.44f, 0.34f, 0.58f};
            case InventoryItems.STICK:
                return new float[]{0.58f, 0.40f, 0.22f};
            case InventoryItems.BREAD:
                return new float[]{0.78f, 0.50f, 0.24f};
            case InventoryItems.POTATO:
                return new float[]{0.64f, 0.48f, 0.25f};
            case InventoryItems.CARROT:
                return new float[]{0.92f, 0.46f, 0.12f};
            case InventoryItems.WHEAT_SEEDS:
                return new float[]{0.44f, 0.62f, 0.18f};
            case InventoryItems.IRON_INGOT:
            case InventoryItems.IRON_PICKAXE:
            case InventoryItems.IRON_SWORD:
            case InventoryItems.IRON_AXE:
            case InventoryItems.IRON_SHOVEL:
            case InventoryItems.IRON_HOE:
            case InventoryItems.IRON_HELMET:
            case InventoryItems.IRON_CHESTPLATE:
            case InventoryItems.IRON_LEGGINGS:
            case InventoryItems.IRON_BOOTS:
                return new float[]{0.76f, 0.78f, 0.82f};
            case InventoryItems.WOODEN_PICKAXE:
            case InventoryItems.WOODEN_SWORD:
            case InventoryItems.WOODEN_AXE:
            case InventoryItems.WOODEN_SHOVEL:
            case InventoryItems.WOODEN_HOE:
                return new float[]{0.64f, 0.43f, 0.22f};
            case InventoryItems.STONE_PICKAXE:
            case InventoryItems.STONE_SWORD:
            case InventoryItems.STONE_AXE:
            case InventoryItems.STONE_SHOVEL:
            case InventoryItems.STONE_HOE:
                return new float[]{0.52f, 0.52f, 0.52f};
            case InventoryItems.DIAMOND_PICKAXE:
            case InventoryItems.DIAMOND_SWORD:
            case InventoryItems.DIAMOND_AXE:
            case InventoryItems.DIAMOND_SHOVEL:
            case InventoryItems.DIAMOND_HOE:
                return new float[]{0.38f, 0.86f, 0.92f};
            case InventoryItems.NETHERITE_PICKAXE:
            case InventoryItems.NETHERITE_SWORD:
            case InventoryItems.NETHERITE_AXE:
            case InventoryItems.NETHERITE_SHOVEL:
            case InventoryItems.NETHERITE_HOE:
                return new float[]{0.34f, 0.33f, 0.38f};
            case InventoryItems.SHIELD:
                return new float[]{0.58f, 0.42f, 0.22f};
            case InventoryItems.TOTEM:
                return new float[]{0.92f, 0.78f, 0.24f};
            case InventoryItems.PIG_SPAWN_EGG:
                return new float[]{0.95f, 0.58f, 0.70f};
            case InventoryItems.SHEEP_SPAWN_EGG:
                return new float[]{0.90f, 0.90f, 0.84f};
            case InventoryItems.COW_SPAWN_EGG:
                return new float[]{0.42f, 0.28f, 0.18f};
            case InventoryItems.VILLAGER_SPAWN_EGG:
                return new float[]{0.58f, 0.40f, 0.24f};
            case InventoryItems.ZOMBIE_SPAWN_EGG:
                return new float[]{0.38f, 0.69f, 0.33f};
            case InventoryItems.SKELETON_SPAWN_EGG:
                return new float[]{0.82f, 0.82f, 0.78f};
            case InventoryItems.RAW_PORK:
                return new float[]{0.86f, 0.46f, 0.50f};
            case InventoryItems.RAW_BEEF:
                return new float[]{0.58f, 0.18f, 0.14f};
            case InventoryItems.RAW_MUTTON:
                return new float[]{0.72f, 0.28f, 0.30f};
            case InventoryItems.COOKED_PORK:
                return new float[]{0.82f, 0.52f, 0.34f};
            case InventoryItems.COOKED_BEEF:
                return new float[]{0.48f, 0.23f, 0.12f};
            case InventoryItems.COOKED_MUTTON:
                return new float[]{0.58f, 0.30f, 0.18f};
            case InventoryItems.BAKED_POTATO:
                return new float[]{0.78f, 0.58f, 0.30f};
            case InventoryItems.LEATHER:
                return new float[]{0.50f, 0.28f, 0.14f};
            case InventoryItems.WOOL:
                return new float[]{0.88f, 0.88f, 0.82f};
            case InventoryItems.ROTTEN_FLESH:
                return new float[]{0.48f, 0.34f, 0.20f};
            case InventoryItems.BONE:
                return new float[]{0.86f, 0.84f, 0.74f};
            case GameConfig.ZOMBIE_SKIN:
                return new float[]{0.38f, 0.69f, 0.33f};
            case GameConfig.ZOMBIE_SHIRT:
                return new float[]{0.23f, 0.51f, 0.57f};
            case GameConfig.ZOMBIE_PANTS:
                return new float[]{0.25f, 0.27f, 0.53f};
            case GameConfig.ZOMBIE_EYE:
                return new float[]{0.08f, 0.08f, 0.08f};
            case GameConfig.WATER:
            case GameConfig.WATER_SOURCE:
            case GameConfig.WATER_FLOWING:
                return new float[]{0.35f, 0.55f, 0.90f};
            case GameConfig.LAVA:
            case GameConfig.LAVA_SOURCE:
            case GameConfig.LAVA_FLOWING:
                return new float[]{0.92f, 0.42f, 0.12f};
            default:
                return new float[]{1.0f, 0.0f, 1.0f};
        }
    }

    private boolean isOreBlock(byte block) {
        return block == GameConfig.IRON_ORE
            || block == GameConfig.DIAMOND_ORE
            || block == GameConfig.COAL_ORE
            || block == GameConfig.DEEPSLATE_IRON_ORE
            || block == GameConfig.DEEPSLATE_DIAMOND_ORE
            || block == GameConfig.DEEPSLATE_COAL_ORE;
    }

    private float[] oreAccentColor(byte block, float brightness) {
        float red;
        float green;
        float blue;
        switch (block) {
            case GameConfig.IRON_ORE:
            case GameConfig.DEEPSLATE_IRON_ORE:
                red = 0.86f;
                green = 0.70f;
                blue = 0.54f;
                break;
            case GameConfig.DIAMOND_ORE:
            case GameConfig.DEEPSLATE_DIAMOND_ORE:
                red = 0.28f;
                green = 0.92f;
                blue = 0.96f;
                break;
            case GameConfig.COAL_ORE:
            case GameConfig.DEEPSLATE_COAL_ORE:
                red = 0.06f;
                green = 0.06f;
                blue = 0.06f;
                break;
            default:
                red = 1.0f;
                green = 0.0f;
                blue = 1.0f;
                break;
        }
        return new float[]{
            clampColor(red * brightness),
            clampColor(green * brightness),
            clampColor(blue * brightness),
            1.0f
        };
    }

    private void shadeColor(float red, float green, float blue, float brightness) {
        glColor3f(
            clampColor(red * brightness * currentSceneBrightness),
            clampColor(green * brightness * currentSceneBrightness),
            clampColor(blue * brightness * currentSceneBrightness)
        );
    }

    private float clampColor(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private float lerp(float start, float end, float amount) {
        return start + (end - start) * Math.max(0.0f, Math.min(1.0f, amount));
    }

    private void updateCameraEffects(boolean sprinting, int fovDegrees, double deltaTime) {
        double targetFov = clamp(fovDegrees, 55, 100) + (sprinting ? GameConfig.SPRINT_FOV_BOOST : 0.0);
        double blend = Math.min(1.0, deltaTime * 8.0);
        currentFovDegrees += (targetFov - currentFovDegrees) * blend;
    }

    private void markChunkColumnDirty(int chunkX, int chunkZ) {
        for (int chunkY = 0; chunkY < GameConfig.WORLD_CHUNKS_Y; chunkY++) {
            markChunkDirty(chunkX, chunkY, chunkZ);
        }
    }

    private void refreshCameraInsideBlockMesh() {
        boolean insideSolid = isCameraInsideSolidBlock();
        if (!insideSolid && !lastCameraInsideSolidBlock && !lastSpectatorInsideBlock && !spectatorInsideBlock) {
            return;
        }
        if (insideSolid
            && lastCameraInsideSolidBlock
            && cameraBlockX == lastCameraSolidBlockX
            && cameraBlockY == lastCameraSolidBlockY
            && cameraBlockZ == lastCameraSolidBlockZ
            && spectatorInsideBlock == lastSpectatorInsideBlock) {
            return;
        }

        if (lastCameraInsideSolidBlock) {
            markCameraNeighborMeshes(lastCameraSolidBlockX, lastCameraSolidBlockY, lastCameraSolidBlockZ);
        }
        if (insideSolid) {
            markCameraNeighborMeshes(cameraBlockX, cameraBlockY, cameraBlockZ);
            lastCameraSolidBlockX = cameraBlockX;
            lastCameraSolidBlockY = cameraBlockY;
            lastCameraSolidBlockZ = cameraBlockZ;
        } else {
            lastCameraSolidBlockX = Integer.MIN_VALUE;
            lastCameraSolidBlockY = Integer.MIN_VALUE;
            lastCameraSolidBlockZ = Integer.MIN_VALUE;
        }

        lastCameraInsideSolidBlock = insideSolid;
        lastSpectatorInsideBlock = spectatorInsideBlock;
    }

    private boolean isCameraInsideSolidBlock() {
        return world.isSolidBlock(world.getBlock(cameraBlockX, cameraBlockY, cameraBlockZ));
    }

    private void markCameraNeighborMeshes(int blockX, int blockY, int blockZ) {
        if (!GameConfig.isWorldYInside(blockY)) {
            return;
        }
        int chunkX = Math.floorDiv(blockX, GameConfig.CHUNK_SIZE);
        int chunkY = GameConfig.sectionIndexForY(blockY);
        int chunkZ = Math.floorDiv(blockZ, GameConfig.CHUNK_SIZE);
        markChunkDirty(chunkX, chunkY, chunkZ);
        markChunkDirty(Math.floorDiv(blockX + 1, GameConfig.CHUNK_SIZE), chunkY, chunkZ);
        markChunkDirty(Math.floorDiv(blockX - 1, GameConfig.CHUNK_SIZE), chunkY, chunkZ);
        if (GameConfig.isWorldYInside(blockY + 1)) {
            markChunkDirty(chunkX, GameConfig.sectionIndexForY(blockY + 1), chunkZ);
        }
        if (GameConfig.isWorldYInside(blockY - 1)) {
            markChunkDirty(chunkX, GameConfig.sectionIndexForY(blockY - 1), chunkZ);
        }
        markChunkDirty(chunkX, chunkY, Math.floorDiv(blockZ + 1, GameConfig.CHUNK_SIZE));
        markChunkDirty(chunkX, chunkY, Math.floorDiv(blockZ - 1, GameConfig.CHUNK_SIZE));
    }

    private void markChunkDirty(int chunkX, int chunkY, int chunkZ) {
        if (chunkY < 0 || chunkY >= GameConfig.WORLD_CHUNKS_Y) {
            return;
        }
        dirtyChunkMeshes.add(chunkKey(chunkX, chunkY, chunkZ));
    }

    private UiRenderer.InventoryUiLayout buildInventoryLayout(boolean creativeMode, int creativeTab) {
        return buildInventoryLayout(creativeMode, creativeTab, GameConfig.INVENTORY_SCREEN_PLAYER);
    }

    private UiRenderer.InventoryUiLayout buildInventoryLayout(boolean creativeMode, int creativeTab, int inventoryScreenMode) {
        return uiRenderer.buildInventoryLayout(creativeMode, creativeTab, framebufferWidth, framebufferHeight, getInventoryUiScale(), inventoryScreenMode);
    }

    private void verifyOpenGl(String step) {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new IllegalStateException("OpenGL error at " + step + ": " + error);
        }
    }

    private void logOpenGlError(String step) {
        if (!GameConfig.ENABLE_DEBUG_LOGS) {
            return;
        }
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            System.err.println("OpenGL warning at " + step + ": " + error);
        }
    }

    private float getUiScale() {
        return Math.max(1.4f, framebufferHeight / 720.0f * 1.4f);
    }

    private float getInventoryUiScale() {
        float desired = Math.max(1.0f, framebufferHeight / 720.0f * 1.18f * Settings.inventoryUiScale());
        float maxByWidth = Math.max(0.85f, (framebufferWidth - 32.0f) / 470.0f);
        float maxByHeight = Math.max(0.85f, (framebufferHeight - 32.0f) / 388.0f);
        return Math.max(0.85f, Math.min(desired, Math.min(maxByWidth, maxByHeight)));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static final class MeshBuildCandidate implements Comparable<MeshBuildCandidate> {
        final int chunkX;
        final int chunkY;
        final int chunkZ;
        final long meshKey;
        final boolean visible;
        final double distanceSquared;

        MeshBuildCandidate(int chunkX, int chunkY, int chunkZ, long meshKey, boolean visible, double distanceSquared) {
            this.chunkX = chunkX;
            this.chunkY = chunkY;
            this.chunkZ = chunkZ;
            this.meshKey = meshKey;
            this.visible = visible;
            this.distanceSquared = distanceSquared;
        }

        @Override
        public int compareTo(MeshBuildCandidate other) {
            if (visible != other.visible) {
                return visible ? -1 : 1;
            }
            return Double.compare(distanceSquared, other.distanceSquared);
        }
    }

    private static final class FloatArrayBuilder {
        private float[] values;
        private float[] sortScratch;
        private FloatBuffer uploadBuffer;
        private int size;

        private FloatArrayBuilder(int initialCapacity) {
            values = new float[Math.max(FLOATS_PER_VERTEX * 4, initialCapacity)];
        }

        void put(float value) {
            if (size >= values.length) {
                float[] next = new float[values.length * 2];
                System.arraycopy(values, 0, next, 0, values.length);
                values = next;
            }
            values[size++] = value;
        }

        void reset(int initialCapacity) {
            if (values.length < initialCapacity) {
                values = new float[initialCapacity];
            }
            size = 0;
        }

        void clear() {
            size = 0;
        }

        boolean isEmpty() {
            return size == 0;
        }

        int vertexCount() {
            return size / FLOATS_PER_VERTEX;
        }

        void sortQuadsFarToNear(double cameraX, double cameraY, double cameraZ) {
            int floatsPerQuad = FLOATS_PER_VERTEX * 4;
            int quadCount = size / floatsPerQuad;
            if (quadCount <= 1) {
                return;
            }

            Integer[] order = new Integer[quadCount];
            double[] distances = new double[quadCount];
            for (int quad = 0; quad < quadCount; quad++) {
                order[quad] = quad;
                int base = quad * floatsPerQuad;
                double centerX = 0.0;
                double centerY = 0.0;
                double centerZ = 0.0;
                for (int vertex = 0; vertex < 4; vertex++) {
                    int offset = base + vertex * FLOATS_PER_VERTEX;
                    centerX += values[offset];
                    centerY += values[offset + 1];
                    centerZ += values[offset + 2];
                }
                centerX *= 0.25;
                centerY *= 0.25;
                centerZ *= 0.25;
                double dx = centerX - cameraX;
                double dy = centerY - cameraY;
                double dz = centerZ - cameraZ;
                distances[quad] = dx * dx + dy * dy + dz * dz;
            }

            java.util.Arrays.sort(order, (a, b) -> Double.compare(distances[b], distances[a]));
            if (sortScratch == null || sortScratch.length < size) {
                sortScratch = new float[size];
            }
            for (int targetQuad = 0; targetQuad < quadCount; targetQuad++) {
                int sourceOffset = order[targetQuad] * floatsPerQuad;
                int targetOffset = targetQuad * floatsPerQuad;
                System.arraycopy(values, sourceOffset, sortScratch, targetOffset, floatsPerQuad);
            }
            float[] previous = values;
            values = sortScratch;
            sortScratch = previous;
        }

        FloatBuffer toFloatBuffer() {
            if (uploadBuffer == null || uploadBuffer.capacity() < size) {
                uploadBuffer = BufferUtils.createFloatBuffer(size);
            }
            uploadBuffer.clear();
            uploadBuffer.put(values, 0, size);
            uploadBuffer.flip();
            return uploadBuffer;
        }
    }
}
