#!/usr/bin/env kotlinc -script

import java.io.File

val inputFile = File(args[0])

// Output file: inputFile_out
val outputFile = File(inputFile.parentFile, "${inputFile.nameWithoutExtension}_out.${inputFile.extension}")

data class ExternalSource(
    val sourceId: String,
    val mediaId: String,
    val title: String,
    val baseUrl: String
)

val lines = inputFile.readLines().toMutableList()

val externalSources = mutableMapOf<String, ExternalSource>()

var nextSourceIdNumber = 1
fun nextSourceIds(): Pair<String, String> {
    val sId = "@X${nextSourceIdNumber}@"
    val mId = "@X${nextSourceIdNumber + 1}@"
    nextSourceIdNumber += 2
    return sId to mId
}

fun getBaseUrl(fullUrl: String): String {
    // We trust that '?' is always present in these URLs.
    val idx = fullUrl.indexOf('?')
    return fullUrl.substring(0, idx)
}

val outputLines = mutableListOf<String>()

for (originalLine in lines) {
    // We assume valid GEDCOM SOUR line: "<level> SOUR ..."
    // Check length and structure before accessing substrings.
    if (originalLine.startsWith("TRLR", startIndex = 2)) {
        continue
    }
    if (!originalLine.startsWith("SOUR", startIndex = 2)) {
        outputLines.add(originalLine)
        continue
    }

    val level = originalLine.first().digitToInt()
    val rest = originalLine.substring(7) // After "<digit> SOUR "
    val parts = rest.split(";").map { it.trim() }

    val (geneanetSources, otherSources) = parts.partition { it.startsWith("Geneanet Family Tree") }

    for (part in geneanetSources) {
        // Extract the fullTitle and URL
        val fullTitle = part.substringBefore('(').trim()
        val fullUrl = part.substringAfter('(').substringBefore(')')

        val baseUrl = getBaseUrl(fullUrl)
        val extSource = externalSources.getOrPut(baseUrl) {
            val (sId, mId) = nextSourceIds()
            ExternalSource(
                sourceId = sId,
                mediaId = mId,
                title = fullTitle,
                baseUrl = baseUrl
            )
        }

        outputLines.add("$level SOUR ${extSource.sourceId}")
        outputLines.add("${level + 1} DATA")
        outputLines.add("${level + 2} TEXT $fullUrl")
    }
    if (otherSources.isNotEmpty()) {
        val recombined = otherSources.joinToString(" ; ")
        outputLines.add("$level SOUR $recombined")
    }
}

// Append the new sources at the end of the file
for (extSource in externalSources.values) {
    outputLines.add("0 ${extSource.sourceId} SOUR")
    outputLines.add("1 TITL ${extSource.title}")
    outputLines.add("1 REPO @X768@")
    outputLines.add("1 OBJE ${extSource.mediaId}")
    outputLines.add("0 ${extSource.mediaId} OBJE")
    outputLines.add("1 FILE ${extSource.baseUrl}")
    outputLines.add("2 TITL ${extSource.title}")
}

outputLines.add("0 TRLR")

outputFile.writeText(outputLines.joinToString("\n"))
