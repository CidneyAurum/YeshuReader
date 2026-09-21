package app.yeshu.reader.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Key 状态机：界面文案完全由它决定，所以四种状态必须严格区分。
 * 关键是「密文存在」绝不等于「可用」——换机/恢复备份后 Keystore 密钥失效时，
 * 密文还在但解不开，此时必须报「需要重新填写」而不是「已安全保存」。
 */
class KeyPayloadStateTest {

    private val origin = "https://tokenrhythm.studio"

    @Test
    fun `没有密文时是未保存`() {
        assertEquals(
            KeyState.NONE,
            KeyPayloadState.classify(origin, origin, payloadPresent = false, decryptSucceeded = false)
        )
    }

    @Test
    fun `来源匹配且可解密才是可用`() {
        assertEquals(
            KeyState.OK,
            KeyPayloadState.classify(origin, origin, payloadPresent = true, decryptSucceeded = true)
        )
    }

    @Test
    fun `密文存在但解不开时报需要重新填写`() {
        assertEquals(
            KeyState.UNDECRYPTABLE,
            KeyPayloadState.classify(origin, origin, payloadPresent = true, decryptSucceeded = false)
        )
        assertNotNull(KeyState.UNDECRYPTABLE.hint)
        assertTrue(KeyState.UNDECRYPTABLE.hint!!.contains("重新填写"))
    }

    @Test
    fun `解不开优先于来源不符`() {
        // 两个问题同时存在时，先暴露更可操作的那个（重新填写 Key）
        assertEquals(
            KeyState.UNDECRYPTABLE,
            KeyPayloadState.classify("https://other.example", origin, payloadPresent = true, decryptSucceeded = false)
        )
    }

    @Test
    fun `来源不同不算可用`() {
        assertEquals(
            KeyState.ORIGIN_MISMATCH,
            KeyPayloadState.classify("https://other.example", origin, payloadPresent = true, decryptSucceeded = true)
        )
        assertNotNull(KeyState.ORIGIN_MISMATCH.hint)
    }

    @Test
    fun `来源未知的旧数据不算可用`() {
        assertEquals(
            KeyState.ORIGIN_MISMATCH,
            KeyPayloadState.classify("", origin, payloadPresent = true, decryptSucceeded = true)
        )
        assertEquals(
            KeyState.ORIGIN_MISMATCH,
            KeyPayloadState.classify(null, origin, payloadPresent = true, decryptSucceeded = true)
        )
    }

    @Test
    fun `地址为空时不能把 Key 当成可用`() {
        assertEquals(
            KeyState.ORIGIN_MISMATCH,
            KeyPayloadState.classify(origin, "", payloadPresent = true, decryptSucceeded = true)
        )
    }

    @Test
    fun `正常状态不产生多余提示`() {
        assertNull(KeyState.OK.hint)
        assertNull(KeyState.NONE.hint)
    }
}
