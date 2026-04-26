package com.meshfish.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public final class MeshfishDebugRenderer {
    private static final RenderPipeline DEBUG_PIPELINE = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("meshfish", "debug_mesh"))
        .withVertexShader(Identifier.fromNamespaceAndPath("meshfish", "mesh"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("meshfish", "fragment"))
        .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
        .withCull(false)
        .withDepthStencilState(Optional.empty())
        .build();

    private MeshfishDebugRenderer() {
    }

    public static void initialize() {
        LevelRenderEvents.END_MAIN.register(MeshfishDebugRenderer::render);
    }

    private static void render(LevelRenderContext context) {
        if (!MeshfishPipelineRegistry.meshShadersAvailable) {
            return;
        }

        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        if (target == null || target.getColorTextureView().isClosed()) {
            return;
        }

        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass renderPass = commandEncoder.createRenderPass(
            () -> "Meshfish debug mesh pass",
            target.getColorTextureView(),
            OptionalInt.empty(),
            target.useDepth ? target.getDepthTextureView() : null,
            OptionalDouble.empty()
        )) {
            renderPass.setPipeline(DEBUG_PIPELINE);
            renderPass.draw(0, 1);
        }

        commandEncoder.submit();
    }
}
