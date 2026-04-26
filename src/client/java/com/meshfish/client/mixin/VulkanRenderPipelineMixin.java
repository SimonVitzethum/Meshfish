package com.meshfish.client.mixin;

import com.meshfish.client.render.MeshfishPipelineRegistry;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanRenderPipeline.class)
public class VulkanRenderPipelineMixin {

    @Inject(method = "destroy", at = @At("HEAD"))
    private void meshfish$destroyTaskShaderModule(CallbackInfo ci) {
        VulkanRenderPipeline pipeline = (VulkanRenderPipeline) (Object) this;
        long taskModule = MeshfishPipelineRegistry.removeTaskModule(pipeline);
        if (taskModule != 0L) {
            VK12.vkDestroyShaderModule(pipeline.device().vkDevice(), taskModule, null);
        }
    }
}
