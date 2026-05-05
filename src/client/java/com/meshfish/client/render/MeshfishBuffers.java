package com.meshfish.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Objects;

public class MeshfishBuffers {
    // Triple buffering for frame resources
    public static final int FRAME_COUNT = 3;
    private static final int MAX_DRAW_DESCRIPTOR_SETS = 2048;
    public static MeshfishVmaBuffer[] meshStorageBuffers = new MeshfishVmaBuffer[FRAME_COUNT];
    public static VulkanGpuBuffer[] cameraUniformBuffers = new VulkanGpuBuffer[FRAME_COUNT];
    public static MeshfishVmaBuffer[] indirectBuffers = new MeshfishVmaBuffer[FRAME_COUNT];
    public static MeshfishVmaBuffer[] taskShaderPayloadBuffers = new MeshfishVmaBuffer[FRAME_COUNT];
    public static long descriptorSetLayout = 0L;
    public static long descriptorPool = 0L;
    public static long[] descriptorSets = new long[FRAME_COUNT];
    private static final long[][] drawDescriptorSets = new long[FRAME_COUNT][MAX_DRAW_DESCRIPTOR_SETS];
    private static volatile long sceneTextureView;
    private static volatile long sceneSampler;
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
    private static final AtomicLong[] drawCommandCursors = new AtomicLong[FRAME_COUNT];
    private static final AtomicInteger[] drawDescriptorCursors = new AtomicInteger[FRAME_COUNT];

    // Debug logging toggle — keep false in production/hotpath
    public static volatile boolean DEBUG = false;

    private static final int DRAW_COMMAND_SIZE = 52;
    public static final int SCENE_BUFFER_SIZE = 1024 * 1024 * 16;
    public static final int MAX_SCENE_BLOCKS = 5400;
    public static final int MAX_SCENE_MODELS = 4096;
    public static final int MAX_MODEL_QUADS = 64;
    public static final int SCENE_HEADER_SIZE = 16;
    public static final int SCENE_BLOCK_RECORD_SIZE = 32;
    public static final int SCENE_MODEL_RECORD_SIZE = 8;
    public static final int SCENE_QUAD_RECORD_SIZE = 96;
    public static final int SCENE_BLOCKS_OFFSET = SCENE_HEADER_SIZE;
    public static final int SCENE_MODELS_OFFSET = SCENE_BLOCKS_OFFSET + MAX_SCENE_BLOCKS * SCENE_BLOCK_RECORD_SIZE;
    public static final int SCENE_QUADS_OFFSET = SCENE_MODELS_OFFSET + MAX_SCENE_MODELS * SCENE_MODEL_RECORD_SIZE;
    public static final int DRAW_FLAG_INDEXED = 1;
    public static final int DRAW_FLAG_SCENE_BLOCKS = 2;
    private static volatile int sceneBlockCount;
    private static volatile int sceneModelCount;
    private static volatile int sceneQuadCount;

    static {
        for (int i = 0; i < FRAME_COUNT; i++) {
            indirectCursors[i] = new AtomicLong(0L);
            drawCommandCursors[i] = new AtomicLong(0L);
            drawDescriptorCursors[i] = new AtomicInteger(0);
        }
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
        drawCommandCursors[getFrameIndex()].set(0L);
        drawDescriptorCursors[getFrameIndex()].set(0);
    }

    // Allocate region in current indirect buffer; returns offset
    public static long allocateIndirectRegion(long size) {
        int idx = getFrameIndex();
        return indirectCursors[idx].getAndAdd(size);
    }

    // Accessors for current frame buffers
    public static MeshfishVmaBuffer getCurrentMeshStorageBuffer() {
        return meshStorageBuffers[getFrameIndex()];
    }

    public static VulkanGpuBuffer getCurrentCameraUniformBuffer() {
        return cameraUniformBuffers[getFrameIndex()];
    }

    public static MeshfishVmaBuffer getCurrentIndirectBuffer() {
        return indirectBuffers[getFrameIndex()];
    }

    public static MeshfishVmaBuffer getCurrentTaskPayloadBuffer() {
        return taskShaderPayloadBuffers[getFrameIndex()];
    }

