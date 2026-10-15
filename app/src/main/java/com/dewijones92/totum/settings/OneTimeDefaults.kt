package com.dewijones92.totum.settings

import com.dewijones92.totum.data.sponsorblock.SkipCategory

internal fun withEndcardsOnce(stored: Set<SkipCategory>, alreadyApplied: Boolean): Set<SkipCategory>? =
    if (alreadyApplied || SkipCategory.OUTRO in stored) null else stored + SkipCategory.OUTRO
