// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - Airlines
// Version 1.0
// Purpose : IATA (2-letter) to ICAO (3-letter) airline designators for the
//           carriers most likely to be tracked, used to turn a flight number
//           such as "LY315" into the ADS-B callsign "ELY315". Entries are
//           from public designator lists; an unknown prefix falls back to
//           trying the flight number itself as the callsign.
// =============================================================
package org.skytrack.data

object Airlines {

    private val iataToIcao: Map<String, String> = mapOf(
        // Israel and region
        "LY" to "ELY", "6H" to "ISR", "IZ" to "AIZ", "BZ" to "BBG",
        "RJ" to "RJA", "MS" to "MSR", "ME" to "MEA", "CY" to "CYP", "TK" to "THY", "PC" to "PGT",
        // Gulf
        "EK" to "UAE", "EY" to "ETD", "QR" to "QTR", "SV" to "SVA", "FZ" to "FDB", "G9" to "ABY",
        "XY" to "KNE", "WY" to "OMA", "GF" to "GFA", "KU" to "KAC", "ET" to "ETH",
        // Europe legacy
        "LH" to "DLH", "BA" to "BAW", "AF" to "AFR", "KL" to "KLM", "LX" to "SWR", "OS" to "AUA",
        "SN" to "BEL", "AZ" to "ITY", "IB" to "IBE", "TP" to "TAP", "SK" to "SAS", "AY" to "FIN",
        "LO" to "LOT", "OK" to "CSA", "A3" to "AEE", "OA" to "OAL", "JU" to "ASL", "RO" to "ROT",
        "BT" to "BTI", "EI" to "EIN", "VS" to "VIR", "SU" to "AFL", "UX" to "AEA", "WK" to "EDW",
        // Europe low cost / leisure
        "FR" to "RYR", "U2" to "EZY", "W6" to "WZZ", "W4" to "WMT", "VY" to "VLG", "DY" to "NAX",
        "D8" to "IBK", "EW" to "EWG", "DE" to "CFG", "X3" to "TUI", "HV" to "TRA", "TO" to "TVF",
        "LS" to "EXS", "BY" to "TOM", "4U" to "GWI", "TB" to "JAF",
        // Americas
        "DL" to "DAL", "UA" to "UAL", "AA" to "AAL", "WN" to "SWA", "B6" to "JBU", "AS" to "ASA",
        "NK" to "NKS", "F9" to "FFT", "AC" to "ACA", "WS" to "WJA", "LA" to "LAN", "AV" to "AVA",
        "CM" to "CMP", "AM" to "AMX",
        // Asia / Pacific
        "JL" to "JAL", "NH" to "ANA", "KE" to "KAL", "OZ" to "AAR", "CA" to "CCA", "MU" to "CES",
        "CZ" to "CSN", "CX" to "CPA", "SQ" to "SIA", "TG" to "THA", "MH" to "MAS", "GA" to "GIA",
        "QF" to "QFA", "VA" to "VOZ", "NZ" to "ANZ", "AI" to "AIC", "6E" to "IGO", "UK" to "VTI",
        "HU" to "CHH", "9C" to "CQH", "3U" to "CSC", "HO" to "DKH", "BR" to "EVA", "CI" to "CAL",
        "VN" to "HVN", "PR" to "PAL", "5J" to "CEB", "TR" to "TGW", "AK" to "AXM", "D7" to "XAX",
        "JQ" to "JST"
    )

    /**
     * Candidate ADS-B callsigns for a flight number, most likely first.
     * "LY315" -> ["ELY315", "LY315"]; "ELY315" -> ["ELY315"]; leading zeros are dropped.
     */
    fun callsignsFor(flightNumber: String): List<String> {
        val fn = flightNumber.trim().uppercase().replace(" ", "")
        val m = Regex("^([A-Z0-9]{2,3}?)(\\d{1,4})([A-Z]?)$").find(fn) ?: return listOf(fn).filter { it.isNotEmpty() }
        val prefix = m.groupValues[1]
        val number = m.groupValues[2].trimStart('0').ifEmpty { "0" }
        val suffix = m.groupValues[3]
        val out = ArrayList<String>(3)
        val icao = iataToIcao[prefix]
        if (icao != null) out.add(icao + number + suffix)
        if (prefix.length == 3 && prefix.all { it.isLetter() }) out.add(prefix + number + suffix)
        out.add(prefix + number + suffix)
        return out.distinct()
    }
}
