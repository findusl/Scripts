@file:DependsOn("it.skrape:skrapeit:1.2.2")

import it.skrape.core.htmlDocument
import it.skrape.selects.eachHref
import it.skrape.selects.html5.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

println("Starting")

main()

fun main() {
    val folder = File("/Users/slehrbaum/Library/CloudStorage/OneDrive-Personal/My_DND5e_Campaign/Places/Towns/Waterdeep/News")

    val links = queryMainPageForLinks()

    links.forEach { url ->
        try {
            val article = parseArticle(url)
            val target = File(folder, article.first + ".md")
            target.writeText(article.second)
        } catch (e: Exception) {
            println("Failed for $url with $e")
        }
    }
}

fun queryMainPageForLinks(): List<String> {
    val links = mutableListOf<String>()

    val document = getWebsite("https://rpg.nobl.ca/archive.php?x=dnd/archfr/wdn")
    htmlDocument(document) {
        a {
            withClass = "serieslink"
            findAll {
                links += eachHref
            }
        }
    }
    return links.filter { !it.endsWith("zip") }.map { "https://rpg.nobl.ca$it" }
}

fun parseArticle(url: String): Pair<String, String> {
    val document = getWebsite(url)
    var title = ""
    val contents = mutableListOf<String>()

    htmlDocument(document) {
        td {
            withAttributes = listOf("valign" to "top", "width" to "100%")
            b {
                findFirst {
                    title = text
                }
            }
            p {
                findAll {
                    forEach {
                        contents += it.text
                    }
                }
            }
        }
    }
    val filteredContents = contents.takeWhile { it.isNotBlank() }.dropWhile { it == title }

    val bodyMarkdown = filteredContents.joinToString(separator = "\n\n")
    return title to bodyMarkdown
}

/**
 * Skrape.it webclient has some trouble in the script. so I fetch the website manually
 */
fun getWebsite(url: String): String {
    val urlObj = URL(url)
    val connection = urlObj.openConnection() as HttpURLConnection
    connection.requestMethod = "GET"

    val responseCode = connection.responseCode

    if (responseCode == HttpURLConnection.HTTP_OK) {
        val inputStream = connection.inputStream
        val reader = BufferedReader(InputStreamReader(inputStream))
        val response = reader.readText()
        reader.close()
        connection.disconnect()
        return response
    } else {
        connection.disconnect()
        throw Exception("Failed to connect. Response code: $responseCode")
    }
}
