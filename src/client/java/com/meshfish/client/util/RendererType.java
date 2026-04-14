package com.meshfish.client.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.meshfish.Meshfish;

import net.minecraft.client.Minecraft;

public enum RendererType {
    VULKAN("Vulkan"),
    OPENGL("OpenGL");

    public static final Logger LOGGER = LoggerFactory.getLogger(Meshfish.MOD_ID);

    private final String name;

    RendererType(String name) {
        this.name = name;
    }

    public static RendererType getCurrent() {
        String desc = Minecraft.getInstance().getWindow().backend().getName();
        return desc.equalsIgnoreCase("vulkan") ? RendererType.VULKAN : RendererType.OPENGL;
    }
}
