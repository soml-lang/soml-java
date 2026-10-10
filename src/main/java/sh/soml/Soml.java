package sh.soml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
Reads and writes SOML with the default options.

<pre>{@code
record Config(String name, int port, Duration timeout) {}

var config = Soml.decode("""
	name: 'api-gateway'
	port: 8080
	timeout: 30s
	""", Config.class);

Soml.encode(config);
//=> "name: 'api-gateway'\nport: 8080\ntimeout: 30s\n"
}</pre>

<p>Use {@link SomlMapper} for options, such as {@link KeyNaming#KEBAB_CASE} or rejecting unknown keys. It also describes how Java types map to SOML.

<p>Every method throws the unchecked {@link SomlException} for a document that is not valid, with the line and column of the error, and for a value that does not fit, with its path. The methods that read a {@link Path} or a stream, or write to a stream, also throw {@link IOException}. They do not close a stream that they are given.
*/
public final class Soml {
	private Soml() {}

	/**
	Parses a document to a value tree, which is always a {@link SomlObject} or a {@link SomlArray}, because a document is always a collection.

	@throws SomlException When the document is not valid.
	*/
	public static SomlValue parse(String text) {
		return Parser.parse(text);
	}

	/**
	Parses a document of UTF-8 bytes to a value tree. Invalid UTF-8 is an error, never replaced.

	@throws SomlException When the bytes are not valid UTF-8 or the document is not valid.
	*/
	public static SomlValue parse(byte[] utf8) {
		return Parser.parse(Parser.decode(utf8));
	}

	/**
	Parses a file to a value tree.

	@throws IOException When the file cannot be read.
	@throws SomlException When the file is not valid UTF-8 or the document is not valid.
	*/
	public static SomlValue parse(Path file) throws IOException {
		return parse(Files.readAllBytes(file));
	}

	/**
	Parses a stream of UTF-8 bytes to a value tree. It reads the stream to its end, and does not close it.

	@throws IOException When the stream cannot be read.
	@throws SomlException When the bytes are not valid UTF-8 or the document is not valid.
	*/
	public static SomlValue parse(InputStream input) throws IOException {
		return parse(input.readAllBytes());
	}

	/**
	Parses the text of a reader to a value tree. It reads to the end, and does not close the reader.

	@throws IOException When the reader cannot be read.
	@throws SomlException When the document is not valid.
	*/
	public static SomlValue parse(Reader reader) throws IOException {
		return parse(reader.readAllAsString());
	}

	/**
	Formats a document: normalizes its layout and keeps everything else, as the spec's formatter rules say. The result ends with one line feed.

	<p>One tab per level, every member and item on its own line with no commas, at most one blank line in a row, and no trailing spaces or tabs. An object or an array whose brackets are on one line stays on one line, as in {@code ports: [80, 443]}. To give it one member or item per line, put a line break anywhere inside it. Comments, member order, block strings, and the spelling of every value stay as they are, so the value never changes, and formatting a formatted document gives the same text.

	<pre>{@code
	Soml.format("pool: {min: 2,   max: 16,} # Connections");
	//=> "pool: {min: 2, max: 16} # Connections\n"
	}</pre>

	@throws SomlException When the document is not valid.
	*/
	public static String format(String text) {
		return Formatter.format(text);
	}

	/**
	Decodes a document to {@code type}, such as a record.

	@throws SomlException When the document is not valid, or does not fit {@code type}.
	*/
	public static <T> T decode(String text, Class<T> type) {
		return SomlMapper.DEFAULT.decode(text, type);
	}

	/**
	Decodes a document to a generic type, such as {@code List<Server>}.

	@throws SomlException When the document is not valid, or does not fit the type.
	*/
	public static <T> T decode(String text, TypeReference<T> type) {
		return SomlMapper.DEFAULT.decode(text, type);
	}

	/**
	Decodes a document of UTF-8 bytes to {@code type}.

	@throws SomlException When the bytes are not valid UTF-8, the document is not valid, or it does not fit {@code type}.
	*/
	public static <T> T decode(byte[] utf8, Class<T> type) {
		return SomlMapper.DEFAULT.decode(utf8, type);
	}

	/**
	Decodes a document of UTF-8 bytes to a generic type.

	@throws SomlException When the bytes are not valid UTF-8, the document is not valid, or it does not fit the type.
	*/
	public static <T> T decode(byte[] utf8, TypeReference<T> type) {
		return SomlMapper.DEFAULT.decode(utf8, type);
	}

	/**
	Decodes a file to {@code type}.

	@throws IOException When the file cannot be read.
	@throws SomlException When the file is not valid UTF-8, the document is not valid, or it does not fit {@code type}.
	*/
	public static <T> T decode(Path file, Class<T> type) throws IOException {
		return SomlMapper.DEFAULT.decode(file, type);
	}

	/**
	Decodes a file to a generic type.

	@throws IOException When the file cannot be read.
	@throws SomlException When the file is not valid UTF-8, the document is not valid, or it does not fit the type.
	*/
	public static <T> T decode(Path file, TypeReference<T> type) throws IOException {
		return SomlMapper.DEFAULT.decode(file, type);
	}

	/**
	Decodes a stream of UTF-8 bytes to {@code type}. It reads the stream to its end, and does not close it.

	@throws IOException When the stream cannot be read.
	@throws SomlException When the bytes are not valid UTF-8, the document is not valid, or it does not fit {@code type}.
	*/
	public static <T> T decode(InputStream input, Class<T> type) throws IOException {
		return SomlMapper.DEFAULT.decode(input, type);
	}

	/**
	Decodes a stream of UTF-8 bytes to a generic type. It reads the stream to its end, and does not close it.

	@throws IOException When the stream cannot be read.
	@throws SomlException When the bytes are not valid UTF-8, the document is not valid, or it does not fit the type.
	*/
	public static <T> T decode(InputStream input, TypeReference<T> type) throws IOException {
		return SomlMapper.DEFAULT.decode(input, type);
	}

	/**
	Decodes the text of a reader to {@code type}. It reads to the end, and does not close the reader.

	@throws IOException When the reader cannot be read.
	@throws SomlException When the document is not valid, or does not fit {@code type}.
	*/
	public static <T> T decode(Reader reader, Class<T> type) throws IOException {
		return SomlMapper.DEFAULT.decode(reader, type);
	}

	/**
	Decodes the text of a reader to a generic type. It reads to the end, and does not close the reader.

	@throws IOException When the reader cannot be read.
	@throws SomlException When the document is not valid, or does not fit the type.
	*/
	public static <T> T decode(Reader reader, TypeReference<T> type) throws IOException {
		return SomlMapper.DEFAULT.decode(reader, type);
	}

	/**
	Encodes an object, such as a record, a map, a list, or a {@link SomlValue}, as a document. Members keep the order of the object, which reads best in a file for people. Every member and item is on its own line, nesting is indented with tabs, and the text ends with a line feed.

	@throws SomlException When the value is not written as an object or an array, such as a string, a number, or null, because a document is a collection, or when a value in it cannot be represented, such as NaN.
	*/
	public static String encode(@Nullable Object value) {
		return SomlMapper.DEFAULT.encode(value);
	}

	/**
	Encodes an object as a document, as {@link #encode(Object)} does, to a writer. It does not flush or close the writer.

	@throws IOException When the writer cannot be written to.
	@throws SomlException When the value is not written as an object or an array, or when a value in it cannot be represented. Then nothing is written.
	*/
	public static void encode(@Nullable Object value, Writer writer) throws IOException {
		SomlMapper.DEFAULT.encode(value, writer);
	}

	/**
	Encodes an object as a document, as {@link #encode(Object)} does, to a stream as UTF-8. It does not flush or close the stream.

	@throws IOException When the stream cannot be written to.
	@throws SomlException When the value is not written as an object or an array, or when a value in it cannot be represented. Then nothing is written.
	*/
	public static void encode(@Nullable Object value, OutputStream output) throws IOException {
		SomlMapper.DEFAULT.encode(value, output);
	}

	/**
	Encodes an object as a document in canonical form, with the members of every object sorted by key, so the same value always gives the same text, for hashing, signing, and comparing.

	@throws SomlException When the value is not written as an object or an array, such as a string, a number, or null, because a document is a collection, or when a value in it cannot be represented.
	*/
	public static String encodeCanonical(@Nullable Object value) {
		return SomlMapper.DEFAULT.encodeCanonical(value);
	}

	/**
	Converts a Java object to a value tree.

	@throws SomlException When a value in the object cannot be represented.
	*/
	public static SomlValue toValue(@Nullable Object value) {
		return SomlMapper.DEFAULT.toValue(value);
	}

	/**
	Converts a value tree to {@code type}. {@link SomlNull} is {@code null}, or an empty {@code Optional} for an {@code Optional} type.

	@throws SomlException When the value does not fit {@code type}.
	*/
	public static <T> @Nullable T fromValue(SomlValue value, Class<T> type) {
		return SomlMapper.DEFAULT.fromValue(value, type);
	}

	/**
	Converts a value tree to a generic type. {@link SomlNull} is {@code null}, or an empty {@code Optional} for an {@code Optional} type.

	@throws SomlException When the value does not fit the type.
	*/
	public static <T> @Nullable T fromValue(SomlValue value, TypeReference<T> type) {
		return SomlMapper.DEFAULT.fromValue(value, type);
	}
}
