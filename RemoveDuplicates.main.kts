@file:DependsOn("io.ktor:ktor-client-cio-jvm:2.3.9")
@file:DependsOn("com.aallam.openai:openai-client-jvm:3.7.0")

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import java.io.File
import com.aallam.openai.client.OpenAI
import kotlinx.coroutines.runBlocking
import java.nio.charset.Charset
import kotlin.math.absoluteValue

val manualGroups = mutableListOf<DuplicateGroup>()

// Wrap actual code in main to call it from the bottom after everything is initialized
fun main() = runBlocking {
    val csvFile = File(args[0])
    val groups = determineGroups(csvFile)

    for (group in groups.values) {
        if (!group.valid) {
            println("Skipping group ${group.groupId} INVALID")
            manualGroups.add(group)
            continue
        }
        val existingDuplicates = group.getExistingDuplicates()
        if (existingDuplicates.size < 2) {
            println("Skipping group ${group.groupId}, not enough duplicates")
            continue
        }

        println("Group ${group.groupId}")
        for (file in existingDuplicates) {
            println("Option ${file.absolutePath}")
        }
        val messages = mutableListOf(systemMessage)
        val choice = messages.determineFileToKeep(existingDuplicates)
        if (choice == null) {
            println("Skipping group ${group.groupId}, no choice")
            manualGroups.add(group)
            continue
        }
        existingDuplicates
            .asSequence()
            .filter { it != choice }
            .filter { it.exists() } // another merge could delete it
            .forEach { duplicate ->
                println("Deleting ${duplicate.absolutePath}")
                duplicate.delete()
                val completelyMergeFolders = messages.determineMergeFolder(duplicate.parentFile, choice.parentFile)
                mergeFolder(duplicate.parentFile, choice.parentFile, completelyMergeFolders)
            }
    }

    println()
    println("FINISHED AUTOMATIC PARSING")
    println()

    for ((index, group) in manualGroups.withIndex()) {
        val existingDuplicates = group.getExistingDuplicates()
        if (existingDuplicates.size < 2) {
            continue
        }
        println("Group ${group.groupId} could not be manually resolved. Please chose the file to keep from these options: ($index/${manualGroups.size}")
        existingDuplicates.forEachIndexed { i, file ->
            println("$i: " + file.absolutePath + " " + file.length())
        }
        val input = readln()
        val indexToKeep = input.toIntOrNull()
        if (indexToKeep == null || indexToKeep >= existingDuplicates.size || indexToKeep < 0) {
            println("INVALID input $input, skipping group")
        } else {
            existingDuplicates
                .filterIndexed { i, _ -> i != indexToKeep }
                .forEach { duplicate ->
                    println("Deleting ${duplicate.absolutePath}")
                    duplicate.deleteIfExists()
                }
        }
    }
}

fun determineGroups(csvFile: File): Map<Int, DuplicateGroup> {
    return csvFile.readLines(Charset.forName("UTF-16"))
        .asSequence()
        .drop(1)
        .filter { it.isNotBlank() }
        .map { line ->
            val values = line.split('\t')
            val groupId = values[0].toInt()
            val filePath = values[2].trim('"')
            val size = values[3].toLong()
            val file = File(filePath)
            Triple(groupId, file, size)
        }
        .filter { (_, file, _) -> file.extension == "pdf" } // most useful for me
        .filterNot { (_, file, _) -> file.name.contains("SessionTracking") } // big group
        .filter { (_, file, _) -> file.exists() }
        .groupingBy { it.first }
        .aggregate { groupId, acc: DuplicateGroup?, (_, file, size), _ ->
            if (acc == null) {
                DuplicateGroup(groupId, size, mutableListOf(file))
            } else {
                acc.duplicates.add(file)
                if (acc.size != size) {
                    acc.valid = false
                }
                acc
            }
        }
}

