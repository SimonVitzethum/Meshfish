package com.meshfish.client.mixin;

import com.meshfish.client.render.MeshfishSceneRenderer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {
    @Inject(
        method = "renderGroup",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
            ordinal = 1,
            shift = At.Shift.AFTER
        ),
        locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void meshfish$renderSceneInTerrainPass(
        ChunkSectionLayerGroup group,
        GpuSampler sampler,
        CallbackInfo ci,
        RenderSystem.AutoStorageIndexBuffer sequentialBuffer,
        GpuBuffer indexBuffer,
        VertexFormat.IndexType indexType,
        net.minecraft.client.renderer.chunk.ChunkSectionLayer[] layers,
        Minecraft minecraft,
        boolean wireframe,
        com.mojang.blaze3d.pipeline.RenderTarget target,
        RenderPass renderPass
    ) {
        if (group == ChunkSectionLayerGroup.OPAQUE && !wireframe) {
            MeshfishSceneRenderer.renderInTerrainPass(renderPass);
        }
    }
}
