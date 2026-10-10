package sh.soml;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/*
Writes a value tree as text: one member or item per line, tab indentation, no commas, and members in their own order or, in canonical form, sorted by key. Every value is a `SomlValue`, which already holds only what the format can represent, so the only error left is nesting deeper than the limit.
*/
final class Writer {
	static final int MAX_DEPTH = 100;

	private final TextBuffer output;
	private final boolean isCanonical;
	private final int maxDepth;
	// The indentation of the line a value starts on, before its own tabs, for a value that an edit writes into a document.
	private final String baseIndent;
	// Whether containers are written on one line, as in `[1, {a: 2}]`, for a value that an edit writes into a container on one line.
	private final boolean isOneLine;

	private Writer(boolean isCanonical, int maxDepth, String baseIndent, boolean isOneLine) {
		this(new TextBuffer(64), isCanonical, maxDepth, baseIndent, isOneLine);
	}

	private Writer(TextBuffer output, boolean isCanonical, int maxDepth, String baseIndent, boolean isOneLine) {
		this.output = output;
		this.isCanonical = isCanonical;
		this.maxDepth = maxDepth;
		this.baseIndent = baseIndent;
		this.isOneLine = isOneLine;
	}

	/*
	A whole document, which is an object or an array, ending with one line feed, written into `output`, which is empty.

	Renders a complete document before a caller copies the buffer to a string or a stream.
	*/
	static void writeDocumentInto(TextBuffer output, SomlValue value, boolean isCanonical) {
		var empty = emptyContainer(value);

		if (empty != null) {
			output.append(empty).append('\n');
			return;
		}

		var writer = new Writer(output, isCanonical, MAX_DEPTH, "", false);

		try {
			switch (value) {
				case SomlObject object when !object.members().isEmpty() -> {
					// A brace-less object needs at least one entry, so an empty one keeps its braces, like an array.
					writer.writeMembers(object, 2, 0);
				}
				case SomlObject _, SomlArray _ -> {
					writer.writeValue(value, 1, 0);
					writer.output.append('\n');
				}
				default -> throw new SomlException("The top-level value must be an object or an array, because a document is a collection. Got " + describeType(value));
			}
		} catch (Binding.BindingError error) {
			throw SomlException.atPath(error.reason, List.copyOf(error.path), null);
		}
	}

	/*
	One value as it is written in a document, without a line feed at the end, for `toString()`, which does not check the nesting limit, so that it can show a value that is too deep to encode. A tree nested thousands of levels deep, which only code can build, overflows the stack, as its `equals()` and `hashCode()` do.
	*/
	static String writeValue(SomlValue value) {
		var empty = emptyContainer(value);

		if (empty != null) {
			return empty;
		}

		var writer = new Writer(true, Integer.MAX_VALUE, "", false);
		writer.writeValue(value, 1, 0);
		return writer.output.toString();
	}

	/*
	One value as an edit writes it into a document: its own depth is `depth`, and the lines of a container after its first start with `indentation`, the indentation of the line it is on. With `isOneLine`, every container is on one line.

	@throws Binding.BindingError When the value would nest the document more than 100 levels deep.
	*/
	static String writeValueAt(SomlValue value, int depth, String indentation, boolean isOneLine) {
		// Scalars already own their spelling; indentation and container depth do not affect them.
		if (!(value instanceof SomlObject || value instanceof SomlArray)) {
			return value.toString();
		}

		var empty = emptyContainer(value);

		if (empty != null) {
			checkDepth(depth, MAX_DEPTH);
			return empty;
		}

		var writer = new Writer(false, MAX_DEPTH, indentation, isOneLine);
		writer.writeValue(value, depth, 0);
		return writer.output.toString();
	}

