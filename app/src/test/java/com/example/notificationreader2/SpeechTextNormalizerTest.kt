package com.example.notificationreader2

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextNormalizerTest {

    @Test
    fun normalizesIsolatedDigitsAcrossCommonForms() {
        assertEquals("1啊", SpeechTextNormalizer.normalizeIsolatedContent("1"))
        assertEquals("一啊", SpeechTextNormalizer.normalizeIsolatedContent("一"))
        assertEquals("壹啊", SpeechTextNormalizer.normalizeIsolatedContent("壹"))
        assertEquals("６啊", SpeechTextNormalizer.normalizeIsolatedContent("６"))
        assertEquals("貳啊", SpeechTextNormalizer.normalizeIsolatedContent("貳"))
    }

    @Test
    fun normalizesIsolatedCommonSymbols() {
        assertEquals("句号", SpeechTextNormalizer.normalizeIsolatedContent("。"))
        assertEquals("问号", SpeechTextNormalizer.normalizeIsolatedContent("?"))
        assertEquals("省略号", SpeechTextNormalizer.normalizeIsolatedContent("……"))
        assertEquals("艾特", SpeechTextNormalizer.normalizeIsolatedContent("@"))
        assertEquals("反斜杠", SpeechTextNormalizer.normalizeIsolatedContent("\\"))
    }

    @Test
    fun trimsButDoesNotRewriteNormalContent() {
        assertEquals("1啊", SpeechTextNormalizer.normalizeIsolatedContent(" 1 "))
        assertEquals("文字一", SpeechTextNormalizer.normalizeIsolatedContent("文字一"))
        assertEquals("第一条", SpeechTextNormalizer.normalizeIsolatedContent("第一条"))
        assertEquals("11", SpeechTextNormalizer.normalizeIsolatedContent("11"))
        assertEquals("你好！", SpeechTextNormalizer.normalizeIsolatedContent("你好！"))
        assertEquals("嗯", SpeechTextNormalizer.normalizeIsolatedContent("嗯"))
    }
}
