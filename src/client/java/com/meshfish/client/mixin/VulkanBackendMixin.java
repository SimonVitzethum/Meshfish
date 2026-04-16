package com.meshfish.client.mixin;

import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import java.util.Collection;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceSynchronization2Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import com.mojang.blaze3d.vulkan.VulkanInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(VulkanBackend.class)
public class VulkanBackendMixin {

    @Inject(
        method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;)Lcom/mojang/blaze3d/systems/GpuDevice;",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;hasDeviceExtension(Ljava/lang/String;)Z",
            shift = At.Shift.BEFORE
        ),
        locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void addMeshShaderExtensions(long window, ShaderSource shaderSource, GpuDebugOptions debugOptions, CallbackInfoReturnable<com.mojang.blaze3d.systems.GpuDevice> cir, Set<String> extensions, VulkanInstance instance, VulkanPhysicalDevice physicalDevice) {
        if (physicalDevice.hasDeviceExtension("VK_EXT_mesh_shader")) {
            extensions.add("VK_EXT_mesh_shader");
        }

        if (physicalDevice.hasDeviceExtension("VK_KHR_synchronization2")) {
            extensions.add("VK_KHR_synchronization2");
        }
        if (physicalDevice.hasDeviceExtension("VK_KHR_dynamic_rendering")) {
            extensions.add("VK_KHR_dynamic_rendering");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VK11.vkGetPhysicalDeviceProperties(physicalDevice.vkPhysicalDevice(), props);
            int apiVersion = props.apiVersion();

            if (apiVersion < VK12.VK_API_VERSION_1_2) {
                if (physicalDevice.hasDeviceExtension("VK_KHR_buffer_device_address")) {
                    extensions.add("VK_KHR_buffer_device_address");
                }
                if (physicalDevice.hasDeviceExtension("VK_KHR_descriptor_indexing")) {
                    extensions.add("VK_KHR_descriptor_indexing");
                }
                if (physicalDevice.hasDeviceExtension("VK_KHR_draw_indirect_count")) {
                    extensions.add("VK_KHR_draw_indirect_count");
                }
            }
        }
    }

    @Inject(
        method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;)Lcom/mojang/blaze3d/systems/GpuDevice;",
        at = @At("RETURN")
    )
    private void onCreateDeviceReturn(long window, ShaderSource shaderSource, GpuDebugOptions debugOptions, CallbackInfoReturnable<com.mojang.blaze3d.systems.GpuDevice> cir) {
        MeshfishBuffers.backendDeviceCreated = true;
        if (MeshfishBuffers.DEBUG) System.out.println("[Meshfish] VulkanBackend#createDevice returned - device ready flag set");
    }

    @Inject(
        method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkDeviceCreateInfo;pNext(J)Lorg/lwjgl/vulkan/VkDeviceCreateInfo;",
            shift = At.Shift.BEFORE
        ),
        locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void injectMeshShaderFeatures(Collection<String> extensions, VulkanPhysicalDevice physicalDevice, CallbackInfoReturnable<org.lwjgl.vulkan.VkDevice> cir, MemoryStack stack, VkPhysicalDeviceFeatures2 features2) {
        if (stack == null || features2 == null) return; // Null safety check for mixin injection

        // Query the physical device properties to get API version
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
        VK11.vkGetPhysicalDeviceProperties(physicalDevice.vkPhysicalDevice(), props);
        int apiVersion = props.apiVersion();
        boolean is13 = apiVersion >= VK13.VK_API_VERSION_1_3;

        // Query Vulkan 1.2 features separately
        VkPhysicalDeviceVulkan12Features supported12 = null;
        if (apiVersion >= VK12.VK_API_VERSION_1_2 || physicalDevice.hasDeviceExtension("VK_KHR_descriptor_indexing") ||
            physicalDevice.hasDeviceExtension("VK_KHR_buffer_device_address") ||
            physicalDevice.hasDeviceExtension("VK_KHR_draw_indirect_count")) {
            supported12 = VkPhysicalDeviceVulkan12Features.calloc(stack);
            VkPhysicalDeviceFeatures2 queryVK12 = VkPhysicalDeviceFeatures2.calloc(stack);
            queryVK12.pNext(supported12.address());
            VK11.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), queryVK12);
        }

        // Query Synchronization2 features separately
        VkPhysicalDeviceSynchronization2Features supportedSync2 = null;
        if (is13 || physicalDevice.hasDeviceExtension("VK_KHR_synchronization2")) {
            supportedSync2 = VkPhysicalDeviceSynchronization2Features.calloc(stack);
            VkPhysicalDeviceFeatures2 querySync2 = VkPhysicalDeviceFeatures2.calloc(stack);
            querySync2.pNext(supportedSync2.address());
            VK11.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), querySync2);
        }

        // Query Dynamic Rendering features separately
        VkPhysicalDeviceDynamicRenderingFeatures supportedDynamic = null;
        if (is13 || physicalDevice.hasDeviceExtension("VK_KHR_dynamic_rendering")) {
            supportedDynamic = VkPhysicalDeviceDynamicRenderingFeatures.calloc(stack);
            VkPhysicalDeviceFeatures2 queryDynamic = VkPhysicalDeviceFeatures2.calloc(stack);
            queryDynamic.pNext(supportedDynamic.address());
            VK11.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), queryDynamic);
        }

        // Query Mesh Shader EXT features separately
        VkPhysicalDeviceMeshShaderFeaturesEXT supportedMeshEXT = null;
        if (physicalDevice.hasDeviceExtension("VK_EXT_mesh_shader")) {
            supportedMeshEXT = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack);
            VkPhysicalDeviceFeatures2 queryMeshEXT = VkPhysicalDeviceFeatures2.calloc(stack);
            queryMeshEXT.pNext(supportedMeshEXT.address());
            VK11.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), queryMeshEXT);
        }

        long vk12Ptr = VulkanBackend.VK12_FEATURES_STRUCT.findOrCreateStructInPNextChain(features2, stack);
        if (vk12Ptr != 0L && supported12 != null) {
            VkPhysicalDeviceVulkan12Features vk12Features = VkPhysicalDeviceVulkan12Features.create(vk12Ptr);
            if (apiVersion >= VK12.VK_API_VERSION_1_2) {
                vk12Features
                    .descriptorIndexing(supported12.descriptorIndexing())
                    .bufferDeviceAddress(supported12.bufferDeviceAddress())
                    .drawIndirectCount(supported12.drawIndirectCount())
                    .shaderSubgroupExtendedTypes(supported12.shaderSubgroupExtendedTypes());
            } else {
                if (physicalDevice.hasDeviceExtension("VK_KHR_descriptor_indexing")) {
                    vk12Features.descriptorIndexing(supported12.descriptorIndexing());
                }
                if (physicalDevice.hasDeviceExtension("VK_KHR_buffer_device_address")) {
                    vk12Features.bufferDeviceAddress(supported12.bufferDeviceAddress());
                }
                if (physicalDevice.hasDeviceExtension("VK_KHR_draw_indirect_count")) {
                    vk12Features.drawIndirectCount(supported12.drawIndirectCount());
                }
            }
        }

        if (supportedSync2 != null && supportedSync2.synchronization2()) {
            long sync2Ptr = VulkanBackend.SYNC2_FEATURES_STRUCT.findOrCreateStructInPNextChain(features2, stack);
            if (sync2Ptr != 0L) {
                VkPhysicalDeviceSynchronization2Features.create(sync2Ptr)
                    .synchronization2(true);
            }
        }

        if (supportedDynamic != null && supportedDynamic.dynamicRendering()) {
            int dynamicRenderingSType = is13 ? VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES : KHRDynamicRendering.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR;
            long dynamicRenderingPtr = new VulkanPNextStruct(dynamicRenderingSType, VkPhysicalDeviceDynamicRenderingFeatures.SIZEOF)
                .findOrCreateStructInPNextChain(features2, stack);
            if (dynamicRenderingPtr != 0L) {
                VkPhysicalDeviceDynamicRenderingFeatures.create(dynamicRenderingPtr)
                    .dynamicRendering(true);
            }
        }

        if (supportedMeshEXT != null && supportedMeshEXT.meshShader()) {
            long meshShaderPtr = new VulkanPNextStruct(EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT, VkPhysicalDeviceMeshShaderFeaturesEXT.SIZEOF)
                .findOrCreateStructInPNextChain(features2, stack);
            if (meshShaderPtr != 0L) {
                VkPhysicalDeviceMeshShaderFeaturesEXT.create(meshShaderPtr)
                    .meshShader(true)
                    .taskShader(supportedMeshEXT.taskShader());
            }
        }
    }
}

