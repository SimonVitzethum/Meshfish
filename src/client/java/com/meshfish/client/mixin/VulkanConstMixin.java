package com.meshfish.client.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanConst;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanConst.class)
public abstract class VulkanConstMixin {
    @Inject(method = "bufferUsageToVk", at = @At("RETURN"), cancellable = true)
    private static void meshfish$makeVertexAndIndexBuffersShaderReadable(int usage, CallbackInfoReturnable<Integer> cir) {
        if ((usage & (GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX)) != 0) {
            cir.setReturnValue(cir.getReturnValueI() | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        }
    }
}
