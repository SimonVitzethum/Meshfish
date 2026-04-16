package com.meshfish.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import java.nio.LongBuffer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Objects;

public class MeshfishBuffers {
    // Triple buffering for frame resources
    public static final int FRAME_COUNT = 3;
    public static VulkanGpuBuffer[] meshStorageBuffers = new VulkanGpuBuffer[FRAME_COUNT];
    public static VulkanGpuBuffer[] cameraUniformBuffers = new VulkanGpuBuffer[FRAME_COUNT];
    public static VulkanGpuBuffer[] indirectBuffers = new VulkanGpuBuffer[FRAME_COUNT];
    public static VulkanGpuBuffer[] taskShaderPayloadBuffers = new VulkanGpuBuffer[FRAME_COUNT];
    public static long descriptorSetLayout = 0L;
    public static long descriptorPool = 0L;
    public static long[] descriptorSets = new long[FRAME_COUNT];
    // cached VkDevice reference used for destroying Vulkan objects
    private static VkDevice vkDeviceRef = null;

    // Guard to avoid intercepting the internal buffer creations performed by initialize()
    public static volatile boolean initializing = false;
    // Flag set when the Vulkan device has been created (set by VulkanBackendMixin)
    public static volatile boolean backendDeviceCreated = false;

    // Thread ownership: the thread that created/owns GPU resources (render thread)
    public static volatile Thread ownerThread = null;

    // Current frame index (0..FRAME_COUNT-1)
    private static final AtomicInteger currentFrame = new AtomicInteger(0);

    // Simple per-frame indirect allocator cursor (for many small indirect draws)
    private static final AtomicLong[] indirectCursors = new AtomicLong[FRAME_COUNT];

    // Debug logging toggle — keep false in production/hotpath
    public static volatile boolean DEBUG = false;

    static {
        for (int i = 0; i < FRAME_COUNT; i++) indirectCursors[i] = new AtomicLong(0L);
    }

    // Returns current frame index
    public static int getFrameIndex() {
        return currentFrame.get();
    }

    // Advance to next frame (should be called from render thread once per frame)
    public static void nextFrame() {
        currentFrame.set((currentFrame.get() + 1) % FRAME_COUNT);
        // reset cursor for new frame
        indirectCursors[getFrameIndex()].set(0L);
    }

    // Allocate region in current indirect buffer; returns offset
    public static long allocateIndirectRegion(long size) {
        int idx = getFrameIndex();
        return indirectCursors[idx].getAndAdd(size);
    }

    // Accessors for current frame buffers
    public static VulkanGpuBuffer getCurrentMeshStorageBuffer() {
        return meshStorageBuffers[getFrameIndex()];
    }

    public static VulkanGpuBuffer getCurrentCameraUniformBuffer() {
        return cameraUniformBuffers[getFrameIndex()];
    }

    public static VulkanGpuBuffer getCurrentIndirectBuffer() {
        return indirectBuffers[getFrameIndex()];
    }

    public static VulkanGpuBuffer getCurrentTaskPayloadBuffer() {
        return taskShaderPayloadBuffers[getFrameIndex()];
    }

