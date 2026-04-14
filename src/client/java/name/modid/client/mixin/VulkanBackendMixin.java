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
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
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
        boolean useEXT = physicalDevice.hasDeviceExtension("VK_EXT_mesh_shader");
        if (useEXT) {
            extensions.add("VK_EXT_mesh_shader");
        } else if (physicalDevice.hasDeviceExtension("VK_NV_mesh_shader")) {
            extensions.add("VK_NV_mesh_shader");
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
            }
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
        // Query the physical device properties to get API version
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
        VK11.vkGetPhysicalDeviceProperties(physicalDevice.vkPhysicalDevice(), props);
        int apiVersion = props.apiVersion();

        VkPhysicalDeviceVulkan12Features supported12 = VkPhysicalDeviceVulkan12Features.calloc(stack);
        VkPhysicalDeviceSynchronization2Features supportedSync2 = VkPhysicalDeviceSynchronization2Features.calloc(stack);
        VkPhysicalDeviceDynamicRenderingFeatures supportedDynamic = VkPhysicalDeviceDynamicRenderingFeatures.calloc(stack);
        VkPhysicalDeviceMeshShaderFeaturesEXT supportedMeshEXT = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack);
        VkPhysicalDeviceMeshShaderFeaturesNV supportedMeshNV = VkPhysicalDeviceMeshShaderFeaturesNV.calloc(stack);

        boolean useEXT = physicalDevice.hasDeviceExtension("VK_EXT_mesh_shader");

        supported12.pNext(supportedSync2.address());
        supportedSync2.pNext(supportedDynamic.address());
        supportedDynamic.pNext(useEXT ? supportedMeshEXT.address() : supportedMeshNV.address());

        VkPhysicalDeviceFeatures2 queryFeatures = VkPhysicalDeviceFeatures2.calloc(stack);
        queryFeatures.pNext(supported12.address());
        VK11.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), queryFeatures);

        long vk12Ptr = VulkanBackend.VK12_FEATURES_STRUCT.findOrCreateStructInPNextChain(features2, stack);
        if (vk12Ptr != 0L) {
            VkPhysicalDeviceVulkan12Features.create(vk12Ptr)
                .descriptorIndexing(supported12.descriptorIndexing())
                .bufferDeviceAddress(supported12.bufferDeviceAddress())
                .drawIndirectCount(supported12.drawIndirectCount())
                .shaderSubgroupExtendedTypes(supported12.shaderSubgroupExtendedTypes());
        }

        if (supportedSync2.synchronization2()) {
            long sync2Ptr = VulkanBackend.SYNC2_FEATURES_STRUCT.findOrCreateStructInPNextChain(features2, stack);
            if (sync2Ptr != 0L) {
                VkPhysicalDeviceSynchronization2Features.create(sync2Ptr)
                    .synchronization2(true);
            }
        }

        if (supportedDynamic.dynamicRendering()) {
            long dynamicRenderingPtr = new VulkanPNextStruct(KHRDynamicRendering.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR, VkPhysicalDeviceDynamicRenderingFeatures.SIZEOF)
                .findOrCreateStructInPNextChain(features2, stack);
            if (dynamicRenderingPtr != 0L) {
                VkPhysicalDeviceDynamicRenderingFeatures.create(dynamicRenderingPtr)
                    .dynamicRendering(true);
            }
        }

        if (useEXT && supportedMeshEXT.meshShader()) {
            long meshShaderPtr = new VulkanPNextStruct(EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT, VkPhysicalDeviceMeshShaderFeaturesEXT.SIZEOF)
                .findOrCreateStructInPNextChain(features2, stack);
            if (meshShaderPtr != 0L) {
                VkPhysicalDeviceMeshShaderFeaturesEXT.create(meshShaderPtr)
                    .meshShader(true)
                    .taskShader(supportedMeshEXT.taskShader());
            }
        } else if (!useEXT && supportedMeshNV.meshShader()) {
            long meshShaderPtr = new VulkanPNextStruct(NVMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_NV, VkPhysicalDeviceMeshShaderFeaturesNV.SIZEOF)
                .findOrCreateStructInPNextChain(features2, stack);
            if (meshShaderPtr != 0L) {
                VkPhysicalDeviceMeshShaderFeaturesNV.create(meshShaderPtr)
                    .meshShader(true)
                    .taskShader(supportedMeshNV.taskShader());
            }
        }
    }
}