	/*
	Writes a value into `output` at `depth`, with the lines of a container after its first indented by `indent` tabs, for the encoder, which writes Java objects directly and meets a `SomlValue` among them.

	@throws Binding.BindingError When the value would nest the document more than 100 levels deep.
	*/
	static void writeValueInto(TextBuffer output, SomlValue value, int depth, int indent, boolean isCanonical) {
		switch (value) {
			case SomlObject _, SomlArray _ -> {
				var empty = emptyContainer(value);

				if (empty != null) {
					checkDepth(depth, MAX_DEPTH);
					output.append(empty);
				} else {
					new Writer(output, isCanonical, MAX_DEPTH, "", false).writeValue(value, depth, indent);
				}
			}
			default -> writeScalar(output, value);
		}
	}

	/*
	Writes canonical object members into an existing encoder buffer, including a sealed subtype's tag.
	*/
	static void writeMembersInto(TextBuffer output, SomlObject object, int depth, int indent) {
		new Writer(output, true, MAX_DEPTH, "", false).writeMembers(object, depth, indent);
	}

	private void appendIndent(int tabs) {
		output.append(baseIndent).repeat('\t', tabs);
	}

	private static @Nullable String emptyContainer(SomlValue value) {
		return switch (value) {
			case SomlObject object when object.memberCount() == 0 -> "{}";
			case SomlArray(var items) when items.isEmpty() -> "[]";
			default -> null;
		};
	}

	static String describeType(SomlValue value) {
		return switch (value) {
			case SomlString _ -> "a string";
			case SomlInt _ -> "an int";
			case SomlFloat _ -> "a float";
			case SomlBool _ -> "a bool";
			case SomlNull _ -> "null";
			case SomlDate _ -> "a date";
			case SomlInstant _ -> "an instant";
			case SomlDuration _ -> "a duration";
			case SomlArray _ -> "an array";
			case SomlObject _ -> "an object";
		};
	}

	/*
	Writes a value whose own depth is `depth`, with the lines of a container after its first indented by `indent` tabs.
	*/
	private void writeValue(SomlValue value, int depth, int indent) {
		switch (value) {
			case SomlArray(var items) -> {
				checkDepth(depth, maxDepth);
				var count = items.size();

				if (count == 0) {
					output.append("[]");
					return;
				}

				output.append(isOneLine ? "[" : "[\n");

				for (var index = 0; index < count; index++) {
					if (isOneLine) {
						if (index > 0) {
							output.append(", ");
						}
					} else {
						appendIndent(indent + 1);
					}

					try {
						writeValue(items.get(index), depth + 1, indent + 1);
					} catch (Binding.BindingError error) {
						throw error.at(index);
					}

					if (!isOneLine) {
						output.append('\n');
					}
				}

				if (!isOneLine) {
					appendIndent(indent);
				}

				output.append(']');
			}
			case SomlObject object -> {
				checkDepth(depth, maxDepth);

				if (object.memberCount() == 0) {
					output.append("{}");
					return;
				}

				output.append(isOneLine ? "{" : "{\n");
				writeMembers(object, depth + 1, indent + 1);

				if (!isOneLine) {
					appendIndent(indent);
				}

				output.append('}');
			}
			default -> writeScalar(output, value);
		}
	}

	/*
	Writes a scalar without allocating a writer or inspecting container options.
	*/
	private static void writeScalar(TextBuffer output, SomlValue value) {
		switch (value) {
			case SomlString(var string) -> output.appendString(string, null);
			case SomlInt(var number) -> output.append(number);
			case SomlFloat(var number) -> SomlFloat.appendTo(output, number);
			case SomlBool(var bool) -> output.append(bool);
			case SomlNull _ -> output.append("null");
			case SomlDate(var date) -> output.append(date.toString());
			case SomlInstant(var instant) -> SomlInstant.appendTo(output, instant);
			case SomlDuration(var duration) -> SomlDuration.appendTo(output, duration);
			case SomlObject _, SomlArray _ -> throw new IllegalArgumentException("Expected a scalar");
		}
	}