    // Synchronized initialize creating per-frame buffers
    public static synchronized void initialize(VulkanDevice device) {
        if (device == null) return;
        // set owner thread once
        if (ownerThread == null) ownerThread = Thread.currentThread();

        // avoid re-init
        boolean already = true;
        for (int i = 0; i < FRAME_COUNT; i++) {
            if (meshStorageBuffers[i] == null || cameraUniformBuffers[i] == null || indirectBuffers[i] == null || taskShaderPayloadBuffers[i] == null) {
                already = false; break;
            }
        }
        if (already) return;

        initializing = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < FRAME_COUNT; i++) {
                final int idx = i;
                if (meshStorageBuffers[idx] == null) {
                    meshStorageBuffers[idx] = (VulkanGpuBuffer) device.createBuffer(
                            () -> "Meshfish-MeshStorageBuffer-" + idx,
                            VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                            1024 * 1024 * 16
                    );
                    if (DEBUG) System.out.println("[Meshfish] Created mesh storage buffer #" + idx);
                }

                if (cameraUniformBuffers[idx] == null) {
                    cameraUniformBuffers[idx] = (VulkanGpuBuffer) device.createBuffer(
                            () -> "Meshfish-CameraUniformBuffer-" + idx,
                            VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                            256
                    );
                    if (DEBUG) System.out.println("[Meshfish] Created camera uniform buffer #" + idx);
                }

                if (indirectBuffers[idx] == null) {
                    indirectBuffers[idx] = (VulkanGpuBuffer) device.createBuffer(
                            () -> "Meshfish-IndirectBuffer-" + idx,
                            VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                            1024 * 64
                    );
                    if (DEBUG) System.out.println("[Meshfish] Created indirect buffer #" + idx);
                }

                if (taskShaderPayloadBuffers[idx] == null) {
                    taskShaderPayloadBuffers[idx] = (VulkanGpuBuffer) device.createBuffer(
                            () -> "Meshfish-TaskPayloadBuffer-" + idx,
                            VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                            1024 * 1024
                    );
                    if (DEBUG) System.out.println("[Meshfish] Created task shader payload buffer #" + idx);
                }
            }

