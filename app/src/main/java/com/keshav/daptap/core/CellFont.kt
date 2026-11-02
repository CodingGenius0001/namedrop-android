package com.keshav.daptap.core

import java.text.Normalizer

/**
 * DapTap's grid-built display alphabet: every glyph is drawn on a 7-row cell grid, no diagonals except
 * where a stair of cells reads as one. web/c/cellfont.js carries an identical copy (CellFontSyncTest).
 */
object CellFont {
    const val ROWS = 7
    const val GAP_COLUMNS = 1

    val glyphs: Map<Char, List<String>> = mapOf(
        'A' to g(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to g("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to g(".####", "#....", "#....", "#....", "#....", "#....", ".####"),
        'D' to g("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
        'E' to g("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to g("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to g(".####", "#....", "#....", "#.###", "#...#", "#...#", ".###."),
        'H' to g("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to g("#", "#", "#", "#", "#", "#", "#"),
        'J' to g("....#", "....#", "....#", "....#", "....#", "#...#", ".###."),
        'K' to g("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to g("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to g("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to g("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
        'O' to g(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to g("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to g(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to g("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to g(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to g("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to g("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to g("#...#", "#...#", "#...#", "#...#", ".#.#.", ".#.#.", "..#.."),
        'W' to g("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"),
        'X' to g("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to g("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'Z' to g("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        '0' to g(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to g(".#.", "##.", ".#.", ".#.", ".#.", ".#.", "###"),
        '2' to g(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to g("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
        '4' to g("#...#", "#...#", "#...#", "#####", "....#", "....#", "....#"),
        '5' to g("#####", "#....", "#....", "####.", "....#", "....#", "####."),
        '6' to g(".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to g("#####", "....#", "...#.", "..#..", "..#..", "..#..", "..#.."),
        '8' to g(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to g(".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."),
        '-' to g("...", "...", "...", "###", "...", "...", "..."),
        '.' to g(".", ".", ".", ".", ".", ".", "#"),
        '\'' to g("#", "#", ".", ".", ".", ".", "."),
        ' ' to g("..", "..", "..", "..", "..", "..", ".."),
    )

    private fun g(vararg rows: String): List<String> {
        require(rows.size == ROWS && rows.all { it.length == rows[0].length })
        return rows.toList()
    }

    /** Uppercases, strips accents (é → E) and maps typographic apostrophes; keeps unknown chars as-is. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.trim().uppercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('’', '\'').replace('‘', '\'')
            .replace(Regex("\\s+"), " ")

    /** True when the whole word can be drawn in cells; otherwise callers fall back to regular type. */
    fun canRender(text: String): Boolean {
        val n = normalize(text)
        return n.isNotEmpty() && n.all { glyphs.containsKey(it) }
    }

    data class Layout(val columns: Int, val cells: List<Pair<Int, Int>>) // (column, row) of every lit cell

    /** Lays [text] out on one line of cells. */
    fun layout(text: String): Layout {
        val n = normalize(text)
        val cells = ArrayList<Pair<Int, Int>>()
        var x = 0
        n.forEachIndexed { i, ch ->
            val glyph = glyphs[ch] ?: glyphs.getValue(' ')
            for (r in 0 until ROWS) for (c in glyph[r].indices) if (glyph[r][c] == '#') cells += (x + c) to r
            x += glyph[0].length
            if (i != n.lastIndex) x += GAP_COLUMNS
        }
        return Layout(x, cells)
    }
}