    public static int getSceneBlockCount() {
        return sceneBlockCount;
    }

    public static int getSceneModelCount() {
        return sceneModelCount;
    }

    public static int getSceneQuadCount() {
        return sceneQuadCount;
    }

    public static int getScenePotentialQuadCount() {
        return sceneBlockCount * MAX_MODEL_QUADS;
    }

    public static int getSceneVertexCount() {
        return getScenePotentialQuadCount() * 3;
    }

    public static void uploadScene(ByteBuffer sceneData, int blockCount, int modelCount, int quadCount) {
        if (sceneData == null || blockCount <= 0 || sceneData.remaining() <= 0) {
            sceneBlockCount = 0;
            sceneModelCount = 0;
            sceneQuadCount = 0;
            return;
        }

        ByteBuffer source = sceneData.slice();
        int uploadedByteCount = 0;
        for (MeshfishVmaBuffer buffer : meshStorageBuffers) {
            if (buffer == null) {
                continue;
            }

            int byteCount = Math.min(source.remaining(), (int) Math.min(buffer.size(), Integer.MAX_VALUE));
            if (byteCount <= 0) {
                continue;
            }

            try (MeshfishVmaBuffer.MappedView mapped = buffer.map(0L, byteCount)) {
                ByteBuffer dst = mapped.data();
                ByteBuffer src = source.duplicate();
                src.limit(byteCount);
                dst.put(src);
            }

            uploadedByteCount = Math.max(uploadedByteCount, byteCount);
        }

        if (uploadedByteCount > 0) {
            sceneBlockCount = Math.min(blockCount, MAX_SCENE_BLOCKS);
            sceneModelCount = Math.min(modelCount, MAX_SCENE_MODELS);
            sceneQuadCount = Math.max(0, quadCount);
        } else {
            sceneBlockCount = 0;
            sceneModelCount = 0;
            sceneQuadCount = 0;
        }
    }

    public static void bindSceneTexture(GpuTextureView textureView, GpuSampler sampler) {
        if (!(textureView instanceof VulkanGpuTextureView vulkanTextureView)
            || !(sampler instanceof VulkanGpuSampler vulkanSampler)
            || vkDeviceRef == null) {
            return;
        }

        sceneTextureView = vulkanTextureView.vkImageView();
        sceneSampler = vulkanSampler.vkSampler();
        for (long descriptorSet : descriptorSets) {
            updateSceneTextureDescriptor(descriptorSet);
        }
    }

    public static long writeDrawCommand(
        int baseVertex,
        int firstIndex,
        int indexCount,
        int instanceCount,
        int firstVertex,
        int vertexCount,
        VertexFormat.IndexType indexType,
        int flags,
        int vertexStride,
        int positionOffset,
        int colorOffset,
        int uvOffset,
        int transformMode
    ) {
        MeshfishVmaBuffer buffer = getCurrentTaskPayloadBuffer();
        if (buffer == null) {
            return 0L;
        }

        int frame = getFrameIndex();
        long commandOffset = drawCommandCursors[frame].getAndAdd(DRAW_COMMAND_SIZE);
        if (commandOffset + DRAW_COMMAND_SIZE > buffer.size()) {
            commandOffset = 0L;
            drawCommandCursors[frame].set(DRAW_COMMAND_SIZE);
        }

        try (MeshfishVmaBuffer.MappedView mapped = buffer.map(commandOffset, DRAW_COMMAND_SIZE)) {
            ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
            data.putInt(baseVertex);
            data.putInt(firstIndex);
            data.putInt(Math.max(0, indexCount));
            data.putInt(Math.max(1, instanceCount));
            data.putInt(Math.max(0, firstVertex));
            data.putInt(Math.max(0, vertexCount));
            data.putInt(indexType == null ? 0 : indexType.ordinal() + 1);
            data.putInt(flags);
            data.putInt(vertexStride);
            data.putInt(positionOffset);
            data.putInt(colorOffset);
            data.putInt(uvOffset);
            data.putInt(transformMode);
        }
        return commandOffset;
    }

