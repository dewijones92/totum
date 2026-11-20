package com.dewijones92.totum.innertube.player

import java.io.IOException

public class NetworkUnreachable(public val detail: String) : IOException(detail)
