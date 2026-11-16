package fyi.fyw.etobackup

import cpw.mods.fml.common.Mod

@Mod(
    modid = ETOBackup.MOD_ID,
    name = "ETOBackup",
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*",
    modLanguageAdapter = "net.shadowfacts.forgelin.KotlinAdapter",
    dependencies = "required-after:forgelin",
)
object ETOBackup {
    const val MOD_ID = "etobackup"
}
