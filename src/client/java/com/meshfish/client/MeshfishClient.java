package com.meshfish.client;

import com.meshfish.client.render.MeshfishFrameState;
import com.meshfish.client.render.MeshfishSceneRenderer;
import com.meshfish.client.util.RendererType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public class MeshfishClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MeshfishFrameState.initialize();
		MeshfishSceneRenderer.initialize();

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (client.player == null)
				return;

			RendererType current = RendererType.getCurrent();
			if (current == RendererType.VULKAN)
				return;

			client.player.sendSystemMessage(
					Component.translatable("meshfish.warning.not_vulkan").withStyle(ChatFormatting.RED));
		});
	}
}
