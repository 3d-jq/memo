package com.psyche.memo.common.logging

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenEstimatorTest {

    @Test
    fun emptyIsZero() {
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun asciiIsFourCharsPerToken() {
        assertEquals(1, TokenEstimator.estimate("abcd"))
        assertEquals(1, TokenEstimator.estimate("abc"))
        assertEquals(2, TokenEstimator.estimate("abcde"))
    }

    @Test
    fun cjkCountsOnePerCharacter() {
        assertEquals(4, TokenEstimator.estimate("你好世界"))
    }

    @Test
    fun mixesCjkAndLatin() {
        // 2 CJK + 4 ascii (1 token) = 3
        assertEquals(3, TokenEstimator.estimate("你好abcd"))
    }

    @Test
    fun fullWidthAndKanaAndHangulAreCjk() {
        assertEquals(3, TokenEstimator.estimate("ＡＢＣ"))
        assertEquals(2, TokenEstimator.estimate("かな"))
        assertEquals(2, TokenEstimator.estimate("한글"))
    }

    @Test
    fun surrogatePairsCountOnce() {
        // An emoji outside the BMP is one code point, not two chars.
        assertEquals(1, TokenEstimator.estimate("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00"))
    }
}
