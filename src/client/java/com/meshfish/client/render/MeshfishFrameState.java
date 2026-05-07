package com.meshfish.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelTerrainRenderContext;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

public final class MeshfishFrameState {
    private static final int FRUSTUM_PLANE_COUNT = 6;
    private static final int FRAME_UNIFORM_SIZE = 256;
    private static final Object LOCK = new Object();
    private static final float[] frustumPlanes = new float[FRUSTUM_PLANE_COUNT * 4];
    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;
    private static boolean initialized;

    private MeshfishFrameState() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;
        LevelRenderEvents.END_EXTRACTION.register(MeshfishFrameState::extract);
        LevelRenderEvents.START_MAIN.register(MeshfishFrameState::upload);
    }

    private static void extract(LevelExtractionContext context) {
        CameraRenderState camera = context.levelState().cameraRenderState;
        Matrix4f viewProjection = new Matrix4f();
        camera.projectionMatrix.mul(camera.viewRotationMatrix, viewProjection);

        synchronized (LOCK) {
            Vector4f plane = new Vector4f();
            for (int i = 0; i < FRUSTUM_PLANE_COUNT; i++) {
                viewProjection.frustumPlane(i, plane);
                int base = i * 4;
                frustumPlanes[base] = plane.x;
                frustumPlanes[base + 1] = plane.y;
                frustumPlanes[base + 2] = plane.z;
                frustumPlanes[base + 3] = plane.w;
            }

            cameraX = camera.pos.x();
            cameraY = camera.pos.y();
            cameraZ = camera.pos.z();
        }
    }

    private static void upload(LevelTerrainRenderContext context) {
        MeshfishBuffers.nextFrame();
        GpuBuffer uniformBuffer = MeshfishBuffers.getCurrentCameraUniformBuffer();
        if (uniformBuffer == null || uniformBuffer.isClosed()) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.calloc(FRAME_UNIFORM_SIZE).order(ByteOrder.nativeOrder());
            synchronized (LOCK) {
                Matrix4f viewProjection = new Matrix4f();
                CameraRenderState camera = context.levelState().cameraRenderState;
                camera.projectionMatrix.mul(camera.viewRotationMatrix, viewProjection);
                viewProjection.getTransposed(data);
                data.position(64);

                for (float value : frustumPlanes) {
                    data.putFloat(value);
                }
                data.putFloat((float) cameraX);
                data.putFloat((float) cameraY);
                data.putFloat((float) cameraZ);
                data.putFloat(1.0f);
            }
            data.putInt(1);
            data.flip();

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.writeToBuffer(uniformBuffer.slice(0L, FRAME_UNIFORM_SIZE), data);
            encoder.submit();
        }
    }
}
