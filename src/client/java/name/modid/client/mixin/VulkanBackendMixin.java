package name.modid.client.mixin;

import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import java.util.Collection;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.NVMeshShader;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesNV;
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
        if (physicalDevice.hasDeviceExtension("VK_NV_mesh_shader")) {
            extensions.add("VK_NV_mesh_shader");
        }
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
        // Enable Vulkan 1.2 features
        long vk12Ptr = VulkanBackend.VK12_FEATURES_STRUCT.findOrCreateStructInPNextChain(features2, stack);
        if (vk12Ptr != 0L) {
            VkPhysicalDeviceVulkan12Features.create(vk12Ptr)
                .descriptorIndexing(true)
                .bufferDeviceAddress(true)
                .drawIndirectCount(true)
                .shaderSubgroupExtendedTypes(true);
        }

        // Enable Synchronization2
        long sync2Ptr = VulkanBackend.SYNC2_FEATURES_STRUCT.findOrCreateStructInPNextChain(features2, stack);
        if (sync2Ptr != 0L) {
            VkPhysicalDeviceSynchronization2Features.create(sync2Ptr)
                .synchronization2(true);
        }

        // Enable Dynamic Rendering
        long dynamicRenderingPtr = VulkanBackend.DYNAMIC_RENDERING_FEATURES_STURCT.findOrCreateStructInPNextChain(features2, stack);
        if (dynamicRenderingPtr != 0L) {
            VkPhysicalDeviceDynamicRenderingFeatures.create(dynamicRenderingPtr)
                .dynamicRendering(true);
        }

        // Enable Mesh Shader features
        if (physicalDevice.hasDeviceExtension("VK_EXT_mesh_shader")) {
            long meshShaderPtr = new VulkanPNextStruct(EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT, VkPhysicalDeviceMeshShaderFeaturesEXT.SIZEOF)
                .findOrCreateStructInPNextChain(features2, stack);
            if (meshShaderPtr != 0L) {
                VkPhysicalDeviceMeshShaderFeaturesEXT.create(meshShaderPtr)
                    .meshShader(true)
                    .taskShader(true);
            }
        }
        if (physicalDevice.hasDeviceExtension("VK_NV_mesh_shader")) {
            long meshShaderPtr = new VulkanPNextStruct(NVMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_NV, VkPhysicalDeviceMeshShaderFeaturesNV.SIZEOF)
                .findOrCreateStructInPNextChain(features2, stack);
            if (meshShaderPtr != 0L) {
                VkPhysicalDeviceMeshShaderFeaturesNV.create(meshShaderPtr)
                    .meshShader(true)
                    .taskShader(true);
            }
        }
    }
}
