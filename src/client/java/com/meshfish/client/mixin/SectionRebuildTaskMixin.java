package com.meshfish.client.mixin;

import com.meshfish.client.render.MeshfishPipelineRegistry;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection.CompileTask.SectionTaskResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$RebuildTask")
public abstract class SectionRebuildTaskMixin {
    @Shadow
    @Final
    private RenderSection this$1;

    @Inject(method = "doTask", at = @At("HEAD"), cancellable = true)
    private void meshfish$skipCpuVertexMesh(CallbackInfoReturnable<SectionTaskResult> cir) {
        if (!MeshfishPipelineRegistry.meshShadersAvailable) {
            return;
        }

        SectionMesh previous = this.this$1.sectionMesh.getAndSet(CompiledSectionMesh.EMPTY);
        if (previous != CompiledSectionMesh.EMPTY && previous != CompiledSectionMesh.UNCOMPILED) {
            previous.close();
        }

        this.this$1.setNotDirty();
        cir.setReturnValue(SectionTaskResult.SUCCESSFUL);
    }
}
