package com.meshfish.client.mixin;

import com.meshfish.Meshfish;
import com.meshfish.client.render.MeshfishPipelineRegistry;
import com.meshfish.client.render.MeshfishBuffers;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import net.minecraft.resources.Identifier;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanRenderPass.class)
public abstract class VulkanRenderPassMixin {
    @Unique
    private static boolean meshfish$loggedSceneDraw;

    @Unique
    private VulkanGpuBuffer meshfish$vertexBuffer;

    @Unique
    private VulkanGpuBuffer meshfish$indexBuffer;

    @Unique
    private VertexFormat.IndexType meshfish$indexType;

    @Unique
    private VertexFormat meshfish$vertexFormat;

    @Unique
    private Identifier meshfish$pipelineLocation;

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

    @Inject(method = "setVertexBuffer", at = @At("TAIL"))
    private void meshfish$rememberVertexBuffer(int slot, GpuBuffer vertexBuffer, CallbackInfo ci) {
        if (slot == 0 && vertexBuffer instanceof VulkanGpuBuffer vulkanGpuBuffer) {
            this.meshfish$vertexBuffer = vulkanGpuBuffer;
        }
    }

    @Inject(method = "setIndexBuffer", at = @At("TAIL"))
    private void meshfish$rememberIndexBuffer(GpuBuffer indexBuffer, VertexFormat.IndexType indexType, CallbackInfo ci) {
        if (indexBuffer instanceof VulkanGpuBuffer vulkanGpuBuffer) {
            this.meshfish$indexBuffer = vulkanGpuBuffer;
            this.meshfish$indexType = indexType;
        }
    }

    @Inject(method = "setPipeline", at = @At("TAIL"))
    private void meshfish$bindMeshDescriptorSet(RenderPipeline pipelineInfo, CallbackInfo ci) {
        this.meshfish$vertexFormat = pipelineInfo.getVertexFormat();
        this.meshfish$pipelineLocation = pipelineInfo.getLocation();

        if (this.pipeline == null || !MeshfishPipelineRegistry.isMeshfishPipeline(this.pipeline)) {
            return;
        }
        if (meshfish$isScenePipeline()) {
            return;
        }

        MeshfishBuffers.bindDescriptorSet(
            this.secondaryCommandBuffer(),
            this.pipeline.pipelineLayout(),
            MeshfishBuffers.descriptorSets[MeshfishBuffers.getFrameIndex()]
        );
    }

    @Inject(method = "drawIndexed", at = @At("HEAD"), cancellable = true)
    private void meshfish$drawMeshIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount, CallbackInfo ci) {
        if (this.pipeline == null || !MeshfishPipelineRegistry.isMeshfishPipeline(this.pipeline)) {
            return;
        }

        if (!this.pipeline.isValid()) {
            throw new IllegalStateException("Pipeline is missing or not valid");
        }

        VertexFormat format = this.meshfish$vertexFormat;
        if (!meshfish$canRenderFormat(format) || this.meshfish$vertexBuffer == null) {
            return;
        }

        MeshfishBuffers.writeDrawCommand(
            baseVertex,
            firstIndex,
            indexCount,
            instanceCount,
            0,
            0,
            this.meshfish$indexType,
            this.meshfish$indexBuffer == null ? 0 : 1,
            format.getVertexSize(),
            format.getOffset(VertexFormatElement.POSITION),
            format.getOffset(VertexFormatElement.COLOR),
            meshfish$elementOffset(format, VertexFormatElement.UV0),
            meshfish$transformMode(format)
        );
        meshfish$pushTextureDescriptors();
        MeshfishBuffers.bindDescriptorSet(
            this.secondaryCommandBuffer(),
            this.pipeline.pipelineLayout(),
            MeshfishBuffers.prepareDrawDescriptorSet(this.meshfish$vertexBuffer, this.meshfish$indexBuffer)
        );
        EXTMeshShader.vkCmdDrawMeshTasksEXT(this.secondaryCommandBuffer(), meshTaskCount(indexCount), Math.max(1, instanceCount), 1);
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

        VertexFormat format = this.meshfish$vertexFormat;
        if (meshfish$isScenePipeline()) {
            if (!meshfish$loggedSceneDraw) {
                meshfish$loggedSceneDraw = true;
                Meshfish.LOGGER.info(
                    "Meshfish scene draw intercepted: firstVertex={}, vertexCount={}, taskGroups={}, pipeline={}",
                    firstVertex,
                    vertexCount,
                    meshTaskCount(vertexCount),
                    this.meshfish$pipelineLocation
                );
            }
            MeshfishBuffers.writeDrawCommand(
                0,
                0,
                0,
                1,
                firstVertex,
                vertexCount,
                null,
                MeshfishBuffers.DRAW_FLAG_SCENE_BLOCKS,
                0,
                0,
                -1,
                -1,
                2
            );
            meshfish$pushTextureDescriptors();
            MeshfishBuffers.bindDescriptorSet(
                this.secondaryCommandBuffer(),
                this.pipeline.pipelineLayout(),
                MeshfishBuffers.descriptorSets[MeshfishBuffers.getFrameIndex()]
            );
            EXTMeshShader.vkCmdDrawMeshTasksEXT(this.secondaryCommandBuffer(), meshTaskCount(vertexCount), 1, 1);
            ci.cancel();
            return;
        }

        if (!meshfish$canRenderFormat(format) || this.meshfish$vertexBuffer == null) {
            return;
        }

        MeshfishBuffers.writeDrawCommand(
            0,
            0,
            0,
            1,
            firstVertex,
            vertexCount,
            null,
            0,
            format.getVertexSize(),
            format.getOffset(VertexFormatElement.POSITION),
            format.getOffset(VertexFormatElement.COLOR),
            meshfish$elementOffset(format, VertexFormatElement.UV0),
            meshfish$transformMode(format)
        );
        meshfish$pushTextureDescriptors();
        MeshfishBuffers.bindDescriptorSet(
            this.secondaryCommandBuffer(),
            this.pipeline.pipelineLayout(),
            MeshfishBuffers.prepareDrawDescriptorSet(this.meshfish$vertexBuffer, null)
        );
        EXTMeshShader.vkCmdDrawMeshTasksEXT(this.secondaryCommandBuffer(), meshTaskCount(vertexCount), 1, 1);
        ci.cancel();
    }

    private static int meshTaskCount(int vertexOrIndexCount) {
        return Math.max(1, (vertexOrIndexCount + 2) / 3);
    }

    @Unique
    private static boolean meshfish$canRenderFormat(VertexFormat format) {
        return format != null
            && format.contains(VertexFormatElement.POSITION)
            && format.getOffset(VertexFormatElement.POSITION) != VertexFormat.UNKNOWN_ELEMENT
            && format.getVertexSize() > 0;
    }

    @Unique
    private static int meshfish$transformMode(VertexFormat format) {
        return format.contains(VertexFormatElement.UV2) ? 1 : 0;
    }

    @Unique
    private static int meshfish$elementOffset(VertexFormat format, VertexFormatElement element) {
        return format.contains(element) ? format.getOffset(element) : VertexFormat.UNKNOWN_ELEMENT;
    }

    @Unique
    private void meshfish$pushTextureDescriptors() {
        if (!this.pipeline.layout().entries().isEmpty()) {
            this.pushDescriptors();
        }
    }

    @Unique
    private boolean meshfish$isScenePipeline() {
        return this.meshfish$pipelineLocation != null
            && "meshfish".equals(this.meshfish$pipelineLocation.getNamespace())
            && "scene_mesh".equals(this.meshfish$pipelineLocation.getPath());
    }
}
