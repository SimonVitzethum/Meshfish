package com.meshfish.client.mixin;

import com.meshfish.client.render.MeshfishPipelineRegistry;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanRenderPass.class)
public abstract class VulkanRenderPassMixin {
    @Shadow
    protected VulkanRenderPipeline pipeline;

    @Shadow
    private VkCommandBuffer secondaryCommandBuffer() {
        throw new AssertionError();
    }

    @Shadow
    private void pushDescriptors() {
        throw new AssertionError();
    }

    @Inject(method = "setPipeline", at = @At("TAIL"))
    private void meshfish$bindMeshDescriptorSet(RenderPipeline pipelineInfo, CallbackInfo ci) {
        if (this.pipeline == null || !MeshfishPipelineRegistry.isMeshfishPipeline(this.pipeline)) {
            return;
        }

        long descriptorSet = MeshfishBuffers.descriptorSets[MeshfishBuffers.getFrameIndex()];
        if (descriptorSet == 0L) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindDescriptorSets(
                this.secondaryCommandBuffer(),
                VK12.VK_PIPELINE_BIND_POINT_GRAPHICS,
                this.pipeline.pipelineLayout(),
                1,
                stack.longs(descriptorSet),
                null
            );
        }
    }

    @Inject(method = "drawIndexed", at = @At("HEAD"), cancellable = true)
    private void meshfish$drawMeshIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount, CallbackInfo ci) {
        if (this.pipeline == null || !MeshfishPipelineRegistry.isMeshfishPipeline(this.pipeline)) {
            return;
        }

        if (!this.pipeline.isValid()) {
            throw new IllegalStateException("Pipeline is missing or not valid");
        }

        if (!this.pipeline.layout().entries().isEmpty()) {
            this.pushDescriptors();
        }
        EXTMeshShader.vkCmdDrawMeshTasksEXT(this.secondaryCommandBuffer(), Math.max(1, indexCount), Math.max(1, instanceCount), 1);
        ci.cancel();
    }

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void meshfish$drawMesh(int firstVertex, int vertexCount, CallbackInfo ci) {
        if (this.pipeline == null || !MeshfishPipelineRegistry.isMeshfishPipeline(this.pipeline)) {
            return;
        }

        if (!this.pipeline.isValid()) {
            return;
        }

        if (!this.pipeline.layout().entries().isEmpty()) {
            this.pushDescriptors();
        }
        EXTMeshShader.vkCmdDrawMeshTasksEXT(this.secondaryCommandBuffer(), Math.max(1, vertexCount), 1, 1);
        ci.cancel();
    }
}
