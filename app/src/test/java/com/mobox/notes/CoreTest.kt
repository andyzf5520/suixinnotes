package com.mobox.notes

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CoreTest {
    private val password = "正确密码-Pass2026".toCharArray()
    @Test fun encryptedRoundTripAndNoPlaintext() {
        val source = "账号=alice；密码=SuperSecret-892；图片数据".toByteArray()
        val seal = Crypto.encrypt(source, password)
        assertArrayEquals(source, Crypto.decrypt(seal, password))
        assertFalse(seal.toString(Charsets.ISO_8859_1).contains("SuperSecret-892"))
    }
    @Test fun wrongPasswordNeverReturnsPartialPlaintext() {
        val seal = Crypto.encrypt("secret".toByteArray(), password)
        assertThrows(javax.crypto.AEADBadTagException::class.java) { Crypto.decrypt(seal, "wrong".toCharArray()) }
    }
    @Test fun tamperedCiphertextFailsAuthentication() {
        val seal = Crypto.encrypt("secret".toByteArray(), password)
        seal[seal.lastIndex] = (seal.last().toInt() xor 1).toByte()
        assertThrows(javax.crypto.AEADBadTagException::class.java) { Crypto.decrypt(seal, password) }
    }
    @Test fun tamperedHeaderFailsAuthentication() {
        val seal = Crypto.encrypt("secret".toByteArray(), password)
        seal[10] = (seal[10].toInt() xor 1).toByte()
        assertThrows(javax.crypto.AEADBadTagException::class.java) { Crypto.decrypt(seal, password) }
    }
    @Test fun truncatedOrUnknownFilesRejected() {
        assertThrows(IllegalArgumentException::class.java) { Crypto.decrypt(ByteArray(12), password) }
        assertThrows(IllegalArgumentException::class.java) { Crypto.decrypt(ByteArray(60), password) }
    }
    @Test fun maliciousKdfParametersRejectedBeforeCostlyWork() {
        val seal = Crypto.encrypt("data".toByteArray(), password)
        java.nio.ByteBuffer.wrap(seal, 5, 4).putInt(Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { Crypto.decrypt(seal, password) }
    }
    @Test fun newSaltAndNonceForEachEncryption() {
        val a = Crypto.encrypt("same".toByteArray(), password)
        val b = Crypto.encrypt("same".toByteArray(), password)
        assertFalse(a.copyOfRange(9, 25).contentEquals(b.copyOfRange(9, 25)))
        assertFalse(a.copyOfRange(25, 37).contentEquals(b.copyOfRange(25, 37)))
    }
    @Test fun backupRoundTripPreservesEveryFieldAndImage() {
        val c = Category(name = "我的分类", color = 3)
        val note = Note(categoryId = c.id, kind = "account", title = "账号标题", body = "带换行\n中文", username = "alice", password = "PrivatePassword", url = "https://example.test", images = listOf(Base64.getEncoder().encodeToString(byteArrayOf(1,2,3))), fontSize = 23, font = "serif", bold = true, italic = true, paper = 4, favorite = true)
        val vault = Vault(listOf(c), listOf(note), Preferences(true, false, 3, 24))
        val backup = Crypto.encrypt(vault.bytes(), password)
        assertEquals(vault, Vault.read(Crypto.decrypt(backup, password)))
    }
    @Test fun encryptedCategoryCannotBeReadWithMasterPassword() {
        val privatePassword = "分类独立-Pass2026".toCharArray()
        val privateSeal = Crypto.encrypt("inside".toByteArray(), privatePassword)
        val c = Category(name = "私密", sealed = Base64.getEncoder().encodeToString(privateSeal))
        val outer = Crypto.encrypt(Vault(listOf(c)).bytes(), password)
        val decoded = Vault.read(Crypto.decrypt(outer, password))
        assertThrows(javax.crypto.AEADBadTagException::class.java) { Crypto.decrypt(Base64.getDecoder().decode(decoded.categories.single().sealed), password) }
        assertEquals("inside", Crypto.decrypt(privateSeal, privatePassword).toString(Charsets.UTF_8))
    }
    @Test fun invalidCategoryReferencesAndDuplicateIdsRejected() {
        val c = Category(name = "有效")
        assertThrows(IllegalArgumentException::class.java) { Vault.read(Vault(listOf(c), listOf(Note(categoryId = "missing"))).bytes()) }
        assertThrows(IllegalArgumentException::class.java) { Vault.read(Vault(listOf(c, c)).bytes()) }
        val n = Note(categoryId = c.id)
        assertThrows(IllegalArgumentException::class.java) { Vault.read(Vault(listOf(c), listOf(n, n)).bytes()) }
    }
    @Test fun csvPreventsFormulaInjectionAndEscapesQuotes() {
        val note = Note(categoryId = "c", kind = "account", title = "=CMD()", username = "\"quoted\",name", password = "Secret", body = "\n+formula")
        val csv = Exports.csv(listOf(note), false)
        assertTrue(csv.contains("\"'=CMD()\""))
        assertTrue(csv.contains("\"\"quoted\"\""))
        assertTrue(csv.contains("'\n+formula"))
        assertFalse(csv.contains("Secret"))
    }
    @Test fun htmlEscapesScriptsAndHidesSecretsByDefault() {
        val note = Note(categoryId = "c", kind = "account", title = "<script>alert(1)</script>", body = "<img onerror=evil>", password = "HiddenSecret")
        val html = Exports.html(listOf(note), false)
        assertFalse(html.contains("<script>")); assertFalse(html.contains("<img onerror")); assertFalse(html.contains("HiddenSecret"))
        assertTrue(html.contains("&lt;script&gt;")); assertTrue(Exports.html(listOf(note), true).contains("HiddenSecret"))
    }
    @Test fun textWarnsAboutMissingPicturesAndMasksPassword() {
        val note = Note(categoryId = "c", kind = "account", password = "HiddenSecret", images = listOf("AQID"))
        assertTrue(Exports.text(listOf(note), false).contains("1 张图片未包含"))
        assertFalse(Exports.text(listOf(note), false).contains("HiddenSecret"))
        assertTrue(Exports.text(listOf(note), true).contains("HiddenSecret"))
    }
    @Test fun csvOnlyExportsAccounts() {
        val result = Exports.csv(listOf(Note(categoryId = "c", title = "普通笔记"), Note(categoryId = "c", kind = "account", title = "账号")), false)
        assertFalse(result.contains("普通笔记")); assertTrue(result.contains("账号"))
    }
}