	/*
	Writes each member on its own line, indented by `indent` tabs. The members' values have the depth `depth`.
	*/
	private void writeMembers(SomlObject object, int depth, int indent) {
		var count = object.memberCount();
		var order = isCanonical ? object.canonicalOrder() : null;

		for (var position = 0; position < count; position++) {
			var index = order == null ? position : order[position];
			var key = object.keyAt(index);

			if (isOneLine) {
				if (position > 0) {
					output.append(", ");
				}
			} else {
				appendIndent(indent);
			}

			output.appendKey(key, null);
			output.append(": ");

			try {
				writeValue(object.valueAt(index), depth, indent);
			} catch (Binding.BindingError error) {
				throw error.at(key);
			}

			if (!isOneLine) {
				output.append('\n');
			}
		}
	}

	/*
	The indexes of an object's members, sorted by key, or null when they are already sorted.
	*/
	static Integer @Nullable [] canonicalOrder(String[] keys, int count) {
		var isSorted = true;

		for (var index = 1; index < count; index++) {
			if (compareKeys(keys[index - 1], keys[index]) > 0) {
				isSorted = false;
				break;
			}
		}

		if (isSorted) {
			return null;
		}

		// Note: Not using a primitive index array because `Arrays.sort()` only accepts a comparator for object arrays.
		// Note: Not using `Arrays.parallelSetAll()` because initializing indexes is a cheap loop that needs no parallel stream or fork/join work.
		var boxed = new Integer[count];
		Arrays.setAll(boxed, index -> index);

		Arrays.sort(boxed, (left, right) -> compareKeys(keys[left], keys[right]));
		return boxed;
	}

	private static void checkDepth(int depth, int maxDepth) {
		if (depth > maxDepth) {
			throw new Binding.BindingError("Cannot encode a value nested more than " + MAX_DEPTH + " levels deep");
		}
	}

	/*
	Note: Not using `String.compareTo()` because canonical keys must sort by Unicode scalar value, while it sorts UTF-16 code units. Move surrogates above U+E000 to U+FFFF so characters above U+FFFF sort after the BMP.
	*/
	static int compareKeys(String left, String right) {
		if (left == right) {
			return 0;
		}

		var length = Math.min(left.length(), right.length());

		for (var index = 0; index < length; index++) {
			int leftCode = left.charAt(index);
			int rightCode = right.charAt(index);

			if (leftCode != rightCode) {
				// Below U+D800, the order of code units is the order of code points, so the two orders can differ only when both units are at or above it.
				if (leftCode >= 0xD800 && rightCode >= 0xD800) {
					leftCode = toCodePointOrder(leftCode);
					rightCode = toCodePointOrder(rightCode);
				}

				return leftCode - rightCode;
			}
		}

		return left.length() - right.length();
	}

	private static int toCodePointOrder(int code) {
		// U+E000 to U+FFFF move down by 0x800 into the surrogate range, and the surrogates move up by 0x2000 above them, so each group keeps its own order.
		if (code >= 0xE000) {
			return code - 0x800;
		}

		return code >= 0xD800 ? code + 0x2000 : code;
	}

	/*
	A bare key is one or more ASCII letters, digits, `_`, or `-`.
	*/
	static boolean isBareKeyCharacter(int code) {
		// Note: Not using `Character.isLetterOrDigit()` because bare keys allow only ASCII letters and digits, plus underscore and hyphen.
		return code >= 0 && code < 128 && BARE_KEY_CHARACTERS[code];
	}

	private static final boolean[] BARE_KEY_CHARACTERS = new boolean[128];

	static {
		for (var code = 0; code < 128; code++) {
			BARE_KEY_CHARACTERS[code] = (code >= 'a' && code <= 'z') || (code >= 'A' && code <= 'Z') || (code >= '0' && code <= '9') || code == '_' || code == '-';
		}
	}

	static boolean isBareKey(String key) {
		if (key.isEmpty()) {
			return false;
		}

		for (var index = 0; index < key.length(); index++) {
			if (!isBareKeyCharacter(key.charAt(index))) {
				return false;
			}
		}

		return true;
	}

	static String formatKey(String key) {
		return isBareKey(key) ? key : formatString(key);
	}

