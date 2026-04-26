package com.meshfish.client.render;

import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public final class MeshfishPipelineRegistry {
    private static final Map<VulkanRenderPipeline, Long> EXTRA_TASK_MODULES =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Set<VulkanRenderPipeline> MESHFISH_PIPELINES =
        Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    public static volatile boolean meshShadersAvailable = false;

    private MeshfishPipelineRegistry() {
    }

    public static void register(VulkanRenderPipeline pipeline, long taskModule) {
        MESHFISH_PIPELINES.add(pipeline);
        if (taskModule != 0L) {
            EXTRA_TASK_MODULES.put(pipeline, taskModule);
        }
    }

    public static boolean isMeshfishPipeline(VulkanRenderPipeline pipeline) {
        return MESHFISH_PIPELINES.contains(pipeline);
    }

    public static long removeTaskModule(VulkanRenderPipeline pipeline) {
        MESHFISH_PIPELINES.remove(pipeline);
        Long taskModule = EXTRA_TASK_MODULES.remove(pipeline);
        return taskModule == null ? 0L : taskModule;
    }
}
