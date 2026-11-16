package fyi.fyw.etobackup.mixins;

import net.minecraft.util.IProgressUpdate;
import net.minecraft.world.WorldServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fyi.fyw.etobackup.BackupGate;

@Mixin(WorldServer.class)
public abstract class MixinWorldServer {

    /**
     * Autosave, save-all, another mod's backup and stopping all come through here. A save queues chunks and writes
     * level.dat, the data files and whatever mods save along with it, so it waits until the snapshot is read.
     */
    @Inject(method = "saveAllChunks", at = @At("HEAD"), require = 1)
    private void backup$waitForSnapshot(boolean all, IProgressUpdate progress, CallbackInfo callback) {
        BackupGate.pass();
        if (all) BackupGate.saved(this);
    }
}
