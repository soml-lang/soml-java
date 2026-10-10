package sh.soml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
Decodes SOML into Java objects and encodes Java objects as SOML, with options.

<p>Use {@link Soml} for the default options. Make a mapper once and reuse it: it is immutable and safe to use from many threads, and it caches what it learns about each type.

<pre>{@code
var mapper = SomlMapper.builder()
	.keyNaming(KeyNaming.KEBAB_CASE)
	.rejectUnknownKeys(true)
	.build();

var config = mapper.decode(Path.of("config.soml"), Config.class);
}</pre>

<h2>Types</h2>

<table>
<caption>How SOML types map to Java types</caption>
<tr><th>SOML</th><th>Java</th></tr>
<tr><td>string</td><td>{@code String}, {@code char}, an enum, {@code UUID}, {@code URI}, {@code Path}, {@code Locale} as a language tag, {@code ZoneId}, {@code ZoneOffset}</td></tr>
<tr><td>int</td><td>{@code long}, {@code int}, {@code short}, {@code byte}, {@code BigInteger} (in the 64-bit range of an int), and {@code double} or {@code float} when it is exact</td></tr>
<tr><td>float</td><td>{@code double}, {@code float}</td></tr>
<tr><td>bool</td><td>{@code boolean}</td></tr>
<tr><td>null</td><td>{@code null}, an empty {@code Optional}</td></tr>
<tr><td>date</td><td>{@code LocalDate}, in the proleptic Gregorian calendar, years 0001 to 9999, without a timezone or time of day. A quoted date stays a string.</td></tr>
<tr><td>instant</td><td>{@code Instant}, {@code OffsetDateTime} and {@code ZonedDateTime} in UTC</td></tr>
<tr><td>duration</td><td>{@code Duration}</td></tr>
<tr><td>array</td><td>{@code List}, {@code Set}, {@code SortedSet}, a {@code Collection} class with a constructor without parameters, an array</td></tr>
<tr><td>object</td><td>a record, a class, a sealed interface or abstract class, {@code Map}, {@code SortedMap}</td></tr>
<tr><td>any value</td><td>{@code SomlValue}, or {@code Object} for {@code String}, {@code Long}, {@code Double}, {@code Boolean}, {@code null}, {@code LocalDate}, {@code Instant}, {@code Duration}, {@code List}, and {@code Map}</td></tr>
</table>

<h2>Records and classes</h2>

<ul>
<li>A record is created with its canonical constructor, so its validation runs. A member that the document leaves out is an empty {@code Optional} for an {@code Optional} component, {@code null} for a component annotated with any annotation named {@code Nullable}, such as JSpecify's, and otherwise an error.</li>
<li>A class needs a constructor without parameters, and its fields are set one by one, so a field that the document leaves out keeps its initial value, which makes field initializers defaults. Static and {@code transient} fields, and fields annotated with {@link SomlIgnore}, are left out.</li>
<li>A key is the Java name, unless {@link SomlName} or {@link KeyNaming} says otherwise.</li>
<li>When encoding, a member with an empty {@code Optional} is left out, and a member that is {@code null} is written as {@code null}.</li>
<li>The decoder creates records and calls adapters while it parses, so it can create some of them for a document that a later error makes it refuse, and create them again, with the other records of the document, to report the values that do not fit. Keep constructors and adapters free of side effects.</li>
<li>A record or a class in a named module must be open to {@code sh.soml}, as in {@code opens com.example.config to sh.soml;}.</li>
<li>A sealed interface or abstract class is an object whose {@code type} member names the subtype to create: its simple name, or the name in {@link SomlName} on it. A record or an ordinary final class whose direct supertype is sealed is written with this member first, except in canonical form where all keys are sorted. Map and collection subclasses are not supported as sealed variants; wrap the container in a record or register an adapter for the sealed type. Only the permitted subtypes can be created.</li>
</ul>
*/
public final class SomlMapper {
	static final SomlMapper DEFAULT = builder().build();

	private final Binding binding;
	private final int maxDocumentLength;

	private SomlMapper(Builder builder) {
		this.binding = new Binding(builder.keyNaming, builder.rejectsUnknownKeys, builder.adapters);
		this.maxDocumentLength = builder.maxDocumentLength;
	}

	/**
	A builder with the default options.
	*/
	public static Builder builder() {
		return new Builder();
	}

	/**
	Decodes a document to {@code type}.

	@throws SomlException When the document is not valid, or does not fit {@code type}. The exception has the path, the line, and the column of the first value that does not fit, and {@link SomlException#errors()} has the others.
	*/
	@SuppressWarnings("unchecked")
	public <T> T decode(String text, Class<T> type) {
		return (T) decodeText(text, type);
	}

