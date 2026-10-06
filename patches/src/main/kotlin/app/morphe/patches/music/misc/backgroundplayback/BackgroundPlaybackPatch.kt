package app.morphe.patches.music.misc.backgroundplayback

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.fix.bitmap.fixRecycledBitmapPatch
import app.morphe.util.findFreeRegister

val backgroundPlaybackPatch = bytecodePatch(
    name = "Remove background playback restrictions",
    description = "Removes restrictions on background playback, including playing kids videos in the background.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        fixRecycledBitmapPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        KidsBackgroundPlaybackPolicyControllerFingerprint.method.apply {
            val freeRegister = findFreeRegister(0)
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, Lapp/morphe/extension/shared/license/LicenseManager;->isActivated()Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :not_activated
                    return-void
                    :not_activated
                """
            )
        }

        BackgroundPlaybackDisableFingerprint.method.apply {
            val freeRegister = findFreeRegister(0)
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, Lapp/morphe/extension/shared/license/LicenseManager;->isActivated()Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :not_activated
                    const/4 v$freeRegister, 0x1
                    return v$freeRegister
                    :not_activated
                """
            )
        }
    }
}