	/*
	A string is `'...'` unless it holds a `'`, a tab, a line feed, another C0 control character, or DEL, and then it is `"..."` with escapes. The string holds no carriage return and no lone surrogate, which `SomlString` refuses.
	*/
	static String formatString(String string) {
		if (string.isEmpty()) {
			return "''";
		}

		for (var index = 0; index < string.length(); index++) {
			if (needsEscapes(string.charAt(index))) {
				var text = new TextBuffer(string.length() + 2);
				text.appendEscaped(string);
				return text.toString();
			}
		}

		return "'" + string + "'";
	}

	private static boolean needsEscapes(char character) {
		return character < 0x20 || character == '\'' || character == 0x7F;
	}

	/*
	Note: Not using `StringBuilder` because the growing `char[]` costs less per append and allows strings to be copied in bulk and checked in place. The encoder copies the text into a `String` once, at the end.
	*/
	static final class TextBuffer implements AutoCloseable {
		private char[] characters;
		private int length;

		TextBuffer(int capacity) {
			this(new char[capacity]);
		}

		private TextBuffer(char[] characters) {
			this.characters = characters;
		}

		/*
		One buffer of the encoder, kept between documents, because making a buffer large enough costs as much as encoding a small document. A document takes it, or makes its own when another document has it, and gives it back when it is done. A buffer larger than the limit is not kept.
		*/
		private static final AtomicReference<char @Nullable []> SPARE = new AtomicReference<>();
		private static final int MAX_SPARE = 1 << 20;

		static TextBuffer reused() {
			// Note: Not using `AtomicReference.getAndUpdate()` because `getAndSet(null)` exchanges the value directly without an update callback and retry loop.
			var spare = SPARE.getAndSet(null);
			// Note: Not using `Optional.orElse()` because it evaluates the fallback even when a pooled buffer is available.
			return new TextBuffer(spare != null ? spare : new char[4096]);
		}

		/*
		Gives the buffer back, after which this one must not be used.
		*/
		@Override
		public void close() {
			if (characters.length <= MAX_SPARE) {
				SPARE.set(characters);
			}
		}

		private void reserve(int count) {
			var required = Math.addExact(length, count);

			if (required > characters.length) {
				characters = Arrays.copyOf(characters, Math.clamp((long) characters.length * 2, required, Integer.MAX_VALUE));
			}
		}

		TextBuffer append(char character) {
			reserve(1);
			characters[length++] = character;
			return this;
		}

		TextBuffer append(String text) {
			return append(text, 0, text.length());
		}

		TextBuffer append(long number) {
			// Note: Not using `Long.toString()` because writing digits directly avoids an intermediate string, especially for the small integers common in documents.
			if (number >= 0 && number < 10) {
				return append((char) ('0' + number));
			}

			if (number < 0 && number > -10) {
				reserve(2);
				characters[length++] = '-';
				characters[length++] = (char) ('0' - number);
				return this;
			}

			reserve(20);

			if (number < 0) {
				characters[length++] = '-';
			} else {
				number = -number;
			}

			// Work in the negative range, which includes the magnitude of Long.MIN_VALUE.
			var digitsStart = length;

			do {
				characters[length++] = (char) ('0' - number % 10);
				number /= 10;
			} while (number != 0);

			// Note: Not using `StringBuilder.reverse()` because the digits already occupy the output buffer and can be reversed without intermediate storage.
			for (int left = digitsStart, right = length - 1; left < right; left++, right--) {
				var character = characters[left];
				characters[left] = characters[right];
				characters[right] = character;
			}

			return this;
		}

		/*
		Appends a non-negative number padded with zeros to at least `width` digits.
		*/
		TextBuffer appendPadded(int number, int width) {
			var digits = 1;

			for (var remaining = number; remaining >= 10; remaining /= 10) {
				digits++;
			}

			var count = Math.max(width, digits);
			reserve(count);
			var end = length + count;
			var start = end;

			do {
				characters[--start] = (char) ('0' + number % 10);
				number /= 10;
			} while (number > 0);

			Arrays.fill(characters, length, start, '0');
			length = end;
			return this;
		}

