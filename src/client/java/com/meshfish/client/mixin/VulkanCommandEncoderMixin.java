package com.meshfish.client.mixin;

import com.meshfish.client.render.MeshfishBuffers;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanCommandEncoder.class)
public class VulkanCommandEncoderMixin {

   

    // Buffer initialization is handled lazily after the Vulkan device is created

    
    @Inject(method = "destroy", at = @At("HEAD"))
    private void onDestroy(CallbackInfo ci) {
        MeshfishBuffers.destroy();
    }

    @Inject(
        method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;Lcom/mojang/blaze3d/platform/NativeImage$Format;IIIIII)V",
        at = @At("HEAD")
    )
    private void onWriteToTextureHead(GpuTexture destination, java.nio.ByteBuffer source, com.mojang.blaze3d.platform.NativeImage.Format format, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height, CallbackInfo ci) {
        if (MeshfishBuffers.DEBUG) System.out.println("[Meshfish] writeToTexture called: " + width + "x" + height);
    }

    @Inject(
        method = "copyTextureToBuffer(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
        at = @At("HEAD")
    )
    private void onCopyTextureToBufferHead(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int srcX, int srcY, int width, int height, CallbackInfo ci) {
        if (MeshfishBuffers.DEBUG) System.out.println("[Meshfish] copyTextureToBuffer: " + width + "x" + height + " -> offset=" + offset);
    }

    @Inject(
        method = "mapBuffer",
        at = @At("RETURN")
    )
    private void onMapBufferReturn(GpuBufferSlice bufferSlice, boolean read, boolean write, CallbackInfoReturnable<com.mojang.blaze3d.buffers.GpuBuffer.MappedView> cir) {
        if (MeshfishBuffers.DEBUG) System.out.println("[Meshfish] mapBuffer returned (read=" + read + ", write=" + write + ")");
    }

    
}