	/**
	Decodes a document to a generic type, such as {@code List<Server>}.

	@throws SomlException When the document is not valid, or does not fit the type.
	*/
	@SuppressWarnings("unchecked")
	public <T> T decode(String text, TypeReference<T> type) {
		return (T) decodeText(text, type.type());
	}

	/**
	Decodes a document of UTF-8 bytes to {@code type}.

	@throws SomlException When the bytes are not valid UTF-8, the document is not valid, or it does not fit {@code type}.
	*/
	public <T> T decode(byte[] utf8, Class<T> type) {
		return decode(read(utf8), type);
	}

	/**
	Decodes a document of UTF-8 bytes to a generic type.

	@throws SomlException When the bytes are not valid UTF-8, the document is not valid, or it does not fit the type.
	*/
	public <T> T decode(byte[] utf8, TypeReference<T> type) {
		return decode(read(utf8), type);
	}

	/**
	Decodes a file to {@code type}.

	@throws IOException When the file cannot be read.
	@throws SomlException When the file is not valid UTF-8, the document is not valid, or it does not fit {@code type}.
	*/
	public <T> T decode(Path file, Class<T> type) throws IOException {
		try (var input = Files.newInputStream(file)) {
			return decode(input, type);
		}
	}

	/**
	Decodes a file to a generic type.

	@throws IOException When the file cannot be read.
	@throws SomlException When the file is not valid UTF-8, the document is not valid, or it does not fit the type.
	*/
	public <T> T decode(Path file, TypeReference<T> type) throws IOException {
		try (var input = Files.newInputStream(file)) {
			return decode(input, type);
		}
	}

	/**
	Decodes a document of UTF-8 bytes from a stream to {@code type}. It reads the stream to its end, and does not close it.

	@throws IOException When the stream cannot be read.
	@throws SomlException When the bytes are not valid UTF-8, the document is not valid, or it does not fit {@code type}.
	*/
	public <T> T decode(InputStream input, Class<T> type) throws IOException {
		return decode(read(input), type);
	}

	/**
	Decodes a document of UTF-8 bytes from a stream to a generic type. It reads the stream to its end, and does not close it.

	@throws IOException When the stream cannot be read.
	@throws SomlException When the bytes are not valid UTF-8, the document is not valid, or it does not fit the type.
	*/
	public <T> T decode(InputStream input, TypeReference<T> type) throws IOException {
		return decode(read(input), type);
	}

	/**
	Decodes a document from a reader to {@code type}. It reads to the end, and does not close the reader.

	@throws IOException When the reader cannot be read.
	@throws SomlException When the document is not valid, or does not fit {@code type}.
	*/
	public <T> T decode(Reader reader, Class<T> type) throws IOException {
		return decode(read(reader), type);
	}

	/**
	Decodes a document from a reader to a generic type. It reads to the end, and does not close the reader.

	@throws IOException When the reader cannot be read.
	@throws SomlException When the document is not valid, or does not fit the type.
	*/
	public <T> T decode(Reader reader, TypeReference<T> type) throws IOException {
		return decode(read(reader), type);
	}

	/**
	Converts a value tree to {@code type}, as {@link #decode(String, Class)} does with a document. {@link SomlNull} is {@code null}, or an empty {@code Optional} for an {@code Optional} type.

	@throws SomlException When the value does not fit {@code type}. The exception has the path, and no position.
	*/
	@SuppressWarnings("unchecked")
	public <T> @Nullable T fromValue(SomlValue value, Class<T> type) {
		return (T) fromValue(value, (Type) type);
	}

	/**
	Converts a value tree to a generic type. {@link SomlNull} is {@code null}, or an empty {@code Optional} for an {@code Optional} type.

	@throws SomlException When the value does not fit the type.
	*/
	@SuppressWarnings("unchecked")
	public <T> @Nullable T fromValue(SomlValue value, TypeReference<T> type) {
		return (T) fromValue(value, type.type());
	}

	private @Nullable Object fromValue(SomlValue value, Type type) {
		Objects.requireNonNull(value, "value");
		Objects.requireNonNull(type, "type");

		try {
			return binding.decode(value, type);
		} catch (Binding.BindingError error) {
			throw toException(error, null);
		}
	}

	/**
	Converts a Java object to a value tree. A {@link SomlValue} is returned as it is unless an adapter overrides it, and {@code null} is {@link SomlNull}.

	@throws SomlException When the object, or a value in it, cannot be represented, such as NaN, a date or instant outside the years 0001 to 9999, or nesting deeper than 100 levels. The exception has the path to the value.
	*/
	public SomlValue toValue(@Nullable Object value) {
		try {
			return binding.encode(value);
		} catch (Binding.BindingError error) {
			throw toException(error, null);
		}
	}

