package com.flatide.propertee2.value;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Screen columns for ALIGN_* (spec v0.31.0), checked at the edges of the Unicode 16.0 tables. */
class DisplayWidthTest {

    @Test void narrowWideAndZero() {
        assertEquals(5, DisplayWidth.of("ab 1."));
        assertEquals(4, DisplayWidth.of("사과"));                     // Hangul syllables
        assertEquals(4, DisplayWidth.of("漢字"));
        assertEquals(4, DisplayWidth.of("カナ"));
        assertEquals(4, DisplayWidth.of("\uFF21\uFF22"));           // fullwidth A, B
        assertEquals(2, DisplayWidth.of("\uD83D\uDE00"));           // U+1F600, a surrogate pair
        assertEquals(1, DisplayWidth.of("e\u0301"));                 // a combining accent takes none
        assertEquals(2, DisplayWidth.of("\u1112\u1161\u11AB"));    // decomposed 한: the vowel and final take none
        assertEquals(0, DisplayWidth.of("\u200B\uFEFF"));           // format characters (Cf)
        assertEquals(1, DisplayWidth.of("\uD800"));                  // a lone surrogate
    }

    @Test void tableEdges() {
        assertEquals(1, DisplayWidth.of("\u10FF"));
        assertEquals(2, DisplayWidth.of("\u1100"));                  // first wide range
        assertEquals(2, DisplayWidth.of("\u115F"));
        assertEquals(0, DisplayWidth.of("\u1160"));
        assertEquals(0, DisplayWidth.of("\u11FF"));
        assertEquals(1, DisplayWidth.of("\u1200"));
        assertEquals(2, DisplayWidth.of("\u3000"));                  // ideographic space (F)
        assertEquals(0, DisplayWidth.of("\u302A"));                  // a combining mark that is also W
        assertEquals(2, DisplayWidth.of("\uD7A3"));                  // last Hangul syllable
        assertEquals(0, DisplayWidth.of("\uD7B0"));
        assertEquals(1, DisplayWidth.of("\u00AC"));
        assertEquals(0, DisplayWidth.of("\u00AD"));                  // soft hyphen (Cf), first zero range
        assertEquals(2, DisplayWidth.of(new String(Character.toChars(0x3FFFD))));   // plane 3 defaults to W
        assertEquals(1, DisplayWidth.of(new String(Character.toChars(0x3FFFE))));
        assertEquals(0, DisplayWidth.of(new String(Character.toChars(0xE01EF))));   // last zero range
        assertEquals(1, DisplayWidth.of(new String(Character.toChars(0x10FFFF))));
    }
}
