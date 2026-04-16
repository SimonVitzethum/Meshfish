package com.meshfish.client.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(VulkanCommandEncoder.class)
public class VulkanCommandEncoderMixin {


    @Inject(
        method = "createStagingBuffer",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanGpuBuffer;map:(JJ)Lcom/mojang/blaze3d/buffers/GpuBuffer$MappedView;",
            shift = At.Shift.BEFORE
        ),
        locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void onCreateStagingBuffer(java.nio.ByteBuffer data, CallbackInfoReturnable<VulkanGpuBuffer> cir, int size, VulkanGpuBuffer stagingBuffer) {
     
        System.out.println("[Meshfish] Staging buffer created with size: " + size);
    }

    @Inject(
        method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;Lcom/mojang/blaze3d/platform/NativeImage$Format;IIIIII)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanCommandEncoder;writeToTexture:(Lcom/mojang/blaze3d/vulkan/VulkanGpuTexture;Lcom/mojang/blaze3d/vulkan/VulkanGpuBuffer;JIIIIIII)V",
            shift = At.Shift.BEFORE
        ),
        locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void onWriteToTexture(GpuTexture destination, java.nio.ByteBuffer source, com.mojang.blaze3d.platform.NativeImage.Format format, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height, CallbackInfo ci) {
       
        System.out.println("[Meshfish] Writing to texture: " + width + "x" + height);
    }

  
    @Inject(
        method = "copyTextureToBuffer(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCmdCopyImageToBuffer:(Lorg/lwjgl/vulkan/VkCommandBuffer;JIJLorg/lwjgl/vulkan/VkBufferImageCopy$Buffer;)V",
            shift = At.Shift.BEFORE
        ),
        locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void onCopyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int srcX, int srcY, int width, int height, CallbackInfo ci) {
        System.out.println("[Meshfish] Copying texture to buffer: " + width + "x" + height);
    }


    @Inject(
        method = "mapBuffer",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanGpuBuffer;map:(JJ)Lcom/mojang/blaze3d/buffers/GpuBuffer$MappedView;",
            shift = At.Shift.AFTER
        ),
        locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void onMapBuffer(GpuBufferSlice bufferSlice, boolean read, boolean write, CallbackInfoReturnable<com.mojang.blaze3d.buffers.GpuBuffer.MappedView> cir) {
        System.out.println("[Meshfish] Buffer mapped for " + (read ? "read" : "") + (write ? "write" : ""));
    }
}