	/**
	Encodes an object as a document, with members in the order of the object: a record's components and a class's fields in the order they are declared, and a map's entries in the order it iterates in. Every member and item is on its own line, nesting is indented with tabs, and the text ends with a line feed.

	@throws SomlException When the value is not written as an object or an array, such as a string, a number, or null, because a document is a collection, or when a value in it cannot be represented.
	*/
	public String encode(@Nullable Object value) {
		return encodeDocument(value, false);
	}

	/**
	Encodes an object as a document, as {@link #encode(Object)} does, to a writer. It does not flush or close the writer.

	@throws IOException When the writer cannot be written to.
	@throws SomlException When the value is not written as an object or an array, or when a value in it cannot be represented. Then nothing is written.
	*/
	public void encode(@Nullable Object value, Writer writer) throws IOException {
		try {
			binding.encodeDocument(value, writer);
		} catch (Binding.BindingError error) {
			throw toException(error, null);
		}
	}

	/**
	Encodes an object as a document, as {@link #encode(Object)} does, to a stream as UTF-8. It does not flush or close the stream.

	@throws IOException When the stream cannot be written to.
	@throws SomlException When the value is not written as an object or an array, or when a value in it cannot be represented. Then nothing is written.
	*/
	public void encode(@Nullable Object value, OutputStream output) throws IOException {
		output.write(encode(value).getBytes(StandardCharsets.UTF_8));
	}

	/**
	Encodes an object as a document in canonical form, with the members of every object sorted by key, so the same value always gives the same text, for hashing, signing, and comparing.

	@throws SomlException When the value is not written as an object or an array, such as a string, a number, or null, because a document is a collection, or when a value in it cannot be represented.
	*/
	public String encodeCanonical(@Nullable Object value) {
		return encodeDocument(value, true);
	}

	private String encodeDocument(@Nullable Object value, boolean isCanonical) {
		try {
			return binding.encodeDocument(value, isCanonical);
		} catch (Binding.BindingError error) {
			throw toException(error, null);
		}
	}

	/*
	The text of UTF-8 bytes, which are refused when there are more than the limit.
	*/
	private String read(byte[] utf8) {
		Objects.requireNonNull(utf8, "utf8");
		checkLength(utf8.length, "bytes");
		return Parser.decode(utf8);
	}

	/*
	The text of a stream of UTF-8 bytes. With a limit, it reads one byte past it at most, so a long stream is refused without reading it in full.
	*/
	private String read(InputStream input) throws IOException {
		Objects.requireNonNull(input, "input");
		return read(maxDocumentLength == Integer.MAX_VALUE ? input.readAllBytes() : input.readNBytes(maxDocumentLength + 1));
	}

	private String read(Reader reader) throws IOException {
		Objects.requireNonNull(reader, "reader");

		if (maxDocumentLength == Integer.MAX_VALUE) {
			return reader.readAllAsString();
		}

		var buffer = new char[Math.min(8192, maxDocumentLength + 1)];
		var text = new StringBuilder(buffer.length);

		for (int count; (count = reader.read(buffer, 0, Math.min(buffer.length, maxDocumentLength - text.length() + 1))) != -1;) {
			checkLength(text.length() + count, "characters");
			text.append(buffer, 0, count);
		}

		return text.toString();
	}

	private void checkLength(int length, String unit) {
		if (length > maxDocumentLength) {
			throw new SomlException("The document is longer than the limit of " + maxDocumentLength + " " + unit);
		}
	}

	private Object decodeText(String text, Type type) {
		Objects.requireNonNull(text, "text");
		Objects.requireNonNull(type, "type");
		checkLength(text.length(), "characters");

		// The fast path decodes while it parses. When the document does not fit, it decodes again from a tree, which checks the whole document first, so a syntax error later in it is reported before the value that does not fit, with that value's position.
		// A document is an object or an array, and a codec decodes a value that is not null to an object, which an adapter must return too.
		try {
			return Objects.requireNonNull(binding.decodeWhileParsing(text, type));
		} catch (Binding.BindingError _) {
			// Reported below.
		}

		var located = Parser.parseWithPositions(text);

		try {
			return Objects.requireNonNull(binding.decode(located.value(), type));
		} catch (Binding.BindingError error) {
			throw toException(error, located);
		}
	}

