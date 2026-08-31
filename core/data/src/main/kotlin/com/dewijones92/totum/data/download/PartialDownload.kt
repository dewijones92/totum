package com.dewijones92.totum.data.download

import java.io.File

/**
 * Where a part-fetched download lives until it is whole.
 *
 * One function rather than a string spelled out at each site, because three places have to agree
 * about it — the strategy that writes it, and cancel/delete, which have to take it away. A
 * `.part` nobody deletes is invisible bytes: no record points at it, so nothing in the app can
 * ever show it or remove it, and it would be resumed by a download that had moved on.
 */
internal fun File.partialDownload(): File = File("$path.part")
