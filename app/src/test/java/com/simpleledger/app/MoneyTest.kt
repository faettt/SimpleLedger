package com.simpleledger.app

import com.simpleledger.app.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun `parse yuan string to cents`() {
        assertEquals(1250L, Money.parseToCents("12.5"))
        assertEquals(1200L, Money.parseToCents("12"))
        assertEquals(99L, Money.parseToCents("0.99"))
        assertEquals(1L, Money.parseToCents("0.01"))
        assertEquals(1250L, Money.parseToCents(" 12.50 "))
        assertEquals(1234567L, Money.parseToCents("12,345.67"))
    }

    @Test
    fun `parse rounds half up to cents`() {
        assertEquals(13L, Money.parseToCents("0.125"))
        assertEquals(12L, Money.parseToCents("0.124"))
    }

    @Test
    fun `parse rejects invalid input`() {
        assertNull(Money.parseToCents(""))
        assertNull(Money.parseToCents("abc"))
        assertNull(Money.parseToCents("-5"))
        assertNull(Money.parseToCents("0"))
        assertNull(Money.parseToCents("0.00"))
        assertNull(Money.parseToCents("99999999999999"))
    }

    @Test
    fun `format cents with grouping`() {
        assertEquals("1,234.56", Money.formatCents(123456L))
        assertEquals("0.99", Money.formatCents(99L))
        assertEquals("12.50", Money.formatCents(1250L))
    }

    @Test
    fun `format with symbol`() {
        assertEquals("¥1,234.56", Money.formatWithSymbol(123456L))
    }
}
