package fyi.fyw.etobackup.mixins;

import net.minecraft.world.gen.ChunkProviderServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import fyi.fyw.etobackup.BackupGate;

@Mixin(ChunkProviderServer.class)
public abstract class MixinChunkProviderServer {

    /**
     * Unloading a chunk saves it as it is then, later than the save the snapshot is of. Chunks stay loaded until the
     * snapshot is read, as they do while saving is off.
     */
    @Inject(method = "unloadQueuedChunks", at = @At("HEAD"), cancellable = true, require = 1)
    private void backup$keepChunksLoaded(CallbackInfoReturnable<Boolean> callback) {
        if (BackupGate.holding()) callback.setReturnValue(false);
    }
}
