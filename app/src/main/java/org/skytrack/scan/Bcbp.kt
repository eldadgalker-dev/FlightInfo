// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// SkyTrack - Bcbp
// Version 1.5
// Purpose : Parser for the IATA Bar Coded Boarding Pass (BCBP, Resolution
//           792) mandatory block. Every boarding pass barcode (PDF417 on
//           paper, Aztec / QR on mobile) starts with this fixed 60-character
//           header, which carries origin, destination, carrier, flight
//           number, day of year and seat. Pure Kotlin, JVM-testable.
//           Departure time is NOT part of the barcode.
// =============================================================
package org.skytrack.scan

import java.time.LocalDate
import java.time.Year

data class BoardingPass(
    val passengerName: String,
    val fromIata: String,
    val toIata: String,
    val carrier: String,
    val flightNumber: String,      // carrier + number, e.g. "LY315"
    val date: LocalDate?,          // resolved from day-of-year; null if invalid
    val seat: String,
    val legs: Int
)

object Bcbp {

    private const val MANDATORY_LENGTH = 60

    /**
     * Parse the first leg of a BCBP string. Returns null if the text is not a
     * BCBP payload. Lenient about trailing content and about pass variants
     * that omit the optional block.
     * @param today used to resolve the 3-digit day-of-year into a full date.
     */
    fun parse(raw: String, today: LocalDate = LocalDate.now()): BoardingPass? {
        val s = raw.trimStart()
        if (s.length < MANDATORY_LENGTH - 2) return null      // some issuers drop the size field
        if (s[0] != 'M') return null
        val legs = s[1].digitToIntOrNull() ?: return null
        if (legs !in 1..4) return null

        val name = s.substring(2, 22).trim()
        val from = s.substring(30, 33).trim().uppercase()
        val to = s.substring(33, 36).trim().uppercase()
        val carrier = s.substring(36, 39).trim().uppercase()
        val number = s.substring(39, 44).trim().trimStart('0')
        val doy = s.substring(44, 47).trim().toIntOrNull()
        val seat = s.substring(48, 52).trim().trimStart('0')

        if (!from.all { it.isLetter() } || from.length != 3) return null
        if (!to.all { it.isLetter() } || to.length != 3) return null
        if (carrier.isEmpty() || number.isEmpty()) return null

        return BoardingPass(
            passengerName = name,
            fromIata = from,
            toIata = to,
            carrier = carrier,
            flightNumber = carrier + number.filter { it.isLetterOrDigit() },
            date = doy?.let { resolveDayOfYear(it, today) },
            seat = seat,
            legs = legs
        )
    }

    /**
     * BCBP stores only the day of the year. Pick the year (previous, current or
     * next) whose date is closest to today, so a pass scanned around New Year
     * still resolves correctly.
     */
    fun resolveDayOfYear(doy: Int, today: LocalDate): LocalDate? {
        if (doy < 1 || doy > 366) return null
        var best: LocalDate? = null
        for (y in today.year - 1..today.year + 1) {
            if (doy > Year.of(y).length()) continue
            val d = LocalDate.ofYearDay(y, doy)
            if (best == null || kotlin.math.abs(d.toEpochDay() - today.toEpochDay()) <
                kotlin.math.abs(best.toEpochDay() - today.toEpochDay())) best = d
        }
        return best
    }
}