            if (descriptorSetLayout == 0L || descriptorPool == 0L) {
                // Create descriptor layout, pool and allocate per-frame descriptor sets
                try {
                    vkDeviceRef = Objects.requireNonNull(device.vkDevice(), "device.vkDevice() returned null");
                    try (MemoryStack s2 = MemoryStack.stackPush()) {
                        // Layout bindings: 0 = mesh storage, 1 = camera uniform, 2 = task payload storage
                        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, s2);
                        int stageFlags = VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT | VK10.VK_SHADER_STAGE_COMPUTE_BIT;
                        bindings.put(VkDescriptorSetLayoutBinding.calloc(s2).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(stageFlags));
                        bindings.put(VkDescriptorSetLayoutBinding.calloc(s2).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(stageFlags));
                        bindings.put(VkDescriptorSetLayoutBinding.calloc(s2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(stageFlags));
                        bindings.flip();

                        VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(s2).sType$Default().pBindings(bindings);
                        LongBuffer pLayout = s2.mallocLong(1);
                        int err = VK12.vkCreateDescriptorSetLayout(Objects.requireNonNull(vkDeviceRef, "vkDeviceRef is null"), layoutInfo, null, pLayout);
                        if (err != VK10.VK_SUCCESS) {
                            System.err.println("[Meshfish] Failed to create descriptor set layout: " + err);
                        } else {
                            descriptorSetLayout = pLayout.get(0);
                            if (DEBUG) System.out.println("[Meshfish] Created descriptor set layout: " + descriptorSetLayout);
                        }

                        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, s2);
                        poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(FRAME_COUNT);
                        poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(FRAME_COUNT);

                        VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(s2).sType$Default().pPoolSizes(poolSizes).maxSets(FRAME_COUNT);
                        LongBuffer pPool = s2.mallocLong(1);
                        err = VK12.vkCreateDescriptorPool(Objects.requireNonNull(vkDeviceRef, "vkDeviceRef is null"), poolInfo, null, pPool);
                        if (err != VK10.VK_SUCCESS) {
                            System.err.println("[Meshfish] Failed to create descriptor pool: " + err);
                        } else {
                            descriptorPool = pPool.get(0);
                            if (DEBUG) System.out.println("[Meshfish] Created descriptor pool: " + descriptorPool);
                        }

                        // Allocate sets
                        LongBuffer layouts = s2.mallocLong(FRAME_COUNT);
                        for (int j = 0; j < FRAME_COUNT; j++) layouts.put(j, descriptorSetLayout);

                        VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(s2).sType$Default().descriptorPool(descriptorPool).pSetLayouts(layouts);
                        LongBuffer pDescriptorSets = s2.mallocLong(FRAME_COUNT);
                        err = VK12.vkAllocateDescriptorSets(Objects.requireNonNull(vkDeviceRef, "vkDeviceRef is null"), allocInfo, pDescriptorSets);
                        if (err != VK10.VK_SUCCESS) {
                            System.err.println("[Meshfish] Failed to allocate descriptor sets: " + err);
                        } else {
                            for (int j = 0; j < FRAME_COUNT; j++) descriptorSets[j] = pDescriptorSets.get(j);
                            if (DEBUG) System.out.println("[Meshfish] Allocated descriptor sets");
                        }

                        // Update descriptor sets with buffer bindings
                        for (int j = 0; j < FRAME_COUNT; j++) {
                            VulkanGpuBuffer meshBuf = meshStorageBuffers[j];
                            VulkanGpuBuffer uniBuf = cameraUniformBuffers[j];
                            VulkanGpuBuffer taskBuf = taskShaderPayloadBuffers[j];

                            VkDescriptorBufferInfo.Buffer meshInfo = VkDescriptorBufferInfo.calloc(1, s2).buffer(meshBuf.vkBuffer()).offset(0).range(VK10.VK_WHOLE_SIZE);
                            VkDescriptorBufferInfo.Buffer uniInfo = VkDescriptorBufferInfo.calloc(1, s2).buffer(uniBuf.vkBuffer()).offset(0).range(256);
                            VkDescriptorBufferInfo.Buffer taskInfo = VkDescriptorBufferInfo.calloc(1, s2).buffer(taskBuf.vkBuffer()).offset(0).range(VK10.VK_WHOLE_SIZE);

                            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, s2);
                            writes.get(0).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSets[j]).dstBinding(0).dstArrayElement(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(meshInfo).descriptorCount(1);
                            writes.get(1).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSets[j]).dstBinding(1).dstArrayElement(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(uniInfo).descriptorCount(1);
                            writes.get(2).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSets[j]).dstBinding(2).dstArrayElement(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(taskInfo).descriptorCount(1);

                            VK10.vkUpdateDescriptorSets(Objects.requireNonNull(vkDeviceRef, "vkDeviceRef is null"), writes, null);
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[Meshfish] Error creating descriptor resources: " + t.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[Meshfish] Error creating mesh shader buffers: " + e.getMessage());
        } finally {
            initializing = false;
        }
    }

    // Synchronized destroy
    public static synchronized void destroy() {
        try {
            for (int i = 0; i < FRAME_COUNT; i++) {
                if (meshStorageBuffers[i] != null) { meshStorageBuffers[i].destroy(); meshStorageBuffers[i] = null; }
                if (cameraUniformBuffers[i] != null) { cameraUniformBuffers[i].destroy(); cameraUniformBuffers[i] = null; }
                if (indirectBuffers[i] != null) { indirectBuffers[i].destroy(); indirectBuffers[i] = null; }
                if (taskShaderPayloadBuffers[i] != null) { taskShaderPayloadBuffers[i].destroy(); taskShaderPayloadBuffers[i] = null; }
                indirectCursors[i].set(0L);
            }
            // Destroy descriptor resources if present
            if (vkDeviceRef != null) {
                try {
                    if (descriptorPool != 0L) {
                        VK12.vkDestroyDescriptorPool(Objects.requireNonNull(vkDeviceRef, "vkDeviceRef is null"), descriptorPool, null);
                        descriptorPool = 0L;
                    }
                    if (descriptorSetLayout != 0L) {
                        VK12.vkDestroyDescriptorSetLayout(Objects.requireNonNull(vkDeviceRef, "vkDeviceRef is null"), descriptorSetLayout, null);
                        descriptorSetLayout = 0L;
                    }
                } catch (Throwable t) {
                    System.err.println("[Meshfish] Error destroying descriptor resources: " + t.getMessage());
                } finally {
                    for (int i = 0; i < FRAME_COUNT; i++) descriptorSets[i] = 0L;
                    vkDeviceRef = null;
                }
            }
            if (DEBUG) System.out.println("[Meshfish] Mesh shader buffers destroyed");
            ownerThread = null;
        } catch (Exception e) {
            System.err.println("[Meshfish] Error destroying mesh shader buffers: " + e.getMessage());
        }
    }
}
