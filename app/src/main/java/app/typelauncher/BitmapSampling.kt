package app.typelauncher

/**
 * Chooses a `BitmapFactory.Options.inSampleSize` for decoding a
 * [srcWidth] × [srcHeight] source down to roughly [targetPx] on a side.
 *
 * Shared by [AppIconLoader] (cached icon files) and [ContactPhotoLoader]
 * (contact photo blobs) so both bound their decodes the same way: without a
 * sample size, `BitmapFactory` allocates the source's full pixel count before
 * anything gets a chance to scale it down, which is a decode sized by whatever
 * the source happens to be rather than by what the launcher is about to draw.
 */
internal fun computeInSampleSize(srcWidth: Int, srcHeight: Int, targetPx: Int): Int {
    if (targetPx <= 0) return 1
    var sample = 1
    // Halve until both axes are within ~2× of the target. Powers-of-two keep
    // `BitmapFactory`'s decoder on its fast path; any residual mismatch is
    // cleaned up by `createScaledBitmap` afterwards. The `&&` is deliberate:
    // halving while *either* axis is still oversized would take the shorter
    // axis below the target on a lopsided source, and the scale back up would
    // show as a soft icon.
    while ((srcHeight / sample) > targetPx * 2 && (srcWidth / sample) > targetPx * 2) {
        sample *= 2
    }
    return sample
}
