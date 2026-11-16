package fyi.fyw.etobackup.mixins;

import net.minecraft.world.chunk.storage.AnvilChunkLoader;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import fyi.fyw.etobackup.BackupGate;

@Mixin(AnvilChunkLoader.class)
public abstract class MixinAnvilChunkLoader {

    /**
     * Every chunk write, from the IO thread or from a flushing save, takes its chunk off the pending list in here.
     * Waiting before that keeps the chunk where a load finds it.
     */
    @WrapMethod(method = "writeNextIO")
    private boolean backup$waitForReader(Operation<Boolean> original) {
        long stamp = BackupGate.enter();
        try {
            return original.call();
        } finally {
            BackupGate.leave(stamp);
        }
    }
}
