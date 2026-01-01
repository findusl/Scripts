#!/usr/bin/env kotlin
@file:DependsOn("com.hierynomus:sshj:0.38.0")

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.name
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient

var CSRF_TOKEN: String? = null
val HTTP_CLIENT = HttpClient.newBuilder()
	.followRedirects(HttpClient.Redirect.NORMAL)
	.build()
val KEY_STR = "pG58&Qj$?.d=(<.[v!kJkm_XNbsa6e.f"
val SFTP_HOST = requireNotNull(System.getenv("SFTP_WEBTREES_HOST")) { "Missing env var: SFTP_WEBTREES_HOST" }
val SFTP_USERNAME = requireNotNull(System.getenv("SFTP_WEBTREES_USERNAME")) { "Missing env var: SFTP_WEBTREES_USERNAME" }
val SFTP_PASSWORD = requireNotNull(System.getenv("SFTP_WEBTREES_PASSWORD")) { "Missing env var: SFTP_WEBTREES_PASSWORD" }
val WT_USER = "Sebastian"

val matriculaHtmlPageUrl = promptUserInput("Enter matricula url")
val description = promptUserInput("Enter event description")
val matriculaImageUrl = calculateMatriculaImageUrl(matriculaHtmlPageUrl)
val path = saveImageToDownloads(matriculaImageUrl, description)
uploadImage(path)
val sql = generateWebtreesSql(description, matriculaHtmlPageUrl)
copyToClipboardMac(sql)
println("Execute this sql (copied to clipboard:")
println("==========================")
println(sql)

// =========================
// Helpers (below main logic)
// =========================

fun calculateMatriculaImageUrl(matriculaHtmlPageUrl: String): String {
	val matriculaHtmlPageUri = URI.create(matriculaHtmlPageUrl)
	val imagePath = calculateImagePathAndStoreCsrf(matriculaHtmlPageUri)
	val ctrl = calculateCtrl(imagePath)
	val url = "https://img.data.matricula-online.eu$imagePath?csrf=$CSRF_TOKEN&ctrl=$ctrl"
	println("url = $url")
	return url
}

fun saveImageToDownloads(url: String, description: String): Path  {
	val request = HttpRequest.newBuilder()
		.uri(URI.create(url))
		.header("Cookie", "shared_csrftoken=$CSRF_TOKEN")
		.GET()
		.build()
	val response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray())
	require (response.statusCode() in 200..299) { "HTTP ${response.statusCode()} while loading image" }
	val bytes = response.body()
	val home = Path.of(System.getProperty("user.home"))
	val downloads = home.resolve("Downloads")
	val target = downloads.resolve("$description.jpeg")
	Files.write(target, bytes)
	println("Temp file written to: $target")
	return target
}

fun calculateImagePathAndStoreCsrf(matriculaHtmlPageUri: URI): String {
	val html = matriculaHtmlPageGetAndStoreCsrf(matriculaHtmlPageUri)
	val pageIndex = matriculaHtmlPageUri.query.substringAfter('=').toInt() - 1
	val imagePath = parseImagePathJsonArray(html, pageIndex)
	println("imagePath: $imagePath")
	return imagePath
}

/**
 * This is very specific json parsing for this specific array that only has paths in the array.
 * Very brittle.
 */
fun parseImagePathJsonArray(html: String, pageIndex: Int): String {
	return html.substringAfter("\"files\": [")
		.splitToSequence(',')
		.drop(pageIndex)
		.first()
		.substringBefore(']')
		.trim('"', ' ')
}

fun calculateCtrl(path: String): String {
	val message = "$path?csrf=$CSRF_TOKEN"
	val msgBytes = toAsciiBytes(message)
	val md5 = md5Bytes(msgBytes)

	val keyBytes = toAsciiBytes(KEY_STR)
	val cipher = aesEncryptBlock(keyBytes, md5)
	val ctrl = toHex(cipher)

	println("ctrl md5(hex): ${toHex(md5)} len: ${md5.size}")
	println("ctrl cipher: $ctrl len: ${cipher.size}")

	return ctrl
}

/** Only works on guaranteed ascii input */
fun toAsciiBytes(str: String): ByteArray =
	str.toByteArray(Charsets.US_ASCII)

fun toHex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)

fun md5Bytes(bytes: ByteArray): ByteArray {
	val md = MessageDigest.getInstance("MD5")
	val digest = md.digest(bytes)
	require(digest.size == 16) { "MD5 output not 16 bytes: ${digest.size}" }
	return digest
}

fun aesEncryptBlock(keyBytes: ByteArray, block16: ByteArray): ByteArray {
	val cipher = Cipher.getInstance("AES/ECB/NoPadding")
	val keySpec = SecretKeySpec(keyBytes, "AES")
	cipher.init(Cipher.ENCRYPT_MODE, keySpec)

	val out = cipher.doFinal(block16)
	require(out.size == 16) { "AES block output not 16 bytes: ${out.size}" }
	return out
}

fun matriculaHtmlPageGetAndStoreCsrf(url: URI): String {
	val req = HttpRequest.newBuilder().uri(url).GET().build()
	val resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString())
	require (resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()} for $url" }
	CSRF_TOKEN = resp.headers()
		.firstValue("set-cookie").orElseThrow()
		.substringAfter("shared_csrftoken=")
		.substringBefore(';')
	println("csrf-token: $CSRF_TOKEN")
	return resp.body()
}

fun promptUserInput(label: String): String {
	print("$label: ")
	val line = readlnOrNull()
	require(!line.isNullOrBlank()) { "Please enter a non-empty value." }
	return line
}

