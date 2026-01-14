package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.MessageNode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

private const val TAG = "ChatUtil"

fun navigateToChatPage(
    navController: NavHostController,
    chatId: Uuid = Uuid.random(),
    initText: String? = null,
    initFiles: List<Uri> = emptyList(),
    searchQuery: String? = null,
) {
    Log.i(TAG, "navigateToChatPage: navigate to $chatId")
    navController.navigate(
        route = Screen.Chat(
            id = chatId.toString(),
            text = initText,
            files = initFiles.map { it.toString() },
            searchQuery = searchQuery,
        ),
    ) {
        popUpTo(0) {
            inclusive = true
        }
        launchSingleTop = true
    }
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toText())
}

@OptIn(ExperimentalEncodingApi::class)
suspend fun Context.saveMessageImage(image: String) = withContext(Dispatchers.IO) {
    when {
        image.startsWith("data:image") -> {
            val byteArray = Base64.decode(image.substringAfter("base64,").toByteArray())
            val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
            exportImage(this@saveMessageImage.getActivity()!!, bitmap)
        }

        image.startsWith("file:") -> {
            val file = image.toUri().toFile()
            exportImageFile(this@saveMessageImage.getActivity()!!, file)
        }

        image.startsWith("http") -> {
            kotlin.runCatching { // Use runCatching to handle potential network exceptions
                val url = java.net.URL(image)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connect()

                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                    exportImage(this@saveMessageImage.getActivity()!!, bitmap)
                } else {
                    Log.e(
                        TAG,
                        "saveMessageImage: Failed to download image from $image, response code: ${connection.responseCode}"
                    )
                    null // Return null on failure
                }
            }.getOrNull() // Return null if any exception occurs during download
        }

        else -> error("Invalid image format")
    }
}

fun Context.createChatFilesByContents(uris: List<Uri>): List<Uri> {
    val newUris = mutableListOf<Uri>()
    val dir = this.filesDir.resolve("upload")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    uris.forEach { uri ->
        val fileName = Uuid.random()
        val file = dir.resolve("$fileName")
        if (!file.exists()) {
            file.createNewFile()
        }
        val newUri = file.toUri()
        runCatching {
            this.contentResolver.openInputStream(uri)?.use { inputStream ->
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            newUris.add(newUri)
        }.onFailure {
            it.printStackTrace()
            Log.e(TAG, "createChatFilesByContents: Failed to save image from $uri", it)
        }
    }
    return newUris
}

fun Context.createChatFilesByByteArrays(byteArrays: List<ByteArray>): List<Uri> {
    val newUris = mutableListOf<Uri>()
    val dir = this.filesDir.resolve("upload")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    byteArrays.forEach { byteArray ->
        val fileName = Uuid.random()
        val file = dir.resolve("$fileName")
        if (!file.exists()) {
            file.createNewFile()
        }
        val newUri = file.toUri()
        file.outputStream().use { outputStream ->
            outputStream.write(byteArray)
        }
        newUris.add(newUri)
    }
    return newUris
}

fun Context.getFileNameFromUri(uri: Uri): String? {
    var fileName: String? = null
    val projection = arrayOf(
        OpenableColumns.DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME // 优先尝试 DocumentProvider 标准列
    )
    contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        // 移动到第一行结果
        if (cursor.moveToFirst()) {
            // 尝试获取 DocumentsContract.Document.COLUMN_DISPLAY_NAME 的索引
            val documentDisplayNameIndex =
                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (documentDisplayNameIndex != -1) {
                fileName = cursor.getString(documentDisplayNameIndex)
            } else {
                // 如果 DocumentProvider 标准列不存在，尝试 OpenableColumns.DISPLAY_NAME
                val openableDisplayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (openableDisplayNameIndex != -1) {
                    fileName = cursor.getString(openableDisplayNameIndex)
                }
            }
        }
    }
    // 如果查询失败或没有获取到名称，fileName 会保持 null
    return fileName
}

fun Context.getFileMimeType(uri: Uri): String? {
    return when (uri.scheme) {
        "content" -> contentResolver.getType(uri)
        else -> null
    }
}

@OptIn(ExperimentalEncodingApi::class)
suspend fun Context.convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
    withContext(Dispatchers.IO) {
        message.copy(
            parts = message.parts.map { part ->
                when (part) {
                    is UIMessagePart.Image -> {
                        if (part.url.startsWith("data:image")) {
                            // base64 image
                            val sourceByteArray = Base64.decode(part.url.substringAfter("base64,").toByteArray())
                            val bitmap = BitmapFactory.decodeByteArray(sourceByteArray, 0, sourceByteArray.size)
                            val byteArray = bitmap.compress()
                            val urls = createChatFilesByByteArrays(listOf(byteArray))
                            Log.i(
                                TAG,
                                "convertBase64ImagePartToLocalFile: convert base64 img to ${urls.joinToString(", ")}"
                            )
                            part.copy(
                                url = urls.first().toString(),
                            )
                        } else {
                            part
                        }
                    }

                    else -> part
                }
            }
        )
    }

