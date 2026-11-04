package com.fastdownloader.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordImportTest {
    @Test
    fun readsGooglePasswordManagerExport() {
        val csv = "name,url,username,password,note\r\n" +
            "accounts.google.com,https://accounts.google.com/,me@gmail.com,\"p,a\"\"ss\",\r\n" +
            "site,https://example.com/login,bob,\"two\nlines\",hi\r\n"
        val rows = parseCsv(csv)
        assertEquals(3, rows.size)
        assertEquals(listOf("accounts.google.com", "https://accounts.google.com/", "me@gmail.com", "p,a\"ss", ""), rows[1])
        assertEquals("two\nlines", rows[2][3])
    }
}