    public static long prepareDrawDescriptorSet(VulkanGpuBuffer vertexBuffer, VulkanGpuBuffer indexBuffer) {
        return prepareDrawDescriptorSet(vertexBuffer, indexBuffer, 0L);
    }

    public static long prepareDrawDescriptorSet(VulkanGpuBuffer vertexBuffer, VulkanGpuBuffer indexBuffer, long drawCommandOffset) {
        int frame = getFrameIndex();
        int cursor = drawDescriptorCursors[frame].getAndIncrement();
        if (cursor >= MAX_DRAW_DESCRIPTOR_SETS) {
            cursor = cursor % MAX_DRAW_DESCRIPTOR_SETS;
        }

        long descriptorSet = drawDescriptorSets[frame][cursor];
        if (descriptorSet == 0L || vkDeviceRef == null) {
            return descriptorSets[frame];
        }

        MeshfishVmaBuffer meshBuf = meshStorageBuffers[frame];
        VulkanGpuBuffer uniBuf = cameraUniformBuffers[frame];
        MeshfishVmaBuffer taskBuf = taskShaderPayloadBuffers[frame];
        long vertexVkBuffer = vertexBuffer == null ? meshBuf.vkBuffer() : vertexBuffer.vkBuffer();
        long indexVkBuffer = indexBuffer == null ? meshBuf.vkBuffer() : indexBuffer.vkBuffer();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer meshInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(meshBuf.vkBuffer()).offset(0).range(VK10.VK_WHOLE_SIZE);
            VkDescriptorBufferInfo.Buffer uniInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(uniBuf.vkBuffer()).offset(0).range(256);
            VkDescriptorBufferInfo.Buffer taskInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(taskBuf.vkBuffer()).offset(drawCommandOffset).range(DRAW_COMMAND_SIZE);
            VkDescriptorBufferInfo.Buffer vertexInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(vertexVkBuffer).offset(0).range(VK10.VK_WHOLE_SIZE);
            VkDescriptorBufferInfo.Buffer indexInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(indexVkBuffer).offset(0).range(VK10.VK_WHOLE_SIZE);

            boolean hasSceneTexture = sceneTextureView != 0L && sceneSampler != 0L;
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(hasSceneTexture ? 6 : 5, stack);
            writes.get(0).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet).dstBinding(0).dstArrayElement(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(meshInfo).descriptorCount(1);
            writes.get(1).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet).dstBinding(1).dstArrayElement(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(uniInfo).descriptorCount(1);
            writes.get(2).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet).dstBinding(2).dstArrayElement(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(taskInfo).descriptorCount(1);
            writes.get(3).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet).dstBinding(3).dstArrayElement(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(vertexInfo).descriptorCount(1);
            writes.get(4).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet).dstBinding(4).dstArrayElement(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(indexInfo).descriptorCount(1);
            if (hasSceneTexture) {
                VkDescriptorImageInfo.Buffer sceneTextureInfo = sceneTextureInfo(stack);
                writes.get(5).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet).dstBinding(5).dstArrayElement(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(sceneTextureInfo).descriptorCount(1);
            }
            VK10.vkUpdateDescriptorSets(Objects.requireNonNull(vkDeviceRef, "vkDeviceRef is null"), writes, null);
        }