fun Bitmap.compress(): ByteArray = ByteArrayOutputStream().use {
    compress(Bitmap.CompressFormat.PNG, 100, it)
    it.toByteArray()
}

fun Context.deleteChatFiles(uris: List<Uri>) {
    uris.filter { it.toString().startsWith("file:") }.forEach { uri ->
        val file = uri.toFile()
        if (file.exists()) {
            file.delete()
        }
    }
}

fun Context.deleteAllChatFiles() {
    val dir = this.filesDir.resolve("upload")
    if (dir.exists()) {
        dir.deleteRecursively()
    }
}

suspend fun Context.countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
    val dir = filesDir.resolve("upload")
    if (!dir.exists()) {
        return@withContext Pair(0, 0)
    }
    val files = dir.listFiles() ?: return@withContext Pair(0, 0)
    val count = files.size
    val size = files.sumOf { it.length() }
    Pair(count, size)
}

fun Context.getImagesDir(): File {
    val dir = this.filesDir.resolve("images")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}

fun Context.createImageFileFromBase64(base64Data: String, filePath: String): File {
    val data = if (base64Data.startsWith("data:image")) {
        base64Data.substringAfter("base64,")
    } else {
        base64Data
    }

    val byteArray = Base64.decode(data.toByteArray())
    val file = File(filePath)
    file.parentFile?.mkdirs()
    file.writeBytes(byteArray)
    return file
}

fun Context.listImageFiles(): List<File> {
    val imagesDir = getImagesDir()
    return imagesDir.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }?.toList()
        ?: emptyList()
}

enum class ObfuscationType {
    CYRILLIC_TO_LATIN,
    INVISIBLE_CHARS,
    HOMOGLYPHS
}

data class ObfuscationResult(
    val node: MessageNode,
    val changedIndices: List<Int> = emptyList()
)

private val ISO_9_MAP = mapOf(
    'А' to "A", 'а' to "a",
    'Б' to "B", 'б' to "b",
    'В' to "V", 'в' to "v",
    'Г' to "G", 'г' to "g",
    'Д' to "D", 'д' to "d",
    'Е' to "E", 'е' to "e",
    'Ё' to "Ë", 'ё' to "ë",
    'Ж' to "Ž", 'ж' to "ž",
    'З' to "Z", 'з' to "z",
    'И' to "I", 'и' to "i",
    'Й' to "J", 'й' to "j",
    'К' to "K", 'к' to "k",
    'Л' to "L", 'л' to "l",
    'М' to "M", 'м' to "m",
    'Н' to "N", 'н' to "n",
    'О' to "O", 'о' to "o",
    'П' to "P", 'п' to "p",
    'Р' to "R", 'р' to "r",
    'С' to "S", 'с' to "s",
    'Т' to "T", 'т' to "t",
    'У' to "U", 'у' to "u",
    'Ф' to "F", 'ф' to "f",
    'Х' to "H", 'х' to "h",
    'Ц' to "C", 'ц' to "c",
    'Ч' to "Č", 'ч' to "č",
    'Ш' to "Š", 'ш' to "š",
    'Щ' to "Ŝ", 'щ' to "ŝ",
    'Ъ' to "ʺ", 'ъ' to "ʺ",
    'Ы' to "Y", 'ы' to "y",
    'Ь' to "ʹ", 'ь' to "ʹ",
    'Э' to "È", 'э' to "è",
    'Ю' to "Û", 'ю' to "û",
    'Я' to "Â", 'я' to "â"
)

private val REVERSE_ISO_9_MAP = ISO_9_MAP.entries.associate { it.value to it.key.toString() }

fun obfuscateTextCyrillicToLatin(text: String): String {
    // Если есть кириллица — транслитерируем в латиницу
    val hasCyrillic = text.any { it in ISO_9_MAP.keys }
    if (hasCyrillic) {
        return text.map { ISO_9_MAP[it] ?: it.toString() }.joinToString("")
    }
    // Если кириллицы нет — пытаемся вернуть из латиницы ISO 9 обратно в кириллицу
    var result = text
    // Сортируем ключи по длине, чтобы сначала заменять длинные последовательности (если они есть)
    REVERSE_ISO_9_MAP.keys.sortedByDescending { it.length }.forEach { latin ->
        if (result.contains(latin)) {
            result = result.replace(latin, REVERSE_ISO_9_MAP[latin]!!)
        }
    }
    return result
}

