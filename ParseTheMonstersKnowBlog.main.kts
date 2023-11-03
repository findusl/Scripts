@file:DependsOn("it.skrape:skrapeit:1.2.1")

import it.skrape.core.document
import it.skrape.fetcher.HttpFetcher
import it.skrape.fetcher.response
import it.skrape.fetcher.skrape
import it.skrape.selects.ElementNotFoundException
import it.skrape.selects.html5.div

main()

fun main() {
    var url: String? = "https://www.themonstersknow.com/dragon-tactics-part-5-deep-dragons-and-sea-serpents/"
    val result = mutableMapOf<String, String>()
    while (url?.isNotBlank() == true) {
        var (name, prevUrl) = getNameAndLink(url) ?: break
        name = name.removePrefix("Previous Post").trim()
        println("Found page $name with url $prevUrl")
        result[name] = prevUrl
        url = prevUrl
    }

    val markdown = result.entries.asSequence()
        .map { it.toPair() }
        .sortedBy { it.first }
        .map { (name, link) -> "[$name]($link)" }
        .joinToString(separator = "\n")

    println(markdown)
}

fun getNameAndLink(url: String): Pair<String, String>? {
    try {
        val result = skrape(HttpFetcher) {
            request {
                this.url = url
            }

            response {
                document.div {
                    withClass = "nav-previous"
                    findFirst {
                        eachLink
                    }
                }
            }
        }

        return result.entries.firstOrNull()?.toPair()
    } catch (e: ElementNotFoundException) {
        return null
    }
}