fun mergeFolder(sourceDir: File, targetDir: File, completelyMerge: Boolean) {
    sourceDir.listFiles()!!.forEach { sourceFile ->
        val targetFile = File(targetDir, sourceFile.name)
        if (targetFile.exists()) {
            val sizeDiff = (targetFile.length() - sourceFile.length()).absoluteValue
            if (sizeDiff == 0L) {
                println("\tMerge: Deleting ${sourceFile.name}, already exists")
                sourceFile.delete()
            } else if (completelyMerge && targetFile.length() > sizeDiff * 1000) {
                println("\tMerge: Deleting ${sourceFile.name}, already exists and diff only $sizeDiff")
                sourceFile.delete()
            } else if (completelyMerge) {
                println("\tMerge: Skipping ${sourceFile.name}, name taken different size")
                manualGroups.add(DuplicateGroup(-1, targetFile.length(), mutableListOf(targetFile, sourceFile), valid = false))
            }
        } else if (completelyMerge) {
            println("\tMerge: Moving ${sourceFile.name}")
            sourceFile.renameTo(targetFile)
        }
    }
    if (sourceDir.listFiles()?.isEmpty() == true) {
        println("\tMerge: Dir deleted $sourceDir")
        sourceDir.delete()
    }
}

val openAiClient = OpenAI(token = "api-token", logging = LoggingConfig(LogLevel.None))
val gpt4Model = ModelId("gpt-4")

suspend fun MutableList<ChatMessage>.determineFileToKeep(duplicates: List<File>): File? {
    val message = duplicates.joinToString(
        prefix = "Which of these files to keep? Only return the path of the file to keep or unclear if the choice is unclear.\n",
        separator = "\n",
        transform = { "\"${it.absolutePath}\"" },
    )
    add(ChatMessage(role = ChatRole.User, content = message))
    val determineRequest = ChatCompletionRequest(
        model = gpt4Model,
        temperature = 0.7,
        topP = 1.0,
        frequencyPenalty = 0.0,
        presencePenalty = 0.0,
        messages = this,
    )
    val response = openAiClient.chatCompletion(determineRequest).choices.firstOrNull()?.message!!
    add(response)
    val keptPath = response.content!!.trim('\"')
    println("Chose to keep: $keptPath")
    return duplicates.firstOrNull { it.absolutePath == keptPath }
}

suspend fun MutableList<ChatMessage>.determineMergeFolder(obsoleteFolder: File, targetDir: File): Boolean {
    println("Merge: Should we merge ${obsoleteFolder.absolutePath} into ${targetDir.absolutePath}")
    val message = "Should all files from the folder \"" + obsoleteFolder.absolutePath + "\" be moved to \"" +
            targetDir.absolutePath + "\"?\nReturn true or false. " +
            "Only return true if you are confident that all files within the first directory make sense in the second directory."
    add(ChatMessage(role = ChatRole.User, content = message))
    val determineRequest = ChatCompletionRequest(
        model = gpt4Model,
        temperature = 0.7,
        topP = 1.0,
        frequencyPenalty = 0.0,
        presencePenalty = 0.0,
        messages = this,
    )
    val response = openAiClient.chatCompletion(determineRequest).choices.firstOrNull()?.message!!
    add(response)
    println("Chose to merge folders: ${response.content}")
    return response.content!!.toBoolean()
}

data class DuplicateGroup(
    val groupId: Int,
    val size: Long,
    val duplicates: MutableList<File> = mutableListOf(),
    var valid: Boolean = true,
) {
    fun getExistingDuplicates(): List<File> = duplicates.filter { it.exists() }
}

fun File.deleteIfExists() {
    if (exists()) delete()
}

val systemPrompt = """
    You help determine which file to keep between multiple duplicates of a file. Keep the file with the better readable path that is informative and concise. Try to avoid characters that are not human readable.

    Ensure your answers match the requirements in the request, as they will be consumed by a software not a human.

    If the criteria are contradicting each other or the files appear to not be duplicates, return "unclear"
""".trimIndent()

val systemMessage = ChatMessage(role = ChatRole.System, content = systemPrompt)

main() // run last so that everything is initialized