	/*
	The errors with their positions in the tree decoded after the fast path failed, once for them all, so that a document that fits pays nothing for them.
	*/
	private static SomlException toException(Binding.BindingError error, Parser.@Nullable Located located) {
		if (error.others == null) {
			return exceptionFor(error, located);
		}

		var exceptions = new ArrayList<SomlException>(error.others.size() + 1);
		exceptions.add(exceptionFor(error, located));

		for (var each : error.others) {
			exceptions.add(exceptionFor(each, located));
		}

		return SomlException.of(exceptions, error.isStopped);
	}

	private static SomlException exceptionFor(Binding.BindingError error, Parser.@Nullable Located located) {
		var path = List.copyOf(error.path);
		return located == null ? SomlException.atPath(error.reason, path, error.getCause()) : SomlException.at(error.reason, located.source(), locate(located, path, error.isAtKey), path, error.getCause());
	}

	private static int locate(Parser.Located located, List<Object> path, boolean isAtKey) {
		var offset = located.documentStart();
		var current = located.value();

		for (var index = 0; index < path.size(); index++) {
			var offsets = located.positions().get(current);
			var isLast = index == path.size() - 1;
			var component = path.get(index);

			if (offsets == null) {
				break;
			}

			if (current instanceof SomlObject object && component instanceof String key) {
				var memberIndex = object.indexOfKey(key);

				if (memberIndex == -1) {
					break;
				}

				// The offsets are a key and a value per member, and an error about a key, such as an unknown key, points to the key.
				offset = offsets[(memberIndex * 2) + (isLast && isAtKey ? 0 : 1)];
				current = object.valueAt(memberIndex);
			} else if (current instanceof SomlArray(var items) && component instanceof Integer itemIndex && itemIndex < items.size()) {
				offset = offsets[itemIndex];
				current = items.get(itemIndex);
			} else {
				break;
			}
		}

		return offset;
	}

	/**
	Options for a {@link SomlMapper}.
	*/
	public static final class Builder {
		private KeyNaming keyNaming = KeyNaming.JAVA_NAME;
		private boolean rejectsUnknownKeys;
		private int maxDocumentLength = Integer.MAX_VALUE;
		private Map<Class<?>, SomlAdapter<?>> adapters = Map.of();

		private Builder() {}

		/**
		How the names of record components and fields become keys. The default is {@link KeyNaming#JAVA_NAME}.
		*/
		public Builder keyNaming(KeyNaming keyNaming) {
			this.keyNaming = Objects.requireNonNull(keyNaming, "keyNaming");
			return this;
		}

		/**
		Whether a key that no record component or field has is an error, which catches a typo such as {@code prot: 80}. By default, such a key is ignored. A {@code Map} takes every key either way.
		*/
		public Builder rejectUnknownKeys(boolean rejectsUnknownKeys) {
			this.rejectsUnknownKeys = rejectsUnknownKeys;
			return this;
		}

		/**
		The longest document that the mapper decodes, for a document from a source that is not trusted, which could otherwise take as much memory as it wants. The limit is in bytes for UTF-8 input, as a {@code byte[]}, a {@link Path}, or an {@link InputStream}, and in characters for a {@code String} or a {@link Reader}. A stream or reader is read at most one byte or character past the limit. By default, there is no limit. To parse such a document without a type, decode it to {@link SomlValue}.

		<p>The nesting limit of 100 levels keeps the parser's stack small. This length limit bounds input storage; conversion costs also depend on the requested Java types and adapters, such as arbitrary-precision map keys. Keep adapters and constructors bounded when decoding untrusted input.

		@throws IllegalArgumentException When {@code maxDocumentLength} is negative.
		*/
		public Builder maxDocumentLength(int maxDocumentLength) {
			if (maxDocumentLength < 0) {
				throw new IllegalArgumentException("The maximum document length cannot be negative, but it is " + maxDocumentLength);
			}

			this.maxDocumentLength = maxDocumentLength;
			return this;
		}

		/**
		Converts {@code type} with {@code adapter}, in place of the built-in conversion and of an adapter that the type declares itself. A primitive and its wrapper share one registration, and a later adapter for the same type replaces an earlier one.
		*/
		public <T> Builder adapter(Class<T> type, SomlAdapter<T> adapter) {
			Objects.requireNonNull(type, "type");
			Objects.requireNonNull(adapter, "adapter");

			if (adapters.isEmpty()) {
				adapters = new HashMap<>();
			}

			// An adapter for a primitive is for its wrapper too, because a field is decoded by its declared type and encoded by its value's class.
			// `MethodType` maps a primitive to its wrapper without a table. Any other type maps to itself.
			adapters.put(Binding.boxedType(type), adapter);
			return this;
		}

		/**
		Makes the mapper. The builder can be used again afterwards.
		*/
		public SomlMapper build() {
			return new SomlMapper(this);
		}
	}
}
