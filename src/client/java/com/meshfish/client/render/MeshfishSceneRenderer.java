package com.meshfish.client.render;

import com.meshfish.Meshfish;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelTerrainRenderContext;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

public final class MeshfishSceneRenderer {
    private static final int SCAN_RADIUS_XZ = 24;
    private static final int SCAN_RADIUS_UP = 24;
    private static final int SCAN_RADIUS_DOWN = 80;
    private static final int MAX_MESH_TASK_GROUPS_PER_DRAW = 65535;
    private static final int MAX_SCENE_VERTICES_PER_DRAW = MAX_MESH_TASK_GROUPS_PER_DRAW * 3;
    private static final Object PENDING_LOCK = new Object();
    private static final Direction[] FACE_DIRECTIONS = {
        Direction.WEST,
        Direction.EAST,
        Direction.NORTH,
        Direction.SOUTH,
        Direction.DOWN,
        Direction.UP
    };
    private static final int[][] FACE_VERTEX_CORNERS = {
        {0, 2, 6, 0, 6, 4},
        {1, 5, 7, 1, 7, 3},
        {0, 1, 3, 0, 3, 2},
        {4, 6, 7, 4, 7, 5},
        {0, 4, 5, 0, 5, 1},
        {2, 3, 7, 2, 7, 6}
    };
    private static final RandomSource MODEL_RANDOM = RandomSource.createThreadLocalInstance(0L);
    private static final RenderPipeline SCENE_PIPELINE = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("meshfish", "scene_mesh"))
        .withVertexShader(Identifier.fromNamespaceAndPath("meshfish", "scene_mesh"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("meshfish", "scene_fragment"))
        .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
        .withCull(false)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .build();
    private static boolean initialized;
    private static ByteBuffer pendingScene = ByteBuffer.allocateDirect(0);
    private static int pendingBlockCount;
    private static int pendingModelCount;
    private static int pendingQuadCount;
    private static int pendingPrimitiveCount;
    private static int pendingSourceBlockCount;
    private static boolean loggedExtraction;
    private static boolean loggedEmptyExtraction;
    private static boolean loggedUpload;
    private static boolean loggedRender;
    private static boolean loggedTexture;
    private static int loggedModels;

    private MeshfishSceneRenderer() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;
        LevelRenderEvents.END_EXTRACTION.register(MeshfishSceneRenderer::extract);
        LevelRenderEvents.START_MAIN.register(MeshfishSceneRenderer::upload);
    }

    private static void extract(LevelExtractionContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || context.levelState().cameraRenderState == null) {
            setPendingScene(ByteBuffer.allocateDirect(0), 0, 0, 0, 0, 0);
            return;
        }

        BlockPos cameraBlock = BlockPos.containing(context.levelState().cameraRenderState.pos);
        SceneBuild scene = new SceneBuild();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int maxYDistance = Math.max(SCAN_RADIUS_UP, SCAN_RADIUS_DOWN);
        for (int yDistance = 0; yDistance <= maxYDistance && scene.sourceBlocks < MeshfishBuffers.MAX_SCENE_BLOCKS; yDistance++) {
            if (yDistance <= SCAN_RADIUS_UP) {
                scanYLayer(minecraft, level, cameraBlock, yDistance, pos, scene);
            }
            if (yDistance != 0 && yDistance <= SCAN_RADIUS_DOWN && scene.sourceBlocks < MeshfishBuffers.MAX_SCENE_BLOCKS) {
                scanYLayer(minecraft, level, cameraBlock, -yDistance, pos, scene);
            }
        }

        ByteBuffer sceneData = scene.finish();
        logExtraction(cameraBlock, scene);
        setPendingScene(sceneData, scene.blocks, scene.models, scene.quads, scene.primitives, scene.sourceBlocks);
    }

    private static void scanYLayer(
        Minecraft minecraft,
        ClientLevel level,
        BlockPos cameraBlock,
        int yOffset,
        BlockPos.MutableBlockPos pos,
        SceneBuild scene
    ) {
        for (int z = -SCAN_RADIUS_XZ; z <= SCAN_RADIUS_XZ && scene.sourceBlocks < MeshfishBuffers.MAX_SCENE_BLOCKS; z++) {
            for (int x = -SCAN_RADIUS_XZ; x <= SCAN_RADIUS_XZ && scene.sourceBlocks < MeshfishBuffers.MAX_SCENE_BLOCKS; x++) {
                pos.set(cameraBlock.getX() + x, cameraBlock.getY() + yOffset, cameraBlock.getZ() + z);
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }

                if (putBlockInstance(minecraft, level, state, pos, scene)) {
                    scene.sourceBlocks++;
                }
            }
        }
    }

    private static void setPendingScene(ByteBuffer sceneData, int blockCount, int modelCount, int quadCount, int primitiveCount, int sourceBlockCount) {
        synchronized (PENDING_LOCK) {
            pendingScene = sceneData;
            pendingBlockCount = blockCount;
            pendingModelCount = modelCount;
            pendingQuadCount = quadCount;
            pendingPrimitiveCount = primitiveCount;
            pendingSourceBlockCount = sourceBlockCount;
        }
    }

    private static void upload(LevelTerrainRenderContext context) {
        ByteBuffer sceneData;
        int blockCount;
        int modelCount;
        int quadCount;
        int primitiveCount;
        int sourceBlockCount;
        synchronized (PENDING_LOCK) {
            sceneData = pendingScene.slice();
            blockCount = pendingBlockCount;
            modelCount = pendingModelCount;
            quadCount = pendingQuadCount;
            primitiveCount = pendingPrimitiveCount;
            sourceBlockCount = pendingSourceBlockCount;
        }

        MeshfishBuffers.uploadScene(sceneData, blockCount, modelCount, quadCount, primitiveCount);
        if (!loggedUpload && blockCount > 0) {
            loggedUpload = true;
            Meshfish.LOGGER.info(
                "Meshfish scene upload: sourceBlocks={}, blocks={}, models={}, quads={}, primitives={}, gpuBlocks={}, frame={}",
                sourceBlockCount,
                blockCount,
                modelCount,
                quadCount,
                primitiveCount,
                MeshfishBuffers.getSceneBlockCount(),
                MeshfishBuffers.getFrameIndex()
            );
        }
    }

    private static boolean putBlockInstance(
        Minecraft minecraft,
        ClientLevel level,
        BlockState state,
        BlockPos pos,
        SceneBuild scene
    ) {
        int visibleMask = 0;
        for (int face = 0; face < FACE_DIRECTIONS.length; face++) {
            if (isFaceVisible(level, pos, FACE_DIRECTIONS[face])) {
                visibleMask |= 1 << face;
            }
        }
        if (visibleMask == 0) {
            return false;
        }

        int modelIndex = scene.modelIndex(minecraft, state);
        if (modelIndex < 0) {
            return false;
        }

        Vec3 offset = state.getOffset(pos);
        return scene.putBlock(
            pos.getX() + (float) offset.x,
            pos.getY() + (float) offset.y,
            pos.getZ() + (float) offset.z,
            modelIndex,
            visibleMask
        );
    }

    private static boolean isFaceVisible(ClientLevel level, BlockPos pos, Direction direction) {
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        neighbor.setWithOffset(pos, direction);
        BlockState neighborState = level.getBlockState(neighbor);
        return Block.shouldRenderFace(level.getBlockState(pos), neighborState, direction);
    }

    private static boolean hasQuads(List<BlockStateModelPart> parts, Direction direction) {
        for (BlockStateModelPart part : parts) {
            if (!part.getQuads(direction).isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private static int faceIndex(Direction direction, int fallback) {
        if (direction == null) {
            return fallback;
        }

        for (int i = 0; i < FACE_DIRECTIONS.length; i++) {
            if (FACE_DIRECTIONS[i] == direction) {
                return i;
            }
        }

        return fallback;
    }

    public static void renderInTerrainPass(RenderPass renderPass) {
        if (!MeshfishPipelineRegistry.meshShadersAvailable) {
            return;
        }

        int sceneBlockCount = MeshfishBuffers.getSceneBlockCount();
        if (sceneBlockCount <= 0) {
            if (!loggedEmptyExtraction) {
                loggedEmptyExtraction = true;
                Meshfish.LOGGER.warn("Meshfish scene render skipped: no scene blocks reached the GPU buffer");
            }
            return;
        }

        renderPass.setPipeline(SCENE_PIPELINE);
        TextureAtlas blockAtlas = blockAtlas();
        if (!loggedTexture) {
            loggedTexture = true;
            Meshfish.LOGGER.info(
                "Meshfish scene texture: atlas={}, view={}, sampler={}",
                blockAtlas.location(),
                blockAtlas.getTextureView().getClass().getName(),
                blockAtlas.getSampler().getClass().getName()
            );
        }
        MeshfishBuffers.bindSceneTexture(blockAtlas.getTextureView(), blockAtlas.getSampler());
        if (!loggedRender) {
            loggedRender = true;
            Meshfish.LOGGER.info(
                "Meshfish scene render: blocks={}, models={}, quads={}, primitives={}, vertices={}, frame={}",
                sceneBlockCount,
                MeshfishBuffers.getSceneModelCount(),
                MeshfishBuffers.getSceneQuadCount(),
                MeshfishBuffers.getScenePrimitiveCount(),
                MeshfishBuffers.getSceneVertexCount(),
                MeshfishBuffers.getFrameIndex()
            );
        }
        int sceneVertexCount = MeshfishBuffers.getSceneVertexCount();
        for (int firstVertex = 0; firstVertex < sceneVertexCount; firstVertex += MAX_SCENE_VERTICES_PER_DRAW) {
            int drawVertexCount = Math.min(MAX_SCENE_VERTICES_PER_DRAW, sceneVertexCount - firstVertex);
            renderPass.draw(firstVertex, drawVertexCount);
        }
    }

    private static TextureAtlas blockAtlas() {
        if (Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS) instanceof TextureAtlas atlas) {
            return atlas;
        }

        return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(Identifier.withDefaultNamespace("blocks"));
    }

    private static void logExtraction(BlockPos cameraBlock, SceneBuild scene) {
        if (loggedExtraction || scene.blocks <= 0) {
            return;
        }

        loggedExtraction = true;
        Meshfish.LOGGER.info(
            "Meshfish scene extraction: cameraBlock={}, sourceBlocks={}, blocks={}, models={}, quads={}, primitives={}, bounds=({}, {}, {})..({}, {}, {}), topModels={}",
            cameraBlock,
            scene.sourceBlocks,
            scene.blocks,
            scene.models,
            scene.quads,
            scene.primitives,
            scene.minX,
            scene.minY,
            scene.minZ,
            scene.maxX,
            scene.maxY,
            scene.maxZ,
            scene.topModelSummary()
        );
    }

    private static final class SceneBuild {
        private final ByteBuffer data = ByteBuffer
            .allocateDirect(MeshfishBuffers.SCENE_BUFFER_SIZE)
            .order(ByteOrder.nativeOrder());
        private final Map<BlockState, Integer> modelIndices = new HashMap<>();
        private final List<BlockState> modelStates = new ArrayList<>();
        private final int[] modelBlockCounts = new int[MeshfishBuffers.MAX_SCENE_MODELS];
        private int blocks;
        private int models;
        private int quads;
        private int primitives;
        private int sourceBlocks;
        private float minX = Float.POSITIVE_INFINITY;
        private float minY = Float.POSITIVE_INFINITY;
        private float minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY;
        private float maxY = Float.NEGATIVE_INFINITY;
        private float maxZ = Float.NEGATIVE_INFINITY;

        private int modelIndex(Minecraft minecraft, BlockState state) {
            Integer existing = this.modelIndices.get(state);
            if (existing != null) {
                return existing;
            }

            if (this.models >= MeshfishBuffers.MAX_SCENE_MODELS) {
                return -1;
            }

            int firstQuad = this.quads;
            int modelIndex = this.models++;
            BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(state);
            this.modelStates.add(state);

            MODEL_RANDOM.setSeed(0L);
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(MODEL_RANDOM, parts);
            boolean hasUnculledQuads = hasQuads(parts, null);

            for (int face = 0; face < FACE_DIRECTIONS.length && this.quads - firstQuad < MeshfishBuffers.MAX_MODEL_QUADS; face++) {
                for (BlockStateModelPart part : parts) {
                    for (BakedQuad quad : part.getQuads(FACE_DIRECTIONS[face])) {
                        if (!this.putQuad(quad, face, firstQuad)) {
                            break;
                        }
                    }
                }
            }

            for (BlockStateModelPart part : parts) {
                for (BakedQuad quad : part.getQuads(null)) {
                    if (!this.putQuad(quad, 6, firstQuad)) {
                        break;
                    }
                }
            }

            if (this.quads == firstQuad && !hasUnculledQuads) {
                TextureAtlasSprite fallback = model.particleMaterial().sprite();
                if (fallback != null) {
                    for (int face = 0; face < FACE_DIRECTIONS.length && this.quads - firstQuad < MeshfishBuffers.MAX_MODEL_QUADS; face++) {
                        this.putFallbackQuad(fallback, face, firstQuad);
                    }
                }
            }

            int modelOffset = MeshfishBuffers.SCENE_MODELS_OFFSET + modelIndex * MeshfishBuffers.SCENE_MODEL_RECORD_SIZE;
            this.data.putInt(modelOffset, firstQuad);
            this.data.putInt(modelOffset + 4, this.quads - firstQuad);
            logModel(state, firstQuad, this.quads - firstQuad);
            this.modelIndices.put(state, modelIndex);
            return modelIndex;
        }

        private void logModel(BlockState state, int firstQuad, int quadCount) {
            if (loggedModels >= 16) {
                return;
            }

            loggedModels++;
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
            for (int quad = 0; quad < quadCount; quad++) {
                int quadOffset = MeshfishBuffers.SCENE_QUADS_OFFSET + (firstQuad + quad) * MeshfishBuffers.SCENE_QUAD_RECORD_SIZE;
                for (int vertex = 0; vertex < 4; vertex++) {
                    int vertexOffset = quadOffset + vertex * 20;
                    float x = this.data.getFloat(vertexOffset);
                    float y = this.data.getFloat(vertexOffset + 4);
                    float z = this.data.getFloat(vertexOffset + 8);
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                }
            }
            Meshfish.LOGGER.info(
                "Meshfish model {}: state={}, quads={}, localBounds=({}, {}, {})..({}, {}, {})",
                loggedModels,
                state,
                quadCount,
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ
            );
        }

        private boolean putBlock(float x, float y, float z, int modelIndex, int visibleMask) {
            if (this.blocks >= MeshfishBuffers.MAX_SCENE_BLOCKS) {
                return false;
            }

            int blockIndex = this.blocks;
            int offset = MeshfishBuffers.SCENE_BLOCKS_OFFSET + this.blocks * MeshfishBuffers.SCENE_BLOCK_RECORD_SIZE;
            this.data.putFloat(offset, x);
            this.data.putFloat(offset + 4, y);
            this.data.putFloat(offset + 8, z);
            this.data.putInt(offset + 12, modelIndex);
            this.data.putInt(offset + 16, visibleMask);
            this.data.putInt(offset + 20, 0xffffffff);
            this.data.putInt(offset + 24, 0);
            this.data.putInt(offset + 28, 0);
            this.modelBlockCounts[modelIndex]++;
            this.minX = Math.min(this.minX, x);
            this.minY = Math.min(this.minY, y);
            this.minZ = Math.min(this.minZ, z);
            this.maxX = Math.max(this.maxX, x);
            this.maxY = Math.max(this.maxY, y);
            this.maxZ = Math.max(this.maxZ, z);
            this.blocks++;
            this.putBlockPrimitives(blockIndex, modelIndex, visibleMask);
            return true;
        }

        private void putBlockPrimitives(int blockIndex, int modelIndex, int visibleMask) {
            int modelOffset = MeshfishBuffers.SCENE_MODELS_OFFSET + modelIndex * MeshfishBuffers.SCENE_MODEL_RECORD_SIZE;
            int firstQuad = this.data.getInt(modelOffset);
            int quadCount = this.data.getInt(modelOffset + 4);
            for (int slot = 0; slot < quadCount && this.primitives < MeshfishBuffers.MAX_SCENE_PRIMITIVES; slot++) {
                int quadIndex = firstQuad + slot;
                int face = this.data.getInt(MeshfishBuffers.SCENE_QUADS_OFFSET + quadIndex * MeshfishBuffers.SCENE_QUAD_RECORD_SIZE + 80);
                if (face < 6 && (visibleMask & (1 << face)) == 0) {
                    continue;
                }

                int primitiveOffset = MeshfishBuffers.SCENE_PRIMITIVES_OFFSET + this.primitives * MeshfishBuffers.SCENE_PRIMITIVE_RECORD_SIZE;
                if (primitiveOffset + MeshfishBuffers.SCENE_PRIMITIVE_RECORD_SIZE > MeshfishBuffers.SCENE_BUFFER_SIZE) {
                    return;
                }

                this.data.putInt(primitiveOffset, blockIndex);
                this.data.putInt(primitiveOffset + 4, quadIndex);
                this.primitives++;
            }
        }

        private String topModelSummary() {
            StringBuilder summary = new StringBuilder();
            boolean[] used = new boolean[this.models];
            for (int rank = 0; rank < 8; rank++) {
                int bestIndex = -1;
                int bestCount = 0;
                for (int i = 0; i < this.models; i++) {
                    if (!used[i] && this.modelBlockCounts[i] > bestCount) {
                        bestIndex = i;
                        bestCount = this.modelBlockCounts[i];
                    }
                }
                if (bestIndex < 0) {
                    break;
                }
                used[bestIndex] = true;
                if (summary.length() > 0) {
                    summary.append("; ");
                }
                summary.append(bestCount)
                    .append("x ")
                    .append(this.modelStates.get(bestIndex));
            }
            return summary.toString();
        }

        private boolean putQuad(BakedQuad quad, int face, int modelFirstQuad) {
            if (this.quads - modelFirstQuad >= MeshfishBuffers.MAX_MODEL_QUADS || !this.hasQuadCapacity()) {
                return false;
            }

            int offset = MeshfishBuffers.SCENE_QUADS_OFFSET + this.quads * MeshfishBuffers.SCENE_QUAD_RECORD_SIZE;
            for (int vertex = 0; vertex < 4; vertex++) {
                Vector3fc local = quad.position(vertex);
                long packedUv = quad.packedUV(vertex);
                int vertexOffset = offset + vertex * 20;
                this.data.putFloat(vertexOffset, local.x());
                this.data.putFloat(vertexOffset + 4, local.y());
                this.data.putFloat(vertexOffset + 8, local.z());
                this.data.putFloat(vertexOffset + 12, UVPair.unpackU(packedUv));
                this.data.putFloat(vertexOffset + 16, UVPair.unpackV(packedUv));
            }
            this.data.putInt(offset + 80, faceIndex(quad.direction(), face));
            this.data.putInt(offset + 84, 0);
            this.data.putInt(offset + 88, 0);
            this.data.putInt(offset + 92, 0);
            this.quads++;
            return true;
        }

        private boolean putFallbackQuad(TextureAtlasSprite sprite, int face, int modelFirstQuad) {
            if (this.quads - modelFirstQuad >= MeshfishBuffers.MAX_MODEL_QUADS || !this.hasQuadCapacity()) {
                return false;
            }

            int offset = MeshfishBuffers.SCENE_QUADS_OFFSET + this.quads * MeshfishBuffers.SCENE_QUAD_RECORD_SIZE;
            int[] corners = {
                FACE_VERTEX_CORNERS[face][0],
                FACE_VERTEX_CORNERS[face][1],
                FACE_VERTEX_CORNERS[face][2],
                FACE_VERTEX_CORNERS[face][5]
            };
            for (int vertex = 0; vertex < 4; vertex++) {
                int corner = corners[vertex];
                int vertexOffset = offset + vertex * 20;
                this.data.putFloat(vertexOffset, corner & 1);
                this.data.putFloat(vertexOffset + 4, (corner >> 1) & 1);
                this.data.putFloat(vertexOffset + 8, (corner >> 2) & 1);
                putFallbackUv(this.data, vertexOffset + 12, sprite, face, corner);
            }
            this.data.putInt(offset + 80, face);
            this.data.putInt(offset + 84, 0);
            this.data.putInt(offset + 88, 0);
            this.data.putInt(offset + 92, 0);
            this.quads++;
            return true;
        }

        private boolean hasQuadCapacity() {
            return this.quads < MeshfishBuffers.MAX_SCENE_QUADS;
        }

        private ByteBuffer finish() {
            this.data.putInt(0, this.blocks);
            this.data.putInt(4, this.models);
            this.data.putInt(8, this.quads);
            this.data.putInt(12, this.primitives);
            ByteBuffer out = this.data.duplicate().order(ByteOrder.nativeOrder());
            int usedBytes = MeshfishBuffers.SCENE_PRIMITIVES_OFFSET + this.primitives * MeshfishBuffers.SCENE_PRIMITIVE_RECORD_SIZE;
            out.position(0);
            out.limit(Math.min(usedBytes, MeshfishBuffers.SCENE_BUFFER_SIZE));
            return out.slice().order(ByteOrder.nativeOrder());
        }
    }

    private static void putFallbackUv(ByteBuffer data, int offset, TextureAtlasSprite sprite, int face, int corner) {
        float x = corner & 1;
        float y = (corner >> 1) & 1;
        float z = (corner >> 2) & 1;
        float u;
        float v;
        if (face < 2) {
            u = z;
            v = 1.0F - y;
        } else if (face < 4) {
            u = x;
            v = z;
        } else {
            u = x;
            v = 1.0F - y;
        }

        data.putFloat(offset, sprite.getU(u));
        data.putFloat(offset + 4, sprite.getV(v));
    }
}
