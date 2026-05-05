package com.meshfish.client.mixin;

import com.meshfish.Meshfish;
import com.meshfish.client.render.MeshfishBuffers;
import com.meshfish.client.render.MeshfishPipelineRegistry;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import com.mojang.blaze3d.vulkan.glsl.MeshfishSpirvPipelineCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanDevice.class)
public class VulkanDevicePipelineMixin {

    @Inject(method = "compilePipeline", at = @At("HEAD"), cancellable = true)
    private void meshfish$compilePipeline(RenderPipeline pipeline, ShaderSource shaderSource, CallbackInfoReturnable<VulkanRenderPipeline> cir) {
        if (!MeshfishSpirvPipelineCompiler.shouldHandle(pipeline)) {
            return;
        }

        if (!MeshfishPipelineRegistry.meshShadersAvailable) {
            Meshfish.LOGGER.warn("Mesh shaders were requested for pipeline {}, but VK_EXT_mesh_shader is not available; falling back to vanilla compilation", pipeline.getLocation());
            return;
        }

        VulkanDevice device = (VulkanDevice) (Object) this;
        try {
            MeshfishBuffers.initialize(device);
            cir.setReturnValue(MeshfishSpirvPipelineCompiler.compile(device, pipeline));
        } catch (Exception e) {
            Meshfish.LOGGER.error("Failed to compile Meshfish SPIR-V pipeline {}", pipeline.getLocation(), e);
            cir.setReturnValue(new VulkanRenderPipeline(
                pipeline,
                device,
                0L,
                0L,
                0L,
                VulkanBindGroupLayout.INVALID_LAYOUT,
                0L,
                0L
            ));
        }
    }
}