private val HOMOGLYPH_MAP = mapOf(
    'А' to listOf('A', 'Α'),
    'Б' to listOf('Ƃ'),
    'б' to listOf('ნ'),
    'В' to listOf('B', 'Β', 'Ⲃ'),
    'Г' to listOf('Γ', 'ᒥ'),
    'г' to listOf('ᴦ'),
    'Е' to listOf('E', 'Ε', 'ⴹ'),
    'Ё' to listOf('Ë'),
    'З' to listOf('3'),
    'з' to listOf('ɜ'),
    'И' to listOf('Ͷ'),
    'К' to listOf('Ⲕ', 'K', 'Κ', 'Ƙ'),
    'М' to listOf('Ｍ', 'M', 'Μ', 'Ϻ'),
    'м' to listOf('ᴍ'),
    'Н' to listOf('ᕼ', 'H', 'Η', 'Ⲏ'),
    'О' to listOf('ᱛ', 'O', 'Օ', 'Ο', 'ⵔ', 'Ⲟ', '೦'),
    'о' to listOf('օ', 'ο', 'o', 'ᴏ', '௦', 'ഠ', 'ⲟ'),
    'П' to listOf('Π', '∏', 'Ⲡ'),
    'Р' to listOf('Ρ', 'P', 'Ⲣ'),
    'С' to listOf('Ⅽ', 'C', 'Ϲ', 'Ⲥ', 'Ꮯ'),
    'с' to listOf('ᴄ', 'c', 'ϲ', 'ⅽ'),
    'Т' to listOf('T', 'Τ'),
    'У' to listOf('Ꭹ'),
    'Ф' to listOf('Φ', 'Փ'),
    'Х' to listOf('Ⅹ', 'Χ', 'X'),
    'я' to listOf('ᴙ'),
)

private val REVERSE_HOMOGLYPH_MAP = HOMOGLYPH_MAP.flatMap { (k, v) -> v.map { it to k } }.toMap()

fun obfuscateTextHomoglyphs(text: String): Pair<String, List<Int>> {
    val changedIndices = mutableListOf<Int>()
    val hasHomoglyphs = text.any { it in REVERSE_HOMOGLYPH_MAP }
    if (hasHomoglyphs) {
        // При обратном преобразовании индексы не возвращаем (анимация не нужна)
        return text.map { REVERSE_HOMOGLYPH_MAP[it] ?: it }.joinToString("") to emptyList()
    }
    val sb = StringBuilder()
    var currentOffset = 0
    val tokens = text.split(Regex("(?<=\\s)|(?=\\s)"))
    tokens.forEach { token ->
        if (token.isBlank()) {
            sb.append(token)
            currentOffset += token.length
        } else {
            val letterCount = token.count { it.isLetter() }
            if (letterCount < 2) {
                sb.append(token)
                currentOffset += token.length
            } else {
                val candidates = token.withIndex().filter { it.value in HOMOGLYPH_MAP }
                if (candidates.isEmpty()) {
                    sb.append(token)
                    currentOffset += token.length
                } else {
                    val target = candidates.random()
                    val homoglyph = HOMOGLYPH_MAP[target.value]!!.random()
                    // Запоминаем глобальный индекс изменённого символа
                    changedIndices.add(currentOffset + target.index)
                    val tokenSb = StringBuilder(token)
                    tokenSb.setCharAt(target.index, homoglyph)
                    val resultToken = tokenSb.toString()
                    sb.append(resultToken)
                    currentOffset += resultToken.length
                }
            }
        }
    }
    return sb.toString() to changedIndices
}

fun obfuscateTextInvisible(text: String): String {
    val invisibleChar = "\u200B"
    if (text.contains(invisibleChar)) {
        return text.replace(invisibleChar, "")
    }
    // Регулярка для разделения на токены (слова и пробелы)
    val tokens = text.split(Regex("(?<=\\s)|(?=\\s)"))
    return tokens.joinToString("") { token ->
        if (token.isBlank() || token.length < 2) {
            token
        } else {
            // Ищем подходящие места между буквами
            val validSlots = mutableListOf<Int>()
            for (i in 0 until token.length - 1) {
                if (token[i].isLetter() && token[i + 1].isLetter()) {
                    validSlots.add(i + 1)
                }
            }
            if (validSlots.isEmpty()) {
                token
            } else {
                val insertAt = validSlots.random()
                StringBuilder(token).insert(insertAt, invisibleChar).toString()
            }
        }
    }
}

/**
 * Обфусцировать текущее сообщение в узле.
 */
fun MessageNode.obfuscate(type: ObfuscationType): ObfuscationResult {
    var allChangedIndices = emptyList<Int>()
    val currentMessage = this.currentMessage
    val newParts = currentMessage.parts.map { part ->
        if (part is UIMessagePart.Text) {
            when (type) {
                ObfuscationType.INVISIBLE_CHARS -> part.copy(text = obfuscateTextInvisible(part.text))
                ObfuscationType.CYRILLIC_TO_LATIN -> part.copy(text = obfuscateTextCyrillicToLatin(part.text))
                ObfuscationType.HOMOGLYPHS -> {
                    val (newText, indices) = obfuscateTextHomoglyphs(part.text)
                    allChangedIndices = indices
                    part.copy(text = newText)
                }
            }
        } else part
    }
    val newMessage = currentMessage.copy(parts = newParts)
    val newMessages = messages.toMutableList().apply {
        set(selectIndex, newMessage)
    }
    return ObfuscationResult(
        node = copy(messages = newMessages),
        changedIndices = allChangedIndices
    )
}