fun uploadImage(localPath: Path) {
	require(Files.isRegularFile(localPath)) { "Local path is not a file: $localPath" }

	val remoteName = localPath.name // upload directly into SFTP root with just the filename

	SSHClient().use { ssh ->
		ssh.loadKnownHosts()
		ssh.connect(SFTP_HOST)
		ssh.authPassword(SFTP_USERNAME, SFTP_PASSWORD)

		ssh.newSFTPClient().use { sftp: SFTPClient ->
			val existing = sftp.statExistence(remoteName)
			require(existing == null) { "Remote file already exists: $remoteName" }

			sftp.put(localPath.toString(), remoteName)
		}
		println("Uploaded $localPath -> $remoteName on $SFTP_HOST")
	}
}

fun generateWebtreesSql(titl: String, url: String): String {

	// Conservative on purpose – avoids quotes, backslashes, newlines
	val TITL_ALLOWED = Regex("""^[\p{L}\p{N} _\-\(\),.]+$""")
	val URL_ALLOWED  = Regex("""^[A-Za-z0-9:/?&=%#._\-+~@]+$""")

	validate(titl, TITL_ALLOWED, "TITL")
	validate(url, URL_ALLOWED, "URL")
	val path = "quellen/$titl.jpeg"

	return """
        START TRANSACTION;
		SET @nl := CHAR(10);

        -- CHAN date/time (computed in SQL)
        SET @now := NOW();

        SET @chan_date := CONCAT(
          LPAD(DAY(@now), 2, '0'), ' ',
          CASE MONTH(@now)
            WHEN 1  THEN 'JAN' WHEN 2  THEN 'FEB' WHEN 3  THEN 'MAR' WHEN 4  THEN 'APR'
            WHEN 5  THEN 'MAY' WHEN 6  THEN 'JUN' WHEN 7  THEN 'JUL' WHEN 8  THEN 'AUG'
            WHEN 9  THEN 'SEP' WHEN 10 THEN 'OCT' WHEN 11 THEN 'NOV' WHEN 12 THEN 'DEC'
          END,
          ' ',
          YEAR(@now)
        );

        SET @chan_time := DATE_FORMAT(@now, '%H:%i:%s');

        -- Next GM ids
        SELECT @gm_max := COALESCE(
          MAX(CAST(SUBSTRING(m_id, 3) AS UNSIGNED)),
          0
        )
        FROM wt_media
        WHERE m_id REGEXP '^GM[0-9]+$';

        SET @gm1 := CONCAT('GM', CAST(@gm_max + 1 AS UNSIGNED));
        SET @gm2 := CONCAT('GM', CAST(@gm_max + 2 AS UNSIGNED));

        -- Media 1: file
        INSERT INTO wt_media (m_id, m_file, m_gedcom)
        VALUES (
          @gm1,
          1,
          CONCAT(
            '0 @', @gm1, '@ OBJE', @nl,
            '1 FILE $path', @nl,
            '2 FORM JPG', @nl,
            '3 TYPE DOCUMENT', @nl,
            '1 CHAN', @nl,
            '2 DATE ', @chan_date, @nl,
            '3 TIME ', @chan_time, @nl,
            '2 _WT_USER $WT_USER'
          )
        );
		
		INSERT INTO wt_media_file (
		  m_id,
		  m_file,
		  multimedia_file_refn,
		  multimedia_format,
		  source_media_type,
		  descriptive_title
		)
		VALUES (
		  @gm1,
		  1,
		  '$path',
		  'JPG',
		  'DOCUMENT',
		  ''
		);

        -- Media 2: URL
        INSERT INTO wt_media (m_id, m_file, m_gedcom)
        VALUES (
          @gm2,
          1,
          CONCAT(
            '0 @', @gm2, '@ OBJE', @nl,
            '1 FILE $url', @nl,
            '2 FORM', @nl,
            '3 TYPE ELECTRONIC', @nl,
            '2 TITL $titl', @nl,
            '1 CHAN', @nl,
            '2 DATE ', @chan_date, @nl,
            '3 TIME ', @chan_time, @nl,
            '2 _WT_USER $WT_USER'
          )
        );

        -- Next GS id
        SELECT @gs_max := COALESCE(
          MAX(CAST(SUBSTRING(s_id, 3) AS UNSIGNED)),
          0
        )
        FROM wt_sources
        WHERE s_id REGEXP '^GS[0-9]+$';

        SET @gs1 := CONCAT('GS', CAST(@gs_max + 1 AS UNSIGNED));

        -- Source referencing both media
        INSERT INTO wt_sources (s_id, s_file, s_name, s_gedcom)
        VALUES (
          @gs1,
          1,
          '$titl',
          CONCAT(
            '0 @', @gs1, '@ SOUR', @nl,
            '1 TITL $titl', @nl,
            '1 CHAN', @nl,
            '2 DATE ', @chan_date, @nl,
            '3 TIME ', @chan_time, @nl,
            '2 _WT_USER $WT_USER', @nl,
            '1 OBJE @', @gm1, '@', @nl,
            '1 OBJE @', @gm2, '@'
          )
        );
		
		INSERT INTO wt_link (l_file, l_from, l_type, l_to)
		VALUES
		  (1, @gs1, 'OBJE', @gm1),
		  (1, @gs1, 'OBJE', @gm2);

        SELECT @gm1 AS media1, @gm2 AS media2, @gs1 AS source;

        COMMIT;
    """.trimIndent()
}

private fun validate(input: String, allowed: Regex, name: String) {
	require(input.isNotBlank()) { "$name is blank" }
	require(input.none { it.isISOControl() }) { "$name contains control characters" }
	require(allowed.matches(input)) { "$name contains unsupported characters" }
}

fun copyToClipboardMac(text: String) {
	val process = ProcessBuilder("pbcopy")
		.redirectError(ProcessBuilder.Redirect.INHERIT)
		.start()

	process.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
	process.waitFor()
}