        return descriptorSet;
    }

    private static void updateSceneTextureDescriptor(long descriptorSet) {
        if (descriptorSet == 0L || sceneTextureView == 0L || sceneSampler == 0L || vkDeviceRef == null) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = sceneTextureInfo(stack);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descriptorSet)
                .dstBinding(5)
                .dstArrayElement(0)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .pImageInfo(imageInfo)
                .descriptorCount(1);
            VK10.vkUpdateDescriptorSets(Objects.requireNonNull(vkDeviceRef, "vkDeviceRef is null"), write, null);
        }
    }

    private static VkDescriptorImageInfo.Buffer sceneTextureInfo(MemoryStack stack) {
        return VkDescriptorImageInfo.calloc(1, stack)
            .sampler(sceneSampler)
            .imageView(sceneTextureView)
            .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
    }

    public static void bindDescriptorSet(VkCommandBuffer commandBuffer, long pipelineLayout, long descriptorSet) {
        if (descriptorSet == 0L) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindDescriptorSets(
                commandBuffer,
                VK12.VK_PIPELINE_BIND_POINT_GRAPHICS,
                pipelineLayout,
                1,
                stack.longs(descriptorSet),
                null
            );
        }
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
                        meshStorageBuffers[idx] = new MeshfishVmaBuffer(
                        device,
                        () -> "Meshfish-MeshStorageBuffer-" + idx,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        SCENE_BUFFER_SIZE,
                        true
                    );
                    if (DEBUG) System.out.println("[Meshfish] Created mesh storage buffer #" + idx);
                }

                if (cameraUniformBuffers[idx] == null) {
                    cameraUniformBuffers[idx] = (VulkanGpuBuffer) device.createBuffer(
                            () -> "Meshfish-CameraUniformBuffer-" + idx,
                            GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                            256
                    );
                    if (DEBUG) System.out.println("[Meshfish] Created camera uniform buffer #" + idx);
                }

                if (indirectBuffers[idx] == null) {
                    indirectBuffers[idx] = new MeshfishVmaBuffer(
                        device,
                        () -> "Meshfish-IndirectBuffer-" + idx,
                        VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        1024 * 64,
                        true
                    );
                    if (DEBUG) System.out.println("[Meshfish] Created indirect buffer #" + idx);
                }

                if (taskShaderPayloadBuffers[idx] == null) {
                    taskShaderPayloadBuffers[idx] = new MeshfishVmaBuffer(
                        device,
                        () -> "Meshfish-TaskPayloadBuffer-" + idx,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        1024 * 1024,
                        true
                    );
                    if (DEBUG) System.out.println("[Meshfish] Created task shader payload buffer #" + idx);
                }
            }

            if (descriptorSetLayout == 0L || descriptorPool == 0L) {
                // Create descriptor layout, pool and allocate per-frame descriptor sets
                try {
                    vkDeviceRef = Objects.requireNonNull(device.vkDevice(), "device.vkDevice() returned null");
                    try (MemoryStack s2 = MemoryStack.stackPush()) {
                        // Layout bindings: 0 = scene storage, 1 = camera uniform, 2 = task payload,
                        // 3/4 = current vanilla vertex/index buffers, 5 = block atlas texture.
                        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(6, s2);
                        int stageFlags = VK10.VK_SHADER_STAGE_VERTEX_BIT
                                | VK10.VK_SHADER_STAGE_FRAGMENT_BIT
                                | VK10.VK_SHADER_STAGE_COMPUTE_BIT
                                | EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT
                                | EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
                        bindings.put(VkDescriptorSetLayoutBinding.calloc(s2).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(stageFlags));
                        bindings.put(VkDescriptorSetLayoutBinding.calloc(s2).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(stageFlags));
                        bindings.put(VkDescriptorSetLayoutBinding.calloc(s2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(stageFlags));
                        bindings.put(VkDescriptorSetLayoutBinding.calloc(s2).binding(3).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(stageFlags));
                        bindings.put(VkDescriptorSetLayoutBinding.calloc(s2).binding(4).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(stageFlags));
                        bindings.put(VkDescriptorSetLayoutBinding.calloc(s2).binding(5).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(stageFlags));
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

                        int descriptorSetCount = FRAME_COUNT * (MAX_DRAW_DESCRIPTOR_SETS + 1);
                        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, s2);
                        poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(descriptorSetCount * 4);
                        poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(descriptorSetCount);
                        poolSizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(descriptorSetCount);

                        VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(s2).sType$Default().pPoolSizes(poolSizes).maxSets(descriptorSetCount);
                        LongBuffer pPool = s2.mallocLong(1);
                        err = VK12.vkCreateDescriptorPool(Objects.requireNonNull(vkDeviceRef, "vkDeviceRef is null"), poolInfo, null, pPool);
                        if (err != VK10.VK_SUCCESS) {
                            System.err.println("[Meshfish] Failed to create descriptor pool: " + err);
                        } else {
                            descriptorPool = pPool.get(0);
                            if (DEBUG) System.out.println("[Meshfish] Created descriptor pool: " + descriptorPool);
                        }

                        // Allocate sets
                        LongBuffer layouts = MemoryUtil.memAllocLong(descriptorSetCount);
                        LongBuffer pDescriptorSets = MemoryUtil.memAllocLong(descriptorSetCount);
                        try {
                            for (int j = 0; j < descriptorSetCount; j++) layouts.put(j, descriptorSetLayout);

                            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(s2).sType$Default().descriptorPool(descriptorPool).pSetLayouts(layouts);
                            err = VK12.vkAllocateDescriptorSets(Objects.requireNonNull(vkDeviceRef, "vkDeviceRef is null"), allocInfo, pDescriptorSets);
                            if (err != VK10.VK_SUCCESS) {
                                System.err.println("[Meshfish] Failed to allocate descriptor sets: " + err);
                            } else {
                                for (int j = 0; j < FRAME_COUNT; j++) {
                                    descriptorSets[j] = pDescriptorSets.get(j);
                                }
                                for (int frame = 0; frame < FRAME_COUNT; frame++) {
                                    for (int draw = 0; draw < MAX_DRAW_DESCRIPTOR_SETS; draw++) {
                                        drawDescriptorSets[frame][draw] = pDescriptorSets.get(FRAME_COUNT + frame * MAX_DRAW_DESCRIPTOR_SETS + draw);
                                    }
                                }
                                if (DEBUG) System.out.println("[Meshfish] Allocated descriptor sets");
                            }
                        } finally {
                            MemoryUtil.memFree(layouts);
                            MemoryUtil.memFree(pDescriptorSets);
                        }

                        // Update descriptor sets with buffer bindings
                        for (int j = 0; j < FRAME_COUNT; j++) {
                            MeshfishVmaBuffer meshBuf = meshStorageBuffers[j];
                            VulkanGpuBuffer uniBuf = cameraUniformBuffers[j];
                            MeshfishVmaBuffer taskBuf = taskShaderPayloadBuffers[j];

                            VkDescriptorBufferInfo.Buffer meshInfo = VkDescriptorBufferInfo.calloc(1, s2).buffer(meshBuf.vkBuffer()).offset(0).range(VK10.VK_WHOLE_SIZE);
                            VkDescriptorBufferInfo.Buffer uniInfo = VkDescriptorBufferInfo.calloc(1, s2).buffer(uniBuf.vkBuffer()).offset(0).range(256);
                            VkDescriptorBufferInfo.Buffer taskInfo = VkDescriptorBufferInfo.calloc(1, s2).buffer(taskBuf.vkBuffer()).offset(0).range(VK10.VK_WHOLE_SIZE);
                            VkDescriptorBufferInfo.Buffer vertexInfo = VkDescriptorBufferInfo.calloc(1, s2).buffer(meshBuf.vkBuffer()).offset(0).range(VK10.VK_WHOLE_SIZE);
                            VkDescriptorBufferInfo.Buffer indexInfo = VkDescriptorBufferInfo.calloc(1, s2).buffer(meshBuf.vkBuffer()).offset(0).range(VK10.VK_WHOLE_SIZE);

                            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, s2);
                            writes.get(0).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSets[j]).dstBinding(0).dstArrayElement(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(meshInfo).descriptorCount(1);
                            writes.get(1).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSets[j]).dstBinding(1).dstArrayElement(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(uniInfo).descriptorCount(1);
                            writes.get(2).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSets[j]).dstBinding(2).dstArrayElement(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(taskInfo).descriptorCount(1);
                            writes.get(3).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSets[j]).dstBinding(3).dstArrayElement(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(vertexInfo).descriptorCount(1);
                            writes.get(4).sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSets[j]).dstBinding(4).dstArrayElement(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(indexInfo).descriptorCount(1);

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
                drawCommandCursors[i].set(0L);
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
                    sceneTextureView = 0L;
                    sceneSampler = 0L;
                    sceneBlockCount = 0;
                    sceneModelCount = 0;
                    sceneQuadCount = 0;
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
