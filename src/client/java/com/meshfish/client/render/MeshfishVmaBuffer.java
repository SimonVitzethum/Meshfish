package com.meshfish.client.render;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;

public final class MeshfishVmaBuffer implements Destroyable, AutoCloseable {
    private final VulkanDevice device;
    private final long size;
    private final long vkBuffer;
    private final long vmaAllocation;
    private boolean closed;

    public MeshfishVmaBuffer(
        VulkanDevice device,
        @Nullable Supplier<String> label,
        int vkUsage,
        long size,
        boolean hostVisible
    ) {
        this.device = device;
        this.size = size;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferCreateInfo = VkBufferCreateInfo.calloc(stack)
                .sType$Default()
                .size(size)
                .usage(vkUsage)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocationCreateInfo = VmaAllocationCreateInfo.calloc(stack)
                .usage(hostVisible ? Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST : Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);

            if (hostVisible) {
                allocationCreateInfo.requiredFlags(
                    VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                );
                allocationCreateInfo.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            }

            LongBuffer bufferPointer = stack.callocLong(1);
            PointerBuffer allocationPointer = stack.callocPointer(1);
            VulkanUtils.crashIfFailure(
                Vma.vmaCreateBuffer(device.vma(), bufferCreateInfo, allocationCreateInfo, bufferPointer, allocationPointer, null),
                "Failed to allocate Meshfish VkBuffer"
            );

            this.vkBuffer = bufferPointer.get(0);
            this.vmaAllocation = allocationPointer.get(0);

            if (label != null) {
                device.instance().debug().setObjectName(device.vkDevice(), VK10.VK_OBJECT_TYPE_BUFFER, this.vkBuffer, label);
                Vma.vmaSetAllocationName(device.vma(), this.vmaAllocation, label.get());
            }
        }
    }

    public long size() {
        return this.size;
    }

    public long vkBuffer() {
        return this.vkBuffer;
    }

    public MappedView map(long offset, long length) {
        if (offset < 0L || length < 0L || offset + length > this.size) {
            throw new IllegalArgumentException("Mapped range is outside Meshfish buffer bounds");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.callocPointer(1);
            VulkanUtils.crashIfFailure(
                Vma.vmaMapMemory(this.device.vma(), this.vmaAllocation, pointer),
                "Failed to map Meshfish buffer"
            );
            return new MappedView(MemoryUtil.memByteBuffer(pointer.get(0) + offset, (int) length));
        }
    }

    @Override
    public void destroy() {
        if (!this.closed) {
            this.closed = true;
            Vma.vmaDestroyBuffer(this.device.vma(), this.vkBuffer, this.vmaAllocation);
        }
    }

    @Override
    public void close() {
        destroy();
    }

    public final class MappedView implements AutoCloseable {
        private final ByteBuffer data;
        private boolean closed;

        private MappedView(ByteBuffer data) {
            this.data = data;
        }

        public ByteBuffer data() {
            return this.data;
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                Vma.vmaUnmapMemory(MeshfishVmaBuffer.this.device.vma(), MeshfishVmaBuffer.this.vmaAllocation);
            }
        }
    }
}
