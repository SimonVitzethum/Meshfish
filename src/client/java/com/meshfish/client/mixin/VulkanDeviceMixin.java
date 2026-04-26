package com.meshfish.client.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.meshfish.client.render.MeshfishBuffers;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanDevice.class)
public class VulkanDeviceMixin {

    @Inject(method = "createBuffer", at = @At("HEAD"), cancellable = true)
    private void onCreateBuffer(java.util.function.Supplier<String> nameSupplier, int usage, long size, CallbackInfoReturnable<GpuBuffer> cir) {
        try {
            // If we're currently creating the Meshfish buffers, don't intercept those calls
            if (MeshfishBuffers.initializing) return;
            VulkanDevice device = (VulkanDevice) (Object) this;

            // If the backend has signaled device creation and we haven't initialized, do it now (safe, lazy init)
            if (MeshfishBuffers.getCurrentMeshStorageBuffer() == null && MeshfishBuffers.backendDeviceCreated) {
                MeshfishBuffers.initialize(device);
                if (MeshfishBuffers.DEBUG) System.out.println("[Meshfish] Lazy-initialized MeshfishBuffers after device creation");
            }

            // Only intercept Meshfish-created buffers by name to avoid hijacking unrelated engine buffers.
            String requestedName = null;
            try {
                if (nameSupplier != null) requestedName = nameSupplier.get();
            } catch (Throwable ignored) {
                requestedName = null;
            }

            boolean isMeshfish = requestedName != null && requestedName.contains("Meshfish-");

            // Storage buffer (mesh data) — only if this request originates from Meshfish
            if (isMeshfish && (usage & VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) != 0) {
                if (MeshfishBuffers.getCurrentMeshStorageBuffer() == null) MeshfishBuffers.initialize(device);
                if (MeshfishBuffers.DEBUG) System.out.println("[Meshfish] INTERCEPT: returning mesh storage buffer for " + requestedName);
                cir.setReturnValue(MeshfishBuffers.getCurrentMeshStorageBuffer());
                cir.cancel();
                return;
            }

            // Uniform buffer (camera)
            if (isMeshfish && (usage & VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT) != 0) {
                if (MeshfishBuffers.getCurrentCameraUniformBuffer() == null) MeshfishBuffers.initialize(device);
                if (MeshfishBuffers.DEBUG) System.out.println("[Meshfish] INTERCEPT: returning camera uniform buffer for " + requestedName);
                cir.setReturnValue(MeshfishBuffers.getCurrentCameraUniformBuffer());
                cir.cancel();
                return;
            }

            // Indirect buffer
            if (isMeshfish && (usage & VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT) != 0) {
                if (MeshfishBuffers.getCurrentIndirectBuffer() == null) MeshfishBuffers.initialize(device);
                if (MeshfishBuffers.DEBUG) System.out.println("[Meshfish] INTERCEPT: returning indirect buffer for " + requestedName);
                cir.setReturnValue(MeshfishBuffers.getCurrentIndirectBuffer());
                cir.cancel();
                return;
            }

            // Task shader payloads typically use storage usage; handled above.

            // All other buffer creations: allow original method to create them (don't block).
            if (MeshfishBuffers.DEBUG) System.out.println("[Meshfish] Passing through buffer creation (usage: " + usage + ")");
            return;

        } catch (Throwable t) {
            System.err.println("[Meshfish] Error in VulkanDeviceMixin.createBuffer injection: " + t.getMessage());
        }
    }
}