		TextBuffer append(boolean bool) {
			return append(bool ? "true" : "false");
		}

		TextBuffer repeat(char character, int count) {
			if (count == 0) {
				return this;
			}

			reserve(count);
			Arrays.fill(characters, length, length + count, character);
			length += count;
			return this;
		}

		int length() {
			return length;
		}

		void setLength(int length) {
			this.length = length;
		}

		@Override
		public String toString() {
			return new String(characters, 0, length);
		}

		/*
		Writes already validated text without an intermediate string, and without flushing or closing the caller's writer.
		*/
		void writeTo(java.io.Writer writer) throws IOException {
			writer.write(characters, 0, length);
		}

		/*
		Appends a string as a document writes it. It is copied in after the opening quote, and the copy is scanned, which decides the quotes. With a `kind`, which names the string in the error, as in "A key", the scan also checks for what a SOML string cannot hold, as for a string from a Java object. Without one, the string is known to be valid.

		@throws IllegalArgumentException When the string is checked and holds a carriage return or a lone surrogate.
		*/
		void appendString(String string, @Nullable String kind) {
			var count = string.length();
			reserve(count + 2);
			var start = length + 1;
			string.getChars(0, count, characters, start);
			var end = start + count;
			var needsEscapes = false;

			for (var index = start; index < end; index++) {
				var character = characters[index];

				// Printable ASCII other than `'`, the common case, in two comparisons.
				if (Integer.compareUnsigned(character - 0x20, 0x5F) < 0 && character != '\'') {
					continue;
				}

				if (kind != null) {
					if (character == '\r') {
						throw new IllegalArgumentException(kind + " cannot contain a carriage return (U+000D), which is not representable");
					}

					if (Character.isSurrogate(character)) {
						if (index + 1 < end && Character.isSurrogatePair(character, characters[index + 1])) {
							index++;
							continue;
						}

						throw new IllegalArgumentException(kind + " cannot contain a lone surrogate, which is not a Unicode scalar value");
					}
				}

				if (needsEscapes(character)) {
					needsEscapes = true;

					// Valid already, so nothing else is to be found.
					if (kind == null) {
						break;
					}
				}
			}

			if (!needsEscapes) {
				characters[length] = '\'';
				characters[end] = '\'';
				length = end + 1;
				return;
			}

			// `length` has not moved, so the escaped form writes over the copy.
			appendEscaped(string);
		}

		/*
		Appends a string as `"..."`, with the runs of characters between escapes copied in bulk.
		*/
		private void appendEscaped(String string) {
			append('"');
			var runStart = 0;

			for (var index = 0; index < string.length(); index++) {
				var character = string.charAt(index);
				if (character >= 0x20 && character != 0x7F && character != '\\' && character != '"') {
					continue;
				}

				append(string, runStart, index);

				switch (character) {
					case '\\' -> append("\\\\");
					case '"' -> append("\\\"");
					case '\n' -> append("\\n");
					case '\t' -> append("\\t");
					default -> {
						append("\\u{");

						if (character >= 0x10) {
							append(Character.forDigit(character >>> 4, 16));
						}

						append(Character.forDigit(character & 0xF, 16)).append('}');
					}
				}

				runStart = index + 1;
			}

			append(string, runStart, string.length()).append('"');
		}

		TextBuffer append(String text, int start, int end) {
			var count = end - start;

			if (count == 0) {
				return this;
			}

			reserve(count);
			text.getChars(start, end, characters, length);
			length += count;
			return this;
		}

		/*
		Appends a key that may hold what a SOML key cannot: bare when it can be, which needs no other check, and otherwise as a checked string.

		@throws IllegalArgumentException When the key holds a carriage return or a lone surrogate.
		*/
		void appendCheckedKey(String key) {
			appendKey(key, "A key");
		}

		/*
		Appends a bare key or its quoted string form. A kind also validates the quoted key.
		*/
		void appendKey(String key, @Nullable String kind) {
			if (isBareKey(key)) {
				append(key);
			} else {
				appendString(key, kind);
			}
		}
	}
}
