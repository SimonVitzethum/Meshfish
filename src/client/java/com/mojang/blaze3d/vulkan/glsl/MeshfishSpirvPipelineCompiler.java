package com.mojang.blaze3d.vulkan.glsl;

import com.meshfish.Meshfish;
import com.meshfish.client.render.MeshfishBuffers;
import com.meshfish.client.render.MeshfishPipelineRegistry;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;

public final class MeshfishSpirvPipelineCompiler {
    private static final String TASK_SHADER_RESOURCE = "tool.spv";
    private static final String MESH_SHADER_RESOURCE = "mesh.spv";
    private static final String FRAGMENT_SHADER_RESOURCE = "fragment.spv";
    private static final int COLOR_ATTACHMENT_FORMAT = 37;  
    private static final int DEPTH_ATTACHMENT_FORMAT = 126;

    private MeshfishSpirvPipelineCompiler() {
    }

    public static boolean shouldHandle(RenderPipeline pipeline) {
        return startsWithMeshfishNamespace(pipeline.getLocation())
            || startsWithMeshfishNamespace(pipeline.getVertexShader())
            || startsWithMeshfishNamespace(pipeline.getFragmentShader());
    }

    public static VulkanRenderPipeline compile(VulkanDevice device, RenderPipeline pipeline) throws IOException, ShaderCompileException {
        IntermediaryShaderModule taskShader = null;
        IntermediaryShaderModule meshShader = null;
        IntermediaryShaderModule fragmentShader = null;
        VulkanBindGroupLayout pushDescriptorLayout = null;
        long taskModule = 0L;
        long meshModule = 0L;
        long fragmentModule = 0L;
        long pipelineLayout = 0L;
        long withDepthPipeline = 0L;
        long withoutDepthPipeline = 0L;

        try {
            taskShader = null;
            meshShader = loadRequiredShader(MESH_SHADER_RESOURCE);
            fragmentShader = loadRequiredShader(FRAGMENT_SHADER_RESOURCE);

            ArrayList<VulkanBindGroupLayout.Entry> pushDescriptorEntries = new ArrayList<>();
            addToBindGroup(pushDescriptorEntries, taskShader, pipeline);
            addToBindGroup(pushDescriptorEntries, meshShader, pipeline);
            addToBindGroup(pushDescriptorEntries, fragmentShader, pipeline);

            if (taskShader != null) {
                rebind(taskShader, Map.of(), pushDescriptorEntries, false);
            }

            rebind(meshShader, Map.of(), pushDescriptorEntries, false);

            HashMap<String, Integer> meshOutputs = new HashMap<>();
            for (SpvVariable output : meshShader.outputs()) {
                int location = spvLocation(meshShader, output);
                meshOutputs.put(output.name(), location);
                meshOutputs.put(fieldName(output.name()), location);
            }
            rebind(fragmentShader, meshOutputs, pushDescriptorEntries, true);

            taskModule = taskShader == null ? 0L : taskShader.createVulkanShaderModule(device);
            meshModule = meshShader.createVulkanShaderModule(device);
            fragmentModule = fragmentShader.createVulkanShaderModule(device);

            int descriptorStageFlags = VK12.VK_SHADER_STAGE_FRAGMENT_BIT | EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
            if (taskModule != 0L) {
                descriptorStageFlags |= EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT;
            }

            pushDescriptorLayout = createPushDescriptorLayout(
                device,
                pushDescriptorEntries,
                pipeline.getLocation().toString(),
                descriptorStageFlags
            );

            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer layoutHandles = MeshfishBuffers.descriptorSetLayout != 0L
                    ? stack.longs(pushDescriptorLayout.handle(), MeshfishBuffers.descriptorSetLayout)
                    : stack.longs(pushDescriptorLayout.handle());
                VkPipelineLayoutCreateInfo layoutCreateInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(layoutHandles);
                LongBuffer pointer = stack.mallocLong(1);
                VulkanUtils.crashIfFailure(
                    VK12.vkCreatePipelineLayout(device.vkDevice(), layoutCreateInfo, null, pointer),
                    "Can't create pipeline for " + pipeline.getLocation()
                );
                pipelineLayout = pointer.get(0);
                device.instance().debug().setObjectName(
                    device.vkDevice(),
                    17,
                    pipelineLayout,
                    () -> "Meshfish pipeline layout for " + pipeline.getLocation()
                );
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                int stageCount = taskModule == 0L ? 2 : 3;
                VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);
                ByteBuffer mainName = stack.UTF8("main");

                if (taskModule != 0L) {
                    shaderStages.put(
                        VkPipelineShaderStageCreateInfo.calloc(stack)
                            .sType$Default()
                            .stage(EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT)
                            .module(taskModule)
                            .pName(mainName)
                    );
                }

                shaderStages.put(
                    VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default()
                        .stage(EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT)
                        .module(meshModule)
                        .pName(mainName)
                );
                shaderStages.put(
                    VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default()
                        .stage(VK12.VK_SHADER_STAGE_FRAGMENT_BIT)
                        .module(fragmentModule)
                        .pName(mainName)
                );
                shaderStages.flip();

                VkPipelineVertexInputStateCreateInfo vertexInputState = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType$Default();
                VkPipelineInputAssemblyStateCreateInfo inputAssemblyState = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .topology(VulkanConst.toVk(pipeline.getVertexFormatMode()));
                VkPipelineRasterizationStateCreateInfo rasterizationState = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .polygonMode(VulkanConst.toVk(pipeline.getPolygonMode()))
                    .cullMode(pipeline.isCull() ? VK12.VK_CULL_MODE_BACK_BIT : VK12.VK_CULL_MODE_NONE)
                    .frontFace(VK12.VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .lineWidth(1.0f);
                VkPipelineDepthStencilStateCreateInfo depthStencilState = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default();

                if (pipeline.getDepthStencilState() != null) {
                    rasterizationState.depthBiasEnable(
                        pipeline.getDepthStencilState().depthBiasConstant() != 0.0f
                            && pipeline.getDepthStencilState().depthBiasScaleFactor() != 0.0f
                    );
                    rasterizationState.depthBiasConstantFactor(pipeline.getDepthStencilState().depthBiasConstant());
                    rasterizationState.depthBiasSlopeFactor(pipeline.getDepthStencilState().depthBiasScaleFactor());
                    depthStencilState.depthTestEnable(true);
                    depthStencilState.depthWriteEnable(pipeline.getDepthStencilState().writeDepth());
                    depthStencilState.depthCompareOp(VulkanConst.toVk(pipeline.getDepthStencilState().depthTest()));
                }

                VkPipelineColorBlendAttachmentState.Buffer blendAttachments = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(toColorWriteMask(pipeline.getColorTargetState()));
                if (pipeline.getColorTargetState().blendFunction().isPresent()) {
                    applyBlendInformation(blendAttachments, pipeline.getColorTargetState().blendFunction().get());
                }

                VkPipelineColorBlendStateCreateInfo colorBlendState = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pAttachments(blendAttachments);
                VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .scissorCount(1)
                    .viewportCount(1);
                VkPipelineMultisampleStateCreateInfo multisampleState = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .rasterizationSamples(VK12.VK_SAMPLE_COUNT_1_BIT)
                    .sampleShadingEnable(false);
                VkPipelineDynamicStateCreateInfo dynamicStateInfo = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pDynamicStates(stack.ints(VK12.VK_DYNAMIC_STATE_VIEWPORT, VK12.VK_DYNAMIC_STATE_SCISSOR));
                VkPipelineRenderingCreateInfoKHR renderingInfo = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                    .sType$Default();
                renderingInfo.pColorAttachmentFormats(stack.ints(COLOR_ATTACHMENT_FORMAT));
                renderingInfo.depthAttachmentFormat(DEPTH_ATTACHMENT_FORMAT);

                VkGraphicsPipelineCreateInfo.Buffer createInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .flags(0)
                    .pStages(shaderStages)
                    .pVertexInputState(vertexInputState)
                    .pInputAssemblyState(inputAssemblyState)
                    .pRasterizationState(rasterizationState)
                    .pDepthStencilState(depthStencilState)
                    .pColorBlendState(colorBlendState)
                    .pViewportState(viewportState)
                    .pMultisampleState(multisampleState)
                    .pDynamicState(dynamicStateInfo)
                    .layout(pipelineLayout)
                    .pNext(renderingInfo);

                LongBuffer pointer = stack.mallocLong(1);
                VulkanUtils.crashIfFailure(
                    VK12.vkCreateGraphicsPipelines(device.vkDevice(), 0L, createInfo, null, pointer),
                    "Can't compile mesh pipeline " + pipeline.getLocation()
                );
                withDepthPipeline = pointer.get(0);
                device.instance().debug().setObjectName(
                    device.vkDevice(),
                    19,
                    withDepthPipeline,
                    () -> "Meshfish pipeline " + pipeline.getLocation()
                );

                if (pipeline.getDepthStencilState() == null) {
                    renderingInfo.depthAttachmentFormat(0);
                    VulkanUtils.crashIfFailure(
                        VK12.vkCreateGraphicsPipelines(device.vkDevice(), 0L, createInfo, null, pointer),
                        "Can't compile mesh pipeline " + pipeline.getLocation()
                    );
                    withoutDepthPipeline = pointer.get(0);
                    device.instance().debug().setObjectName(
                        device.vkDevice(),
                        19,
                        withoutDepthPipeline,
                        () -> "Meshfish pipeline " + pipeline.getLocation()
                    );
                }
            }

            VulkanRenderPipeline compiledPipeline = new VulkanRenderPipeline(
                pipeline,
                device,
                withDepthPipeline,
                withoutDepthPipeline,
                pipelineLayout,
                pushDescriptorLayout,
                meshModule,
                fragmentModule
            );
            MeshfishPipelineRegistry.register(compiledPipeline, taskModule);
            return compiledPipeline;
        } catch (IOException | ShaderCompileException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to compile Meshfish SPIR-V pipeline " + pipeline.getLocation(), t);
        } finally {
            closeQuietly(taskShader);
            closeQuietly(meshShader);
            closeQuietly(fragmentShader);

            if (withDepthPipeline == 0L && withoutDepthPipeline == 0L) {
                destroyShaderModule(device, fragmentModule);
                destroyShaderModule(device, meshModule);
                destroyShaderModule(device, taskModule);

                if (pipelineLayout != 0L) {
                    VK12.vkDestroyPipelineLayout(device.vkDevice(), pipelineLayout, null);
                }

                if (pushDescriptorLayout != null && pushDescriptorLayout.handle() != 0L) {
                    VK12.vkDestroyDescriptorSetLayout(device.vkDevice(), pushDescriptorLayout.handle(), null);
                }
            }
        }
    }

    private static boolean startsWithMeshfishNamespace(Object identifierLike) {
        return String.valueOf(identifierLike).startsWith("meshfish:");
    }

    private static IntermediaryShaderModule loadRequiredShader(String resourceName) throws IOException, ShaderCompileException {
        IntermediaryShaderModule shader = loadOptionalShader(resourceName);
        if (shader == null) {
            throw new IOException("Missing required SPIR-V shader resource " + resourceName);
        }
        return shader;
    }

    private static IntermediaryShaderModule loadOptionalShader(String resourceName) throws IOException, ShaderCompileException {
        InputStream input = MeshfishSpirvPipelineCompiler.class.getClassLoader().getResourceAsStream(resourceName);
        if (input == null) {
            return null;
        }

        try (InputStream stream = input) {
            byte[] bytes = stream.readAllBytes();
            if (bytes.length == 0) {
                return null;
            }

            ByteBuffer spirv = MemoryUtil.memAlloc(bytes.length);
            try {
                spirv.put(bytes);
                spirv.flip();
                return IntermediaryShaderModule.createFromSpirv(resourceName, spirv);
            } catch (Throwable t) {
                MemoryUtil.memFree(spirv);
                throw t;
            }
        }
    }

    private static VulkanBindGroupLayout createPushDescriptorLayout(
        VulkanDevice device,
        List<VulkanBindGroupLayout.Entry> entries,
        String name,
        int stageFlags
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = entries.isEmpty()
                ? null
                : VkDescriptorSetLayoutBinding.calloc(entries.size(), stack);

            if (bindings != null) {
                for (int i = 0; i < entries.size(); i++) {
                    VkDescriptorSetLayoutBinding binding = VkDescriptorSetLayoutBinding.calloc(stack)
                        .descriptorType(toDescriptorType(entries.get(i).type()))
                        .descriptorCount(1)
                        .binding(i)
                        .stageFlags(stageFlags);
                    bindings.put(binding);
                }
                bindings.flip();
            }

            VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR);
            if (bindings != null) {
                createInfo.pBindings(bindings);
            }

            LongBuffer pointer = stack.mallocLong(1);
            VulkanUtils.crashIfFailure(
                VK12.vkCreateDescriptorSetLayout(device.vkDevice(), createInfo, null, pointer),
                "Can't set layout for " + name
            );
            return new VulkanBindGroupLayout(pointer.get(0), List.copyOf(entries));
        }
    }

    private static int toDescriptorType(VulkanBindGroupLayout.VulkanBindGroupEntryType type) {
        return switch (type) {
            case UNIFORM_BUFFER -> VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case SAMPLED_IMAGE -> VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case TEXEL_BUFFER -> VK12.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
        };
    }

    private static int toColorWriteMask(com.mojang.blaze3d.pipeline.ColorTargetState colorTargetState) {
        int mask = 0;
        if (colorTargetState.writeRed()) {
            mask |= VK12.VK_COLOR_COMPONENT_R_BIT;
        }
        if (colorTargetState.writeGreen()) {
            mask |= VK12.VK_COLOR_COMPONENT_G_BIT;
        }
        if (colorTargetState.writeBlue()) {
            mask |= VK12.VK_COLOR_COMPONENT_B_BIT;
        }
        if (colorTargetState.writeAlpha()) {
            mask |= VK12.VK_COLOR_COMPONENT_A_BIT;
        }
        return mask;
    }

    private static void addToBindGroup(
        List<VulkanBindGroupLayout.Entry> entries,
        IntermediaryShaderModule shader,
        RenderPipeline pipeline
    ) throws ShaderCompileException {
        if (shader == null) {
            return;
        }

        Optional<RenderPipeline.UniformDescription> uniformDescription;
        String name;

        for (SpvUniformBuffer buffer : shader.uniformBuffers()) {
            name = buffer.name();
            if (isMeshfishDescriptor(name)) {
                continue;
            }
            uniformDescription = findUniformDescription(pipeline, name);
            if (uniformDescription.isEmpty()) {
                throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
            }
            if (!hasEntry(entries, VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER, name)) {
                entries.add(new VulkanBindGroupLayout.Entry(VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER, name, null));
            }
        }

        for (SpvSampler sampler : shader.samplers()) {
            name = sampler.name();
            if (isMeshfishDescriptor(name)) {
                continue;
            }
            uniformDescription = findUniformDescription(pipeline, name);
            if (uniformDescription.isPresent()) {
                if (sampler.dimensions() != 5) {
                    throw new ShaderCompileException("UTB (" + name + ") must have type of SpvDimBuffer");
                }
                if (!hasEntry(entries, VulkanBindGroupLayout.VulkanBindGroupEntryType.TEXEL_BUFFER, name)) {
                    entries.add(new VulkanBindGroupLayout.Entry(
                        VulkanBindGroupLayout.VulkanBindGroupEntryType.TEXEL_BUFFER,
                        name,
                        uniformDescription.get().gpuFormat()
                    ));
                }
                continue;
            }

            if (!hasSampler(pipeline, name)) {
                throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
            }
            if (sampler.dimensions() != 1 && sampler.dimensions() != 3) {
                throw new ShaderCompileException("Sampled texture (" + name + ") must have type of SpvDim2D or SpvDimCube");
            }
            if (!hasEntry(entries, VulkanBindGroupLayout.VulkanBindGroupEntryType.SAMPLED_IMAGE, name)) {
                entries.add(new VulkanBindGroupLayout.Entry(VulkanBindGroupLayout.VulkanBindGroupEntryType.SAMPLED_IMAGE, name, null));
            }
        }
    }

    private static Optional<RenderPipeline.UniformDescription> findUniformDescription(RenderPipeline pipeline, String name) {
        for (RenderPipeline.UniformDescription description : pipeline.getUniforms()) {
            if (description.name().equals(name)) {
                return Optional.of(description);
            }
        }
        return Optional.empty();
    }

    private static boolean hasEntry(
        List<VulkanBindGroupLayout.Entry> entries,
        VulkanBindGroupLayout.VulkanBindGroupEntryType type,
        String name
    ) {
        for (VulkanBindGroupLayout.Entry entry : entries) {
            if (entry.type() == type && entry.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSampler(RenderPipeline pipeline, String name) {
        for (String sampler : pipeline.getSamplers()) {
            if (sampler.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void rebind(
        IntermediaryShaderModule shader,
        Map<String, Integer> inputVariables,
        List<VulkanBindGroupLayout.Entry> entries,
        boolean requireAllInputs
    ) throws ShaderCompileException {
        var spvAsIntBuffer = shader.spirv().asIntBuffer();
        HashSet<String> remainingInputs = new HashSet<>();
        HashSet<String> remainingSamplers = new HashSet<>();
        HashSet<String> remainingUniformBuffers = new HashSet<>();

        for (SpvVariable input : shader.inputs()) {
            remainingInputs.add(input.name());
        }
        for (SpvUniformBuffer uniformBuffer : shader.uniformBuffers()) {
            if (!isMeshfishDescriptor(uniformBuffer.name())) {
                remainingUniformBuffers.add(uniformBuffer.name());
            }
        }
        for (SpvSampler sampler : shader.samplers()) {
            if (!isMeshfishDescriptor(sampler.name())) {
                remainingSamplers.add(sampler.name());
            }
        }

        for (Map.Entry<String, Integer> inputEntry : inputVariables.entrySet()) {
            String variableName = inputEntry.getKey();
            for (SpvVariable inputVariable : shader.inputs()) {
                if (!sameStageVariable(inputVariable.name(), variableName)) {
                    continue;
                }
                spvAsIntBuffer.put(inputVariable.locationOffset(), inputEntry.getValue());
                remainingInputs.remove(inputVariable.name());
                break;
            }
        }

        entryLoop:
        for (int i = 0; i < entries.size(); i++) {
            VulkanBindGroupLayout.Entry entry = entries.get(i);
            switch (entry.type()) {
                case UNIFORM_BUFFER -> {
                    for (SpvUniformBuffer ubo : shader.uniformBuffers()) {
                        if (!ubo.name().equals(entry.name())) {
                            continue;
                        }
                        spvAsIntBuffer.put(ubo.bindingOffset(), i);
                        remainingUniformBuffers.remove(entry.name());
                        continue entryLoop;
                    }
                }
                case SAMPLED_IMAGE -> {
                    for (SpvSampler sampler : shader.samplers()) {
                        if (!sampler.name().equals(entry.name())) {
                            continue;
                        }
                        if (sampler.dimensions() != 1 && sampler.dimensions() != 3) {
                            throw new ShaderCompileException(
                                "Unsupported texture dimensions '"
                                    + SpvcUtil.imageDimensionToString(sampler.dimensions())
                                    + "' for sampler "
                                    + entry.name()
                            );
                        }
                        spvAsIntBuffer.put(sampler.bindingOffset(), i);
                        remainingSamplers.remove(entry.name());
                        continue entryLoop;
                    }
                }
                case TEXEL_BUFFER -> {
                    for (SpvSampler sampler : shader.samplers()) {
                        if (!sampler.name().equals(entry.name())) {
                            continue;
                        }
                        if (sampler.dimensions() != 5) {
                            throw new ShaderCompileException(
                                "Unsupported texel buffer dimensions '"
                                    + SpvcUtil.imageDimensionToString(sampler.dimensions())
                                    + "' for sampler "
                                    + entry.name()
                            );
                        }
                        spvAsIntBuffer.put(sampler.bindingOffset(), i);
                        remainingSamplers.remove(entry.name());
                        continue entryLoop;
                    }
                }
            }
        }

        if (requireAllInputs && !remainingInputs.isEmpty()) {
            throw new ShaderCompileException("Shader expects input variables which are not being provided: " + remainingInputs);
        }
        if (!remainingUniformBuffers.isEmpty()) {
            throw new ShaderCompileException("Shader expects uniform buffers which are not being provided: " + remainingUniformBuffers);
        }
        if (!remainingSamplers.isEmpty()) {
            throw new ShaderCompileException("Shader expects samplers which are not being provided: " + remainingSamplers);
        }
    }

    private static boolean sameStageVariable(String left, String right) {
        return left.equals(right) || fieldName(left).equals(fieldName(right));
    }

    private static String fieldName(String name) {
        int dot = name.lastIndexOf('.');
        return dot == -1 ? name : name.substring(dot + 1);
    }

    private static int spvLocation(IntermediaryShaderModule shader, SpvVariable variable) {
        return shader.spirv().asIntBuffer().get(variable.locationOffset());
    }

    private static boolean isMeshfishDescriptor(String name) {
        return name.equals("meshfishFrame")
            || name.equals("MeshfishFrameUniforms_std140")
            || name.equals("meshfishDraws")
            || name.equals("meshfishScene")
            || name.equals("meshfishVertices")
            || name.equals("meshfishIndices")
            || name.equals("meshfishBlockAtlas");
    }

    private static void applyBlendInformation(VkPipelineColorBlendAttachmentState.Buffer attachmentState, com.mojang.blaze3d.pipeline.BlendFunction blendFunction) {
        attachmentState
            .blendEnable(true)
            .colorBlendOp(VulkanConst.toVk(blendFunction.color().op()))
            .alphaBlendOp(VulkanConst.toVk(blendFunction.alpha().op()))
            .dstAlphaBlendFactor(VulkanConst.toVk(blendFunction.alpha().destFactor()))
            .dstColorBlendFactor(VulkanConst.toVk(blendFunction.color().destFactor()))
            .srcAlphaBlendFactor(VulkanConst.toVk(blendFunction.alpha().sourceFactor()))
            .srcColorBlendFactor(VulkanConst.toVk(blendFunction.color().sourceFactor()));
    }

    private static void destroyShaderModule(VulkanDevice device, long shaderModule) {
        if (shaderModule != 0L) {
            VK12.vkDestroyShaderModule((VkDevice) device.vkDevice(), shaderModule, null);
        }
    }

    private static void closeQuietly(IntermediaryShaderModule shader) {
        if (shader == null) {
            return;
        }
        try {
            shader.close();
        } catch (Throwable t) {
            Meshfish.LOGGER.warn("Failed to release SPIR-V buffer for {}", shader.name(), t);
        }
    }
}
