package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem

/**
 * Items whose PICTURE has been given up on this session, so automatic routes ask for the sound.
 *
 * Without this the rescue was undone within seconds and the item flapped. Reported from a real device
 * (0.1.437, commit c65a750, "tennis video not working????"): 403 at 09:51:47 -> refused -> sound kept
 * -> a video route at 09:51:52 -> 403 again at 09:52:00 -> video again at 09:52:10. `listen()` sets a
 * flag on the LAUNCHER, but every route decides from the persisted playback mode -- VIDEO in his case
 * -- so the next automatic route went straight back to the stream just refused, at a 10-14 second
 * extraction per cycle. What he saw was a video stopping every few seconds, forever.
 *
 * Per item and per session: it is a fact about these streams right now. Cleared by a deliberate tap on
 * Watch, because an automatic decision that cannot be overruled is worse than no automatic decision.
 */
internal class PictureChoices {
    private val pictureGivenUpOn = mutableSetOf<String>()
    private val pictureAskedFor = mutableSetOf<String>()

    fun gaveUpOn(id: MediaItemId) {
        pictureGivenUpOn.add(id.value)
    }

    fun wantsThePictureAgain(id: MediaItemId) {
        val refused = pictureGivenUpOn.remove(id.value)
        val firstAsk = pictureAskedFor.add(id.value)
        if (refused || firstAsk) {
            Diag.log("playback", "${id.value} asked for its picture back; routes will try the video again")
        }
    }

    fun soundOnly(queued: PlayableItem): Boolean =
        queued.item.id.value in pictureGivenUpOn ||
            (queued.item.contentKind == MediaContentKind.MUSIC && queued.item.id.value !in pictureAskedFor)
}
