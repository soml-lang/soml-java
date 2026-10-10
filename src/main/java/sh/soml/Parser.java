package sh.soml;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/*
A recursive-descent parser that builds a `SomlValue` tree and checks every rule in the spec.

It is a port of the parser of the JavaScript reference implementation, which also works on UTF-16 code units, so it reports every error at the same position and with the same reason. Most of the code is diagnostics: when something fails, it works out what the author most likely meant, such as a YAML list, a TOML table header, or an unquoted string.
*/
final class Parser {
	/*
	The longest token that a diagnostic looks at. A longer one is described from its first part, so that no message or scan for a hint grows with the input.
	*/
	private static final int MAX_DIAGNOSED_LENGTH = 1000;
	private static final HexFormat UPPERCASE_HEX = HexFormat.of().withUpperCase();

	private static final String INSTANT_FORMAT = "An instant is written as 2026-09-19T14:00:00Z, with an optional fraction of up to nine digits and an offset of Z or ±HH:MM";
	private static final String KEY_QUOTING_HINT = ". A key that contains characters other than letters, digits, “_”, and “-” must be quoted";

	private static final Pattern TABLE_HEADER = Pattern.compile("^[\\t ]*\\[\\[?(?<name>[A-Z_a-z][\\w\\-]*)\\]\\]?[\\t ]*$");
	private static final Pattern LOCAL_DATE_TIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?$");

	// Diagnostic patterns are compiled once, because malformed input can be frequent too.
	private static final Pattern UPPERCASE_EXPONENT = Pattern.compile("^-?\\d[\\d_]*(?:\\.[\\d_]+)?E-?[\\d_]*$");
	private static final Pattern MISSING_FRACTION = Pattern.compile("^-?[\\d_]+\\.(?:$|[^\\d_])");
	private static final Pattern COLON_NUMBER = Pattern.compile("^\\d+(?::\\d+)+$");
	private static final Pattern TIME_OF_DAY = Pattern.compile("^\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?$");
	private static final Pattern MISPLACED_UNDERSCORE = Pattern.compile("_(?:$|\\D)|(?:^|\\D)_");
	private static final Pattern NON_NUMBER_CHARACTER = Pattern.compile("[^\\d\\-._e]");
	private static final Pattern ZERO_PREFIXED_EXPONENT = Pattern.compile("^-?\\d[\\d_]*(?:\\.[\\d_]+)?e-?0_?\\d");
	private static final Pattern NEGATIVE_ZERO_EXPONENT = Pattern.compile("^-?\\d[\\d_]*(?:\\.[\\d_]+)?e-0$");
	private static final Pattern MISSING_EXPONENT = Pattern.compile("^-?\\d[\\d_]*(?:\\.[\\d_]+)?e-?$");
	private static final Pattern MONTH_SIZE = Pattern.compile("\\d[\\d_]*M");


	private final String source;
	// Note: Not using `String.charAt()` in parser scans because reading the retained character array is cheaper than repeatedly checking the string's encoding.
	private final char[] characters;
	private final int length;
	private int index;
	// Where the document's collection starts, after the comments and whitespace before it.
	private int documentStart;
	/*
	When not null, the offsets of every container's children, for the position of a decoding error: an object's key and value offsets in pairs, in member order, and an array's item offsets.
	*/
	private final @Nullable IdentityHashMap<SomlValue, int[]> positions;
	/*
	When not null, the parser sends the document to it as events, and makes no value for an object or an array. It is set to null before a diagnostic parses again after an error, which sends nothing.
	*/
	private @Nullable Sink sink;
	private final Scratch scratch;
	// The characters as memory, which `skipPlain()` reads four at a time.
	private final MemorySegment segment;
	private final Members[] membersByDepth;
	private final Items[] itemsByDepth;
	private final String[] keys;
	// A power of two, because `cachedKey()` finds the slot with a mask.
	private static final int KEY_CACHE_SIZE = 512;
	private static final int MAX_CACHED_KEY_LENGTH = 32;

	/*
	What a parse needs besides the document, kept between parses, because making it again costs as much as parsing a small document: a buffer for the characters, the open object and array at each depth, from 1 to one past the limit, where the depth error is reported, and the keys read so far, by a hash of their characters, so that a key that repeats, as in an array of records, is one string, whose hash code is computed once. A key in the cache is compared before it is used, so keys from an earlier document are fine.
	*/
	private static final class Scratch {
		char[] buffer = new char[0];
		MemorySegment segment = MemorySegment.ofArray(buffer);
		final Members[] membersByDepth = new Members[Writer.MAX_DEPTH + 2];
		final Items[] itemsByDepth = new Items[Writer.MAX_DEPTH + 2];
		final String[] keys = new String[KEY_CACHE_SIZE];

		/*
		Checks the retained capacity and clears member references in the same pass. A scratch over the limit is discarded immediately.
		*/
		boolean prepareForReuse() {
			long capacity = (long) buffer.length + keys.length;

			if (capacity > MAX_SPARE_BUFFER) {
				return false;
			}

			for (var members : membersByDepth) {
				if (members != null) {
					capacity += (long) members.keys.length + members.values.length;

					if (capacity > MAX_SPARE_BUFFER) {
						return false;
					}

					members.reset();
				}
			}

			for (var items : itemsByDepth) {
				if (items != null) {
					capacity += items.items.length;

					if (capacity > MAX_SPARE_BUFFER) {
						return false;
					}
				}
			}

			return true;
		}

	}

	/*
	The parser that runs on this thread, for `SomlString`, which skips its own check of the one string that the parser is making a value of, `checkedString`, because the parser has checked it with the rest of the document. Any other string is checked, such as one that a record's constructor makes while the decoder runs in a parse into a `Sink`.
	*/
	static final ScopedValue<Parser> CURRENT = ScopedValue.newInstance();

	@Nullable String checkedString;

	/*
	One scratch, kept between parses. A parse takes it, or makes its own when another parse has it, and gives it back when it is done. A scratch whose total buffer capacity is larger than the limit is not kept.
	*/
	private static final AtomicReference<@Nullable Scratch> SPARE_SCRATCH = new AtomicReference<>();
	private static final int MAX_SPARE_BUFFER = 1 << 20;

	private Parser(String source, @Nullable IdentityHashMap<SomlValue, int[]> positions) {
		this.source = source;
		this.length = source.length();
		var scratch = SPARE_SCRATCH.getAndSet(null);

		if (scratch == null) {
			scratch = new Scratch();
		}

		if (scratch.buffer.length < length) {
			scratch.buffer = new char[length];
			scratch.segment = MemorySegment.ofArray(scratch.buffer);
		}

		// The pooled buffer can be longer than this source, and past `length` it can still hold an earlier document, so every read is bounded by `length`, never by `characters.length`.
		source.getChars(0, length, scratch.buffer, 0);
		this.scratch = scratch;
		this.characters = scratch.buffer;
		this.segment = scratch.segment;
		this.membersByDepth = scratch.membersByDepth;
		this.itemsByDepth = scratch.itemsByDepth;
		this.keys = scratch.keys;
		this.positions = positions;
	}

	/*
	Gives the scratch back, after which this parser must not be used. After an error, the scratch can still hold values of the document, so it is dropped.
	*/
	private void release() {
		if (!hasFailed && scratch.prepareForReuse()) {
			SPARE_SCRATCH.set(scratch);
		}
	}

	private boolean hasFailed;

	/*
	Parses a document, which is always an object or an array.
	*/
	static SomlValue parse(String source) {
		return Objects.requireNonNull(new Parser(source, null).run());
	}

	/*
	Runs every document parse in the same scope, including reparsing for decoding positions, and always returns its scratch.
	*/
	private @Nullable SomlValue run() {
		try {
			return ScopedValue.where(CURRENT, this).call(this::parseDocument);
		} catch (SomlException error) {
			hasFailed = true;
			sink = null;
			// A character that is an error anywhere is reported before any other error, as the reference does, which checks every character first.
			checkCharacters();
			throw error;
		} finally {
			release();
		}
	}

	/*
	Parses a valid document again, recording where each value is, for the position of a decoding error. Returns the document start too, as the offset of the whole document.
	*/
	static Located parseWithPositions(String source) {
		var positions = new IdentityHashMap<SomlValue, int[]>();
		var parser = new Parser(source, positions);

		// Without a sink, the parser makes the value of every container.
		var value = Objects.requireNonNull(parser.run());
		return new Located(source, value, parser.documentStart, positions);
	}

	record Located(String source, SomlValue value, int documentStart, IdentityHashMap<SomlValue, int[]> positions) {}

	/*
	What receives a document as events, in order, for the decoder, which binds it to Java objects without a value tree. A member is its key and then its value, and a value is a scalar or a container's events. The parser checks the whole document as it goes, so an error can come after events for the values before it.
	*/
	interface Sink {
		void startObject();

		void key(String key);

		void endObject();

		void startArray();

		void endArray();

		void scalar(SomlValue value);
	}

	private static final Sink DISCARD = new Sink() {
		@Override
		public void startObject() {
		}

		@Override
		public void key(String key) {
		}

		@Override
		public void endObject() {
		}

		@Override
		public void startArray() {
		}

		@Override
		public void endArray() {
		}

		@Override
		public void scalar(SomlValue value) {
		}
	};

	/*
	Checks a document without allocating its container values, as for the formatter's syntax tree.
	*/
	static void validate(String source) {
		parse(source, DISCARD);
	}

	/*
	Parses a document into `sink`, with the same checks and errors as `parse()`.
	*/
	static void parse(String source, Sink sink) {
		var parser = new Parser(source, null);
		parser.sink = sink;

		parser.run();
	}

	/*
	The value of a quoted string or key that is already known to be valid, from `start` to `end` in `source`, for the syntax tree.
	*/
	static String readQuoted(String source, int start, int end) {
		// Validated literal strings and strings without escapes need no parser or scratch buffer.
		if (source.charAt(start) == '\'' || source.indexOf('\\', start, end) == -1) {
			return source.substring(start + 1, end - 1);
		}

		var parser = new Parser(source.substring(start, end), null);

		try {
			return parser.parseString();
		} finally {
			parser.release();
		}
	}

	/*
	UTF-8 without replacement. Invalid input is reported at the first byte of the first invalid sequence, with the position counted in the text decoded before it.
	*/
	static String decode(byte[] bytes) {
		// The fast path: the replacing decoder writes U+FFFD for every invalid sequence, so text without one is valid. Text that holds a U+FFFD, valid or not, takes the strict path.
		var text = new String(bytes, StandardCharsets.UTF_8);

		if (text.indexOf(0xFFFD) == -1) {
			return text;
		}

		var offset = findInvalidUtf8(bytes);

		if (offset == bytes.length) {
			return text;
		}

		var valid = new String(bytes, 0, offset, StandardCharsets.UTF_8);

		if (isTruncatedUtf8(bytes, offset)) {
			throw SomlException.at("Incomplete UTF-8 sequence at the end of the input", valid, valid.length());
		}

		throw SomlException.at("Invalid UTF-8 byte 0x" + UPPERCASE_HEX.toHexDigits(bytes[offset]), valid, valid.length());
	}

	/*
	The first byte of the first invalid sequence. A sequence's length is decided by its first byte, as in the reference.
	*/
	private static int findInvalidUtf8(byte[] bytes) {
		var index = 0;

		while (index < bytes.length) {
			var byteValue = Byte.toUnsignedInt(bytes[index]);

			if (byteValue < 0x80) {
				index++;
				continue;
			}

			var length = sequenceLength(bytes, index);

			if (length == 0 || bytes.length - index < length) {
				return index;
			}

			index += length;
		}

		return index;
	}

	/*
	The length of the well-formed sequence or incomplete prefix at `index`, or 0 when there is none, by the table of well-formed UTF-8 in the Unicode standard.
	*/
	private static int sequenceLength(byte[] bytes, int index) {
		var first = Byte.toUnsignedInt(bytes[index]);
		int length;
		int secondMinimum = 0x80;
		int secondMaximum = 0xBF;

		if (first >= 0xC2 && first <= 0xDF) {
			length = 2;
		} else if (first >= 0xE0 && first <= 0xEF) {
			length = 3;

			if (first == 0xE0) {
				secondMinimum = 0xA0;
			} else if (first == 0xED) {
				secondMaximum = 0x9F;
			}
		} else if (first >= 0xF0 && first <= 0xF4) {
			length = 4;

			if (first == 0xF0) {
				secondMinimum = 0x90;
			} else if (first == 0xF4) {
				secondMaximum = 0x8F;
			}
		} else {
			return 0;
		}

		var available = Math.min(length, bytes.length - index);

		for (var offset = 1; offset < available; offset++) {
			var next = Byte.toUnsignedInt(bytes[index + offset]);
			var minimum = offset == 1 ? secondMinimum : 0x80;
			var maximum = offset == 1 ? secondMaximum : 0xBF;

			if (next < minimum || next > maximum) {
				return 0;
			}
		}

		return length;
	}

	/*
	Whether the bytes from `offset` to the end are the start of a well-formed sequence that the end cuts short.
	*/
	private static boolean isTruncatedUtf8(byte[] bytes, int offset) {
		// A sequence is at most four bytes, so a cut one has at most three.
		// Note: Not using a padded byte array because checking each available continuation byte directly validates the prefix without allocating trial input.
		return sequenceLength(bytes, offset) > bytes.length - offset;
	}

	/*
	Characters that are errors wherever they appear, in one pass over the document. A control character is reported before a lone surrogate, wherever each is, and both before any other error, as the reference reports them.

	The parse does not run this first. Outside a string or a comment, such a character is a syntax error anyway, and the scans of strings and comments call this when they meet one, so it runs only when the document has an error.
	*/
	private void checkCharacters() {
		if (length > 0 && characters[0] == 0xFEFF) {
			throw fail("A byte order mark (BOM) is not allowed", 0);
		}

		var loneSurrogate = -1;

		for (var offset = 0; offset < length; offset++) {
			var character = characters[offset];

			// Printable ASCII, the common case, in one comparison.
			if (Integer.compareUnsigned(character - 0x20, 0x5F) < 0) {
				continue;
			}

			if (character < 0x20) {
				if (character != '\t' && character != '\n') {
					throw failControlCharacter(offset);
				}
			} else if (Character.isSurrogate(character)) {
				if (character <= 0xDBFF && offset + 1 < length && Character.isLowSurrogate(characters[offset + 1])) {
					offset++;
				} else if (loneSurrogate == -1) {
					loneSurrogate = offset;
				}
			} else if (character == 0x7F) {
				throw failControlCharacter(offset);
			}
		}

		if (loneSurrogate != -1) {
			throw fail("A lone surrogate (" + describeCharacter(characters[loneSurrogate]) + ") is not a Unicode scalar value", loneSurrogate);
		}
	}

	private static final long LANES_HIGH_BITS = 0x8000800080008000L;
	private static final long LANES_ONE = 0x0001000100010001L;
	private static final long LANES_SPACE = 0x0020002000200020L;
	private static final long LANES_NOT_ASCII = 0xFF80FF80FF80FF80L;
	private static final long LANES_DELETE = 0x007F007F007F007FL;
	static final long LANES_APOSTROPHE = 0x0027002700270027L;
	static final long LANES_QUOTATION_MARK = 0x0022002200220022L;
	static final long LANES_BACKSLASH = 0x005C005C005C005CL;
	static final long LANES_ASTERISK = 0x002A002A002A002AL;
	// No character is a NUL that is not caught as a control character first, so this matches nothing more.
	static final long LANES_NONE = 0L;

	/*
	The offset of the first character from `offset` on that is not printable ASCII, or that is one of the two characters that `first` and `second` hold in each of their four 16-bit lanes, as in `LANES_APOSTROPHE`. It reads four characters at a time as one `long`, and tells by arithmetic on the lanes whether any of them is such a character (SWAR), so that a long run of plain text, as in a string or a comment, costs about a quarter of the comparisons. The scan of the characters after it decides what each one is.
	*/
	private int skipPlain(int offset, long first, long second) {
		return skipPlain(offset, length, first, second);
	}

	/*
	The same scan bounded by `end`, for content that occupies only part of the document.
	*/
	private int skipPlain(int offset, int end, long first, long second) {
		while (offset + 4 <= end) {
			var lanes = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset * 2L);

			// A lane at or above 0x80 is not ASCII. The others are below 0x8000, where the subtraction of each lane only borrows from a lane that is below the subtrahend, so its high bit tells that one.
			if ((lanes & LANES_NOT_ASCII) != 0 || hasLessThan(lanes, LANES_SPACE) || hasZero(lanes ^ LANES_DELETE) || hasZero(lanes ^ first) || hasZero(lanes ^ second)) {
				return offset;
			}

			offset += 4;
		}

		return offset;
	}

	private static boolean hasLessThan(long lanes, long bound) {
		return ((lanes - bound) & ~lanes & LANES_HIGH_BITS) != 0;
	}

	private static boolean hasZero(long lanes) {
		return hasLessThan(lanes, LANES_ONE);
	}

	/*
	A character in a string or a comment that is not printable ASCII and not a line feed. A tab, a character above ASCII, and a surrogate pair are content, and the offset of the last character it takes is returned. Anything else is an error somewhere in the document, which the full check reports, at the first such character.
	*/
	private int checkContent(int offset) {
		var character = characters[offset];

		if (character == '\t' || (character >= 0x80 && !Character.isSurrogate(character))) {
			return offset;
		}

		if (Character.isHighSurrogate(character) && offset + 1 < length && Character.isLowSurrogate(characters[offset + 1])) {
			return offset + 1;
		}

		checkCharacters();
		throw new IllegalStateException("The character at " + offset + " is not reported by the check of every character");
	}

	/*
	Skips valid string or comment content up to a line feed, either special ASCII character, or the end. Unicode and raw control checks are shared by every caller.
	*/
	private int skipContent(int offset, int end, long first, long second) {
		for (;;) {
			offset = skipPlain(offset, end, first, second);

			if (offset >= end) {
				return offset;
			}

			var character = characters[offset];

			if (Integer.compareUnsigned(character - 0x20, 0x5F) < 0) {
				if (character == (char) first || character == (char) second) {
					return offset;
				}
			} else if (character == '\n') {
				return offset;
			} else {
				offset = checkContent(offset);
			}

			offset++;
		}
	}

	private SomlException failControlCharacter(int offset) {
		var character = characters[offset];

		if (character == '\r') {
			return fail("A carriage return (U+000D) is not allowed anywhere. Use LF line endings", offset);
		}

		return fail("A raw control character (" + formatCodePoint(character) + ") is not allowed anywhere, including in strings and comments. In a string, write it as the escape \\u{" + Integer.toHexString(character) + "} inside \"...\"", offset);
	}

	private SomlException fail(String reason, int offset) {
		return SomlException.at(reason, source, offset);
	}

	private SomlException fail(String reason) {
		return fail(reason, index);
	}

	/*
	The UTF-16 code unit at `offset`, or -1 past the end.
	*/
	private int code(int offset) {
		// Note: Not using `Objects.checkIndex()` because parser lookahead returns a sentinel outside the source instead of throwing.
		return offset >= 0 && offset < length ? characters[offset] : -1;
	}

	private int code() {
		return code(index);
	}

	private boolean isAtEnd() {
		return index >= length;
	}

	private @Nullable SomlValue parseDocument() {
		skipTrivia();
		documentStart = index;

		if (isAtEnd()) {
			throw fail("A document must contain an object or an array, but this one is empty");
		}

		var code = code();
		SomlValue value;

		if (code == '{') {
			value = parseObject(1);
		} else if (code == '[') {
			value = parseArray(1);
		} else {
			value = parseBareObject();
		}

		skipTrivia();

		if (!isAtEnd()) {
			throw fail("Unexpected " + describeHere() + " after the end of the document");
		}

		return value;
	}

	/*
	`bare-object = entry ( entry-sep entry )*`, where a separator is one or more line breaks.
	*/
	private @Nullable SomlValue parseBareObject() {
		var members = members(1);
		var offsets = positions == null ? null : IntStream.builder();
		var start = index;

		if (sink != null) {
			sink.startObject();
		}

		try {
			parseEntry(members, 1, offsets);
		} catch (SomlException error) {
			sink = null;
			diagnoseBareValue(start);
			throw error;
		}

		var hasLineBreak = skipTrivia();

		while (!isAtEnd()) {
			if (code() == ',') {
				throw fail("Top-level entries are separated by line breaks, not commas. Use braces for a one-line object");
			}

			if (!hasLineBreak) {
				throw fail("Expected a line break before the next entry, but found " + describeHere() + slashCommentHint());
			}

			parseEntry(members, 1, offsets);
			hasLineBreak = skipTrivia();
		}

		if (sink != null) {
			sink.endObject();
			return null;
		}

		return record(members.toObject(), offsets);
	}

	private SomlValue record(SomlValue container, IntStream.@Nullable Builder offsets) {
		// `offsets` is made only when `positions` is.
		if (offsets != null && positions != null) {
			positions.put(container, offsets.build().toArray());
		}

		return container;
	}

	/*
	A whole document that is one scalar, such as `5` or an instant, fails as an entry. That failure is reported as what it is.
	*/
	private void diagnoseBareValue(int start) {
		index = start;
		var isBareValue = false;

		try {
			parseValue(1);
			skipTrivia();
			isBareValue = isAtEnd();
		} catch (SomlException _) {
			// Not a bare value either, so the original error stands.
		}

		if (isBareValue) {
			throw fail("A bare value is not a document. A document is an object or an array, so write it as `key: value` or `[value]`", start);
		}
	}

	/*
	Skips spaces, tabs, line breaks, and comments, and reports whether a line break was crossed. A line break inside a block comment does not count.
	*/
	private boolean skipTrivia() {
		var hasCrossedLineBreak = false;

		while (index < length) {
			var character = characters[index];

			if (character == ' ' || character == '\t') {
				index++;
			} else if (character == '\n') {
				hasCrossedLineBreak = true;
				index++;
			} else if (!skipComment()) {
				return hasCrossedLineBreak;
			}
		}

		return hasCrossedLineBreak;
	}

	/*
	Skips the comment that starts here, or returns false when there is none.
	*/
	private boolean skipComment() {
		var code = code();

		if (code == '#') {
			index = skipContent(index + 1, length, LANES_NONE, LANES_NONE);
			return true;
		}

		if (code == '/' && code(index + 1) == '*') {
			skipBlockComment();
			return true;
		}

		return false;
	}

	private void skipBlockComment() {
		var start = index;
		var end = -1;

		for (var offset = skipPlain(start + 2, LANES_ASTERISK, LANES_ASTERISK); offset < length; offset = skipPlain(offset + 1, LANES_ASTERISK, LANES_ASTERISK)) {
			var character = characters[offset];

			if (Integer.compareUnsigned(character - 0x20, 0x5F) < 0) {
				if (character == '*' && offset + 1 < length && characters[offset + 1] == '/') {
					end = offset;
					break;
				}
			} else if (character != '\n') {
				offset = checkContent(offset);
			}
		}

		if (end == -1) {
			throw fail("Unterminated block comment", start);
		}

		// The body may not contain `/*`. The search is bounded by the closing `*/`, so an opening that overlaps it, as in `/*/`, is not inside the body.
		var nested = source.indexOf("/*", start + 2, end);

		if (nested != -1) {
			throw fail("Block comments cannot be nested, and their body may not contain “/*”", nested);
		}

		index = end + 2;
	}

	/*
	`entry = key ":" ws value`.
	*/
	private void parseEntry(Members members, int depth, IntStream.@Nullable Builder offsets) {
		var keyStart = index;
		var key = parseKey();
		var code = code();

		if (code == '.') {
			throw failDotInKey(keyStart);
		}

		if (code != ':') {
			throw failMissingColon(keyStart);
		}

		var colon = index;
		index++;
		skipTrivia();
		var valueStart = index;
		SomlValue value;

		if (sink != null) {
			sink.key(key);
		}

		try {
			value = parseValue(depth + 1);
		} catch (SomlException error) {
			sink = null;
			diagnoseBadValue(error, key, keyStart, colon);
			throw error;
		}

		if (sink != null) {
			// A container sent its own events, and has no value. Only the key is kept, for the duplicate check.
			if (value != null) {
				sink.scalar(value);
			}

			value = SomlNull.INSTANCE;
		}

		// Without a sink, the parser makes every value.
		if (!members.add(key, Objects.requireNonNull(value))) {
			throw fail("Duplicate key " + describeKey(key), keyStart);
		}

		if (offsets != null) {
			offsets.add(keyStart);
			offsets.add(valueStart);
		}
	}

	/*
	Reports a value that failed to parse as what the entry was meant to be, when that is clear.
	*/
	private void diagnoseBadValue(SomlException error, String key, int keyStart, int colon) {
		// A key that contains a `:`, as in `12:30: 'lunch'`, ends at the first `:`, and the rest is read as the value.
		if (Writer.isBareKeyCharacter(code(colon + 1))) {
			diagnoseKeyWithColon(keyStart);
		}

		index = colon + 1;
		var isValueOnNextLine = skipTrivia();
		var valueStart = index;
		var commentHint = describeCommentAsValue(colon);

		if (isValueOnNextLine) {
			diagnoseMissingValue(valueStart, key, keyStart, commentHint);
		}

		// A value that was left out at the end of the document or of an object.
		var code = code(valueStart);

		if (!commentHint.isEmpty() && error.offset() == valueStart && (code == '}' || code == -1)) {
			throw fail(error.reason() + commentHint, valueStart);
		}
	}

	/*
	A bare key followed directly by `:` and more of a key, up to a `:` that ends the key, as in `a:b: 1`.
	*/
	private void diagnoseKeyWithColon(int keyStart) {
		for (var offset = keyStart; offset < keyStart + MAX_DIAGNOSED_LENGTH; offset++) {
			var code = code(offset);

			if (code == ':') {
				var next = code(offset + 1);

				if (next == '\n' || isSpace(next) || next == -1) {
					throw fail("A key that contains “:” must be quoted, as in '" + abbreviate(source.substring(keyStart, offset)) + "'", keyStart);
				}
			} else if (!Writer.isBareKeyCharacter(code)) {
				return;
			}
		}
	}

	/*
	The hint for a `#` directly after a `:`, as in `color: #FFF`, which starts a comment rather than a value.
	*/
	private String describeCommentAsValue(int colon) {
		var hash = skipSpaces(colon + 1);

		if (code(hash) != '#') {
			return "";
		}

		// A `#` followed by a space, or by another `#`, starts an ordinary comment. A separator or a closing bracket after the value is not part of it.
		var limit = Math.min(source.length(), hash + MAX_DIAGNOSED_LENGTH);
		var second = hash + 1 < limit ? source.charAt(hash + 1) : -1;

		if (second == -1 || "\t\n #,]}".indexOf(second) != -1) {
			return "";
		}

		var end = hash + 2;

		while (end < limit && "\t\n ,]}".indexOf(source.charAt(end)) == -1) {
			end++;
		}

		return ". “#” starts a comment, so a value that starts with “#” must be quoted" + quotingExample(source.substring(hash, end));
	}

	/*
	An entry whose value was left out, as in `a:` followed by `'b': 1` on the next line, reads the next key as the value. That failure is reported as what it is.
	*/
	private void diagnoseMissingValue(int start, String parent, int keyStart, String commentHint) {
		// A YAML block sequence.
		if (code(start) == '-' && isSpace(code(start + 1))) {
			throw fail("Expected a value, but found a “-” list. An array is written in brackets, as in [80, 443]", start);
		}

		index = start;
		String key = null;

		try {
			key = parseKey();
		} catch (SomlException _) {
			// Not a key either, so the original error stands.
		}

		if (key == null || code() != ':') {
			return;
		}

		// Whitespace or the end follows the `:` of a bare key. A digit follows the `:` in an instant such as `2026-09-19T25:00:00Z`, whose own error is more precise. A quoted key cannot be part of a value, so anything may follow its `:`.
		var next = code(index + 1);
		var firstCode = code(start);

		if (firstCode != '\'' && firstCode != '"' && next != '\n' && !isSpace(next) && next != -1) {
			return;
		}

		var hint = commentHint.isEmpty() ? describeIndentedKey(parent, key, keyStart, start) : commentHint;
		throw fail("Expected a value, but found the key " + describeKey(key) + hint, start);
	}

	/*
	The hint for a key at `start` that is indented under the entry whose value is missing, as YAML nests an object.
	*/
	private String describeIndentedKey(String parent, String key, int keyStart, int start) {
		var keyIndentation = getIndentation(keyStart);
		var indentation = getIndentation(start);

		if (keyIndentation == -1 || indentation == -1 || indentation <= keyIndentation) {
			return "";
		}

		// The keys are written as in a document, so that the suggestion is valid.
		return ". Indentation does not nest objects, so write " + abbreviate(Writer.formatKey(parent), 200) + ": {" + abbreviate(Writer.formatKey(key), 200) + ": …}";
	}

	/*
	The number of spaces and tabs before `offset` on its line, or -1 when something else comes before it.
	*/
	private int getIndentation(int offset) {
		var lineStart = source.lastIndexOf('\n', offset - 1) + 1;
		return skipSpaces(lineStart) == offset ? offset - lineStart : -1;
	}

	private SomlException failMissingColon(int keyStart) {
		var next = skipSpaces(index);
		var nextCode = code(next);

		if (nextCode == ':' && next > index) {
			return fail("Whitespace is not allowed between a key and its “:”");
		}

		// A block comment followed by the ":" was meant to come before it.
		if (nextCode == '/' && code(next + 1) == '*') {
			var commentEnd = source.indexOf("*/", next + 2);

			if (commentEnd != -1 && code(skipSpaces(commentEnd + 2)) == ':') {
				return fail("A comment is not allowed between a key and its “:”", next);
			}
		}

		// A key with a space in it, such as `the name: 1`. The hint is only given for plain words, because quoting a quoted key would change what it means, and for a `:` that whitespace or the end follows, because quoting the words before the `:` in `server localhost:8080` would give a valid document with another meaning. So `the name:1` gets no hint.
		var colon = next > index && Writer.isBareKeyCharacter(nextCode) ? source.indexOf(':', next, findLineEnd(next)) : -1;

		if (colon != -1 && isSpaceOrLineEnd(code(colon + 1))) {
			// Only spaces and tabs are trimmed, because other characters are not whitespace in SOML, such as U+00A0.
			var keyEnd = skipSpacesBack(colon);

			if (isWordsWithSpaces(keyStart, keyEnd)) {
				var key = source.substring(keyStart, Math.min(keyEnd, keyStart + MAX_DIAGNOSED_LENGTH));
				return fail("A bare key cannot contain spaces. Quote it, as in '" + abbreviate(key) + "'", keyStart);
			}
		}

		if (isAtEnd() || code() == '\n') {
			return fail("Expected “:” after the key");
		}

		// A character directly after a bare key is most likely meant to be part of it.
		var previousCode = code(index - 1);
		var hint = previousCode != '\'' && previousCode != '"' && next == index && isQuotableKeyStart(next) ? KEY_QUOTING_HINT : "";
		index = next;
		return fail("Expected “:” after the key, but found " + describeHere() + hint);
	}

	private String parseKey() {
		var start = index;
		var code = code(start);

		if (code == '\'' || code == '"') {
			if (code(start + 1) == code && code(start + 2) == code) {
				throw fail("A block string cannot be a key");
			}

			// A key is a string, so it may hold anything a string can, including a line feed written as an escape.
			var first = code == '\'' ? LANES_APOSTROPHE : LANES_QUOTATION_MARK;
			var second = code == '\'' ? LANES_APOSTROPHE : LANES_BACKSLASH;
			var end = skipContent(start + 1, length, first, second);

			if (end >= length || characters[end] == '\n') {
				throw failUnterminatedString(start);
			}

			if (characters[end] == '\\') {
				return cachedKey(parseEscapedString(end));
			}

			index = end + 1;
			return cachedKey(start + 1, end);
		}

		var end = start;

		var hash = 0;

		var cacheEnd = Math.min(length, start + MAX_CACHED_KEY_LENGTH);

		while (end < cacheEnd && Writer.isBareKeyCharacter(characters[end])) {
			hash = (31 * hash) + characters[end];
			end++;
		}

		// Longer keys cannot enter the cache, so their remaining characters need no hash.
		while (end < length && Writer.isBareKeyCharacter(characters[end])) {
			end++;
		}

		if (end == start) {
			if (code == '[') {
				diagnoseTableHeader(false);
			}

			throw fail(isAtEnd() ? "Expected a key" : "Expected a key, but found " + describeHere() + keyQuotingHint() + slashCommentHint());
		}

		index = end;
		return cachedKey(start, end, hash);
	}

	private String cachedKey(int start, int end) {
		if (end - start > MAX_CACHED_KEY_LENGTH) {
			return source.substring(start, end);
		}

		var hash = 0;

		for (var offset = start; offset < end; offset++) {
			hash = (31 * hash) + characters[offset];
		}

		return cachedKey(start, end, hash);
	}

	/*
	The key from `start` to `end`, whose characters give `hash` as `String.hashCode()` does, from the cache when it is there.
	*/
	private String cachedKey(int start, int end, int hash) {
		var count = end - start;

		if (count > MAX_CACHED_KEY_LENGTH) {
			return source.substring(start, end);
		}

		var slot = (hash ^ (hash >>> 16)) & (KEY_CACHE_SIZE - 1);
		var cached = keys[slot];

		// The hash code of a string is cached in it, so a different one rules it out without reading its characters.
		if (cached != null && cached.hashCode() == hash && cached.length() == count && source.startsWith(cached, start)) {
			return cached;
		}

		var key = source.substring(start, end);
		keys[slot] = key;
		return key;
	}

	/*
	A decoded quoted key shares the same bounded cache as a bare key, including one read in an earlier document.
	*/
	private String cachedKey(String key) {
		if (key.length() > MAX_CACHED_KEY_LENGTH) {
			return key;
		}

		var hash = key.hashCode();
		var slot = (hash ^ (hash >>> 16)) & (KEY_CACHE_SIZE - 1);
		var cached = keys[slot];

		if (cached != null && cached.equals(key)) {
			return cached;
		}

		keys[slot] = key;
		return key;
	}

	/*
	The hint for a key that starts with a character a bare key cannot hold, such as the `$` in `$schema`, or nothing for a character with another meaning, such as `}`.
	*/
	private String keyQuotingHint() {
		if (!isQuotableKeyStart(index)) {
			return "";
		}

		// A longer key gets the hint without the example.
		var limit = Math.min(source.length(), index + MAX_DIAGNOSED_LENGTH);
		var end = index;

		while (end < limit) {
			var codePoint = source.codePointAt(end);

			if (!isQuotableKeyCharacter(codePoint)) {
				break;
			}

			end += Character.charCount(codePoint);
		}

		if (end >= limit || source.charAt(end) != ':') {
			return KEY_QUOTING_HINT;
		}

		return KEY_QUOTING_HINT + ", as in '" + abbreviate(source.substring(index, end)) + "'";
	}

	private boolean isQuotableKeyStart(int offset) {
		return offset < source.length() && isQuotableKeyCharacter(source.codePointAt(offset));
	}

	/*
	The characters that may be meant as part of a key: visible ones that have no meaning in the grammar, which includes every bare key character. A `=` is left out, because after a key, as in `name=foo`, it was most likely meant as the `:` of INI and TOML, so a key that holds a `=` gets no quoting hint.
	*/
	private static boolean isQuotableKeyCharacter(int codePoint) {
		if ("\"#'*,/:=[]{}".indexOf(codePoint) != -1) {
			return false;
		}

		return !isDefaultIgnorable(codePoint) && !isOther(codePoint) && !isWhiteSpace(codePoint);
	}

	/*
	A `.` after a key, as in `example.com: 1`. A key is never a path, so the `.` is meant either as part of the key or as nesting.
	*/
	private SomlException failDotInKey(int keyStart) {
		var end = index;

		while (end < keyStart + MAX_DIAGNOSED_LENGTH && (Writer.isBareKeyCharacter(code(end)) || code(end) == '.')) {
			end++;
		}

		var key = source.substring(keyStart, end);
		var words = key.split("\\.", -1);

		// The suggestions are only given for a bare key whose dots each have a word on both sides, so that both are valid.
		if (code(end) != ':' || !Writer.isBareKeyCharacter(code(keyStart)) || Arrays.asList(words).contains("")) {
			return fail("A key cannot contain “.” unless it is quoted. Quote the whole key, or use braces to nest, as in a: {b: …}");
		}

		var nested = String.join(": {", words) + ": …" + "}".repeat(words.length - 1);
		return fail("A bare key cannot contain “.”. Quote it, as in '" + abbreviate(key) + "', or use braces to nest, as in " + abbreviate(nested));
	}

	private @Nullable SomlValue parseObject(int depth) {
		var members = members(depth);
		var offsets = positions == null ? null : IntStream.builder();

		if (sink != null) {
			sink.startObject();
		}

		parseItems(depth, '}', members, null, offsets);

		if (sink != null) {
			sink.endObject();
			return null;
		}

		return record(members.toObject(), offsets);
	}

	private @Nullable SomlValue parseArray(int depth) {
		var items = sink == null ? items(depth) : null;
		var offsets = positions == null ? null : IntStream.builder();

		if (sink != null) {
			sink.startArray();
		}

		parseItems(depth, ']', null, items, offsets);

		if (sink != null) {
			sink.endArray();
			return null;
		}

		return record(Objects.requireNonNull(items).toArray(), offsets);
	}

	/*
	The scratch members of an object at `depth`, which is reused for every object at that depth, because only one is open at a time. An object is copied out of it at its exact size when it closes.
	*/
	private Members members(int depth) {
		var members = membersByDepth[depth];

		if (members == null) {
			members = new Members();
			membersByDepth[depth] = members;
		}

		members.reset();
		return members;
	}

	private Items items(int depth) {
		var items = itemsByDepth[depth];

		if (items == null) {
			items = new Items();
			itemsByDepth[depth] = items;
		}

		items.reset();
		return items;
	}

	/*
	`{` or `[` at depth `depth`, then items separated by a comma, a line break, or both, with an optional trailing comma, then the `closing` bracket. A comma must be on the line of the item before it. The members of an object go to `members`, and the items of an array to `items`.
	*/
	private void parseItems(int depth, char closing, @Nullable Members members, @Nullable Items items, IntStream.@Nullable Builder offsets) {
		if (depth > Writer.MAX_DEPTH) {
			throw fail("The document is nested more than " + Writer.MAX_DEPTH + " levels deep");
		}

		var start = index;
		index++;
		skipTrivia();

		while (code() != closing) {
			var hasLineBreak = false;
			var itemEnd = index;

			if (!isAtEnd()) {
				if (members != null) {
					parseEntry(members, depth, offsets);
				} else {
					if (offsets != null) {
						offsets.add(index);
					}

					var item = parseValue(depth + 1);

					if (sink == null) {
						// An array has `items` instead of `members`, and without a sink, the parser makes every value.
						Objects.requireNonNull(items).add(Objects.requireNonNull(item));
					} else if (item != null) {
						sink.scalar(item);
					}
				}

				itemEnd = index;
				hasLineBreak = skipTrivia();
			}

			if (isAtEnd()) {
				throw fail("Unterminated " + (closing == '}' ? "object" : "array") + ": expected “" + closing + "”", start);
			}

			if (code() == ',') {
				if (hasLineBreak) {
					throw fail("A comma must be on the same line as the item before it. The line break already separates the items, so remove the comma");
				}

				index++;
				skipTrivia();
			} else if (!hasLineBreak && code() != closing) {
				var item = closing == '}' ? "an object member" : "an array item";
				// Without a line break that separates, a line break in the gap is inside a block comment.
				var lineBreak = source.indexOf('\n', itemEnd, index);
				var hint = lineBreak != -1 ? ". A line break inside a block comment does not separate items" : slashCommentHint();
				throw fail("Expected “,”, a line break, or “" + closing + "” after " + item + ", but found " + describeHere() + hint);
			}
		}

		index++;
	}

	private @Nullable SomlValue parseValue(int depth) {
		var code = code();

		if (code == '{') {
			return parseObject(depth);
		}

		if (code == '[') {
			return parseArray(depth);
		}

		var start = index;
		SomlValue value;
		// An int or a float written with digits.
		var isNumber = false;

		if (code == '\'' || code == '"') {
			// When this parser is `CURRENT`, `SomlString` skips its check for this exact string, by identity, because the scan has checked it.
			checkedString = parseString();
			value = new SomlString(checkedString);
		} else if (code == 't' && isKeyword("true")) {
			value = SomlBool.TRUE;
		} else if (code == 'f' && isKeyword("false")) {
			value = SomlBool.FALSE;
		} else if (code == 'n' && isKeyword("null")) {
			value = SomlNull.INSTANCE;
		} else if (code == 'i' && isKeyword("infinity")) {
			value = new SomlFloat(Double.POSITIVE_INFINITY);
		} else if (code == '-' && isKeyword("-infinity")) {
			value = new SomlFloat(Double.NEGATIVE_INFINITY);
		} else if (code == '-' || isDigit(code)) {
			value = parseNumberOrTemporal();
			isNumber = value instanceof SomlInt || value instanceof SomlFloat;
		} else {
			throw failUnexpectedValue();
		}

		if (!isValueEnd(code())) {
			throw failAfterValue(start, isNumber);
		}

		if (isNumber && isSpace(code())) {
			diagnoseUnitAfterSpace(start);
		}

		return value;
	}

	/*
	A character that cannot follow the value that starts at `start`.
	*/
	private SomlException failAfterValue(int start, boolean isNumber) {
		var code = code();

		// Go writes microseconds as `µs`, with the micro sign or the Greek letter mu. The duration is only suggested when it is valid, which a number with an exponent or a radix prefix, for example, is not.
		if (isNumber && (code == 0xB5 || code == 0x3BC) && code(index + 1) == 's') {
			var number = source.substring(start, index);
			return fail("The unit for microseconds is written us" + (isValidValue(number + "us") ? ", as in " + abbreviate(number) + "us" : ""));
		}

		// A `''` inside a '...' string, as SQL and YAML escape a quote, ends the string.
		if (code == '\'' && code(start) == '\'') {
			return fail("There is no '' escape in a '...' string. Write a string that contains ' as \"...\", as in \"it's\"");
		}

		return fail("Unexpected " + describeHere() + " after a value");
	}

	/*
	A number followed by a space and a word, such as `512 MiB` or `10 seconds`, which is an error anyway. A word followed by more than spaces, a separator, or a comment is left to the general errors, because it may be a key, as in `a: 1 b: 2`, or a sentence.
	*/
	private void diagnoseUnitAfterSpace(int start) {
		var unitStart = skipSpaces(index);
		var unitEnd = unitStart;

		while (isLetter(code(unitEnd))) {
			unitEnd++;
		}

		var unit = source.substring(unitStart, Math.min(unitEnd, unitStart + MAX_DIAGNOSED_LENGTH));

		// A keyword after a number is a missing comma.
		if (unit.isEmpty() || !isValueEndAt(skipSpaces(unitEnd)) || (unit.equals("true") || unit.equals("false") || unit.equals("null") || unit.equals("infinity"))) {
			return;
		}

		var number = source.substring(start, index);
		// The duration is only suggested when it is valid. The string is always offered, because a unit such as `m` may mean meters rather than minutes.
		var duration = DurationUnit.parse(unit, 0, unit.length()) != null && isValidValue(number + unit) ? abbreviate(number) + unit : "10s";
		throw fail("A unit cannot follow a number after a space. Write a duration without the space, as in " + duration + ", and anything else, such as a size, as a string, as in '" + abbreviate(number + " " + unit) + "'", unitStart);
	}

	private boolean isKeyword(String word) {
		if (!source.startsWith(word, index)) {
			return false;
		}

		// A longer word, such as `nullable`, is not the keyword.
		if (Writer.isBareKeyCharacter(code(index + word.length()))) {
			return false;
		}

		index += word.length();
		return true;
	}

	private SomlException failUnexpectedValue() {
		if (isAtEnd()) {
			return fail("Expected a value, but reached the end of the document");
		}

		var code = code();

		if (code == '+') {
			return fail("A “+” sign is not allowed. A number without a sign is positive");
		}

		// A `.` that no digit follows begins a string, such as `.env` or `./foo`, rather than a number.
		if (code == '.') {
			return fail(isDigit(code(index + 1)) ? "A number cannot begin with “.”; write a digit before it, as in 0.5" : describeUnknownWord(".", unquotedText(index)));
		}

		var wordEnd = index;

		while (Writer.isBareKeyCharacter(code(wordEnd))) {
			wordEnd++;
		}

		if (wordEnd > index) {
			var word = source.substring(index, Math.min(wordEnd, index + MAX_DIAGNOSED_LENGTH));

			// A key where a value should be, as when the value of an entry is left out and the next line has a key, or as in `[a: 1]`, is reported as a key. A word after the `:` of a member and a space, as in `msg: Error: file not found` or `url: https://example.com`, is that member's value instead, an unquoted string. Without the space, as in `a:b: 1`, the first `:` was most likely meant as part of the key.
			var spacesStart = skipSpacesBack(index);
			var isMemberValue = spacesStart < index && code(spacesStart - 1) == ':';

			if (!isMemberValue && code(wordEnd) == ':') {
				return fail("Expected a value, but found the key " + abbreviate(word));
			}

			diagnoseTableHeader(true);
			return fail(describeUnknownWord(word, unquotedText(index)));
		}

		return fail("Expected a value, but found " + describeHere() + (isBlockScalarIndicator() ? ". Write a multiline string as a block string, between ''' lines" : slashCommentHint()));
	}

	/*
	The text from `start` that was most likely meant as one unquoted string, such as `John Smith`: up to the end of the line, a comma, a closing bracket, or a comment after a space or a tab, without the spaces and tabs at its end.
	*/
	private String unquotedText(int start) {
		var limit = Math.min(source.length(), start + MAX_DIAGNOSED_LENGTH);
		var end = start;

		while (end < limit && !isUnquotedTextEnd(end)) {
			end++;
		}

		return source.substring(start, Math.max(start, skipSpacesBack(end)));
	}

	private boolean isUnquotedTextEnd(int offset) {
		var code = code(offset);
		var isComment = code == '#' || (code == '/' && code(offset + 1) == '*');
		return code == '\n' || code == ',' || code == ']' || code == '}' || (isComment && isSpace(code(offset - 1)));
	}

	/*
	A TOML table header, as in `[server]` or `[[servers]]` on a line of its own, reads as an array that holds a word, or as a key that starts with "[". Where a value is expected, `isValue`, it is only a table header when its bracket opens the document, because a table cannot be written as an item of an array inside it.
	*/
	private void diagnoseTableHeader(boolean isValue) {
		var lineStart = source.lastIndexOf('\n', index - 1) + 1;

		if (isValue && skipSpaces(lineStart) != documentStart) {
			return;
		}

		var lineEnd = Math.min(findLineEnd(index), lineStart + MAX_DIAGNOSED_LENGTH);
		var match = TABLE_HEADER.matcher(source).region(lineStart, lineEnd);

		if (match.matches()) {
			throw fail("There are no table headers. Write the table as an object, as in " + abbreviate(match.group("name")) + ": {…}");
		}
	}

	/*
	A YAML literal block scalar indicator, as in `key: |` or `key: |-`, at the end of its line.
	*/
	private boolean isBlockScalarIndicator() {
		// A folded block scalar, `>`, joins its lines, which a block string does not, so only `|` gets the hint.
		if (code() != '|') {
			return false;
		}

		var chomping = code(index + 1);
		var end = skipSpaces(chomping == '-' || chomping == '+' ? index + 2 : index + 1);
		var next = code(end);
		return next == '\n' || next == -1;
	}

	/*
	The hint for a `//` comment, as in JavaScript.
	*/
	private String slashCommentHint() {
		return code() == '/' && code(index + 1) == '/' ? ". A comment starts with “#”" : "";
	}

	private String describeHere() {
		if (isAtEnd()) {
			return "the end of the document";
		}

		var codePoint = source.codePointAt(index);
		return codePoint == '\n' ? "a line break" : describeCharacter(codePoint);
	}

	private String parseString() {
		var code = code();

		if (code(index + 1) == code && code(index + 2) == code) {
			return parseBlockString((char) code);
		}

		return code == '\'' ? parseLiteralString() : parseEscapedString();
	}

	/*
	`'...'` has no escapes, so its value is the source text between the quotes.
	*/
	private String parseLiteralString() {
		var start = index;
		var end = skipContent(start + 1, length, LANES_APOSTROPHE, LANES_APOSTROPHE);

		if (end >= length || characters[end] == '\n') {
			throw failUnterminatedString(start);
		}

		index = end + 1;
		return source.substring(start + 1, end);
	}

	private SomlException failUnterminatedString(int start) {
		var reason = characters[start] == '\''
			? "Unterminated string. A '...' string must end on the line it starts on; use a block string (''') for multiple lines"
			: "Unterminated string. A \"...\" string must end on the line it starts on; use a block string (\"\"\") for multiple lines";
		return fail(reason, start);
	}

	private String parseEscapedString() {
		return parseEscapedString(skipContent(index + 1, length, LANES_QUOTATION_MARK, LANES_BACKSLASH));
	}

	/*
	Decodes an escaped string from its first already-validated quote or backslash, reusing the key scanner's work when called for a key.
	*/
	private String parseEscapedString(int special) {
		var start = index;
		var chunkStart = start + 1;
		StringBuilder value = null;

		for (;;) {
			if (special >= length || characters[special] == '\n') {
				throw failUnterminatedString(start);
			}

			if (characters[special] == '"') {
				index = special + 1;

				if (value == null) {
					return source.substring(chunkStart, special);
				}

				return value.append(source, chunkStart, special).toString();
			}

			if (value == null) {
				value = new StringBuilder(Math.min(8192, special - start + 16));
			}

			value.append(source, chunkStart, special);
			chunkStart = parseEscape(special, value);
			special = skipContent(chunkStart, length, LANES_QUOTATION_MARK, LANES_BACKSLASH);
		}
	}

	/*
	Decodes the escape at `offset`, which is a backslash, into `value`, and returns the offset after it.
	*/
	private int parseEscape(int offset, StringBuilder value) {
		var character = code(offset + 1);

		switch (character) {
			case '\\', '"', 'n', 't' -> {
				value.append(switch (character) {
					case 'n' -> '\n';
					case 't' -> '\t';
					default -> (char) character;
				});
				return offset + 2;
			}
			case 'u' -> {
				var hexStart = offset + 3;
				var hexEnd = hexStart;

				// Uppercase digits too, so that the value of such an escape is checked before its spelling.
				if (code(offset + 2) == '{') {
					while (hexEnd < hexStart + 6 && HexFormat.isHexDigit(code(hexEnd))) {
						hexEnd++;
					}
				}

				if (hexEnd == hexStart || code(hexEnd) != '}') {
					throw fail(describeBadUnicodeEscape(offset + 1), offset);
				}

				var codePoint = HexFormat.fromHexDigits(source, hexStart, hexEnd);

				// The value is checked before the spelling, so that the uppercase and leading zeros errors never point to an escape that is not allowed either.
				if (codePoint == 0x0D) {
					throw fail("A carriage return (U+000D) cannot be represented, so \\u{" + source.substring(hexStart, hexEnd) + "} is not allowed", offset);
				}

				if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
					throw fail("\\u{" + source.substring(hexStart, hexEnd) + "} is a surrogate, which is not a Unicode scalar value", offset);
				}

				if (codePoint > Character.MAX_CODE_POINT) {
					throw fail("\\u{" + source.substring(hexStart, hexEnd) + "} is above U+10FFFF, the largest Unicode scalar value", offset);
				}

				for (var position = hexStart; position < hexEnd; position++) {
					if (characters[position] >= 'A' && characters[position] <= 'F') {
						throw fail("A Unicode escape uses lowercase hexadecimal digits", offset);
					}
				}

				if (hexEnd - hexStart > 1 && characters[hexStart] == '0') {
					throw fail("A Unicode escape may not have leading zeros; write \\u{" + Integer.toHexString(codePoint) + "}", offset);
				}

				value.appendCodePoint(codePoint);
				return hexEnd + 1;
			}
			default -> {
				// A backslash at the end of the document is the same mistake as one at the end of a line.
				var reason = switch (character) {
					case 'r' -> "There is no \\r escape, because a carriage return cannot be represented";
					case '\'' -> "A ' needs no escape inside \"...\"";
					case '\n', -1 -> "A backslash must be followed by an escape character. Use \\\\ for a literal backslash, or a '...' string";
					default -> "Unknown escape “\\" + Character.toString(source.codePointAt(offset + 1)) + "”. The escapes are \\\\, \\\", \\n, \\t, and \\u{…}; use a '...' string for literal backslashes";
				};

				throw fail(reason, offset);
			}
		}
	}

	private static String stripLeadingZeros(String digits) {
		var start = 0;

		while (start < digits.length() - 1 && digits.charAt(start) == '0') {
			start++;
		}

		return digits.substring(start);
	}

	/*
	A block string opens with a run of three or more quotes and a line break, and closes at the first line whose first non-whitespace content is a run of exactly that many quotes. The closing line's indentation is removed from every content line, except a blank one, which holds only spaces and tabs and becomes an empty line.
	*/
	private String parseBlockString(char quote) {
		var start = index;
		var delimiterLength = 0;

		while (code(start + delimiterLength) == quote) {
			delimiterLength++;
		}

		index = start + delimiterLength;

		if (isAtEnd()) {
			throw fail("Unterminated block string", start);
		}

		// As in Swift, the opening delimiter is followed directly by a line break, not even by trailing whitespace.
		if (code() != '\n') {
			throw fail("A block string's opening delimiter must be followed directly by a line break, and its content starts on the next line");
		}

		var contentStart = index + 1;
		var closingLineStart = -1;
		var closingDelimiterStart = -1;

		for (var lineStart = contentStart; lineStart < source.length(); lineStart = findLineEnd(lineStart) + 1) {
			var delimiterStart = skipSpaces(lineStart);
			var runLength = 0;

			while (code(delimiterStart + runLength) == quote) {
				runLength++;
			}

			if (runLength == delimiterLength) {
				closingLineStart = lineStart;
				closingDelimiterStart = delimiterStart;
				break;
			}
		}

		if (closingLineStart == -1) {
			throw fail("Unterminated block string" + describeInlineClosingDelimiter(contentStart, quote, delimiterLength), start);
		}

		var contentOffset = contentStart;

		while (contentOffset < closingLineStart) {
			contentOffset = skipContent(contentOffset, closingLineStart, LANES_NONE, LANES_NONE) + 1;
		}

		// Note: Not using `String.regionMatches()` because reusing one indentation prefix per block benchmarks faster than comparing source ranges on every line.
		var indentation = source.substring(closingLineStart, closingDelimiterStart);
		index = closingDelimiterStart + delimiterLength;
		// Track the first and last nonblank lines without retaining an offset for every line. A blank line becomes an empty line.
		var firstContentStart = -1;
		var lastContentEnd = -1;

		for (int lineStart = contentStart, lineEnd; lineStart < closingLineStart; lineStart = lineEnd + 1) {
			lineEnd = findLineEnd(lineStart);

			// A blank line, which is empty or holds only spaces and tabs, may leave out the indentation. Swift is stricter here: there only a completely empty line may leave it out.
			if (skipSpaces(lineStart) >= lineEnd) {
				continue;
			}

			if (!source.startsWith(indentation, lineStart)) {
				throw fail("This line does not start with the indentation of its block string's closing delimiter. Every line except a blank one must start with exactly the same spaces and tabs", lineStart);
			}

			if (firstContentStart == -1) {
				firstContentStart = lineStart;
			}

			lastContentEnd = lineEnd;
		}

		// Blank lines directly after the opening delimiter and directly before the closing one are not content.
		var value = new StringBuilder(Math.min(8192, Math.max(0, lastContentEnd - firstContentStart)));

		for (int lineStart = firstContentStart, lineEnd; lineStart < lastContentEnd; lineStart = lineEnd + 1) {
			lineEnd = findLineEnd(lineStart);

			if (lineStart > firstContentStart) {
				value.append('\n');
			}

			if (skipSpaces(lineStart) >= lineEnd) {
				continue;
			}

			var textStart = lineStart + indentation.length();

			if (quote == '\'') {
				value.append(source, textStart, lineEnd);
			} else {
				unescapeLine(textStart, lineEnd, value);
			}
		}

		return value.toString();
	}

	/*
	The hint for a content line that ends with the delimiter, as TOML allows. Adding a closing line after it would keep the delimiter as content.
	*/
	private String describeInlineClosingDelimiter(int contentStart, char quote, int delimiterLength) {
		if (delimiterLength > MAX_DIAGNOSED_LENGTH) {
			return "";
		}

		var delimiter = String.valueOf(quote).repeat(delimiterLength);
		var line = 1;

		// Note: Not using `String.indexOf()` repeatedly because a single character scan benchmarks faster for short diagnostic lines.
		for (var offset = 0; offset < contentStart; offset++) {
			if (source.charAt(offset) == '\n') {
				line++;
			}
		}

		for (var lineStart = contentStart; lineStart < source.length(); lineStart = findLineEnd(lineStart) + 1) {
			var textStart = skipSpaces(lineStart);
			var textEnd = Math.max(textStart, skipSpacesBack(findLineEnd(lineStart)));
			var quoteEnd = textStart;

			while (quoteEnd < textEnd && characters[quoteEnd] == quote) {
				quoteEnd++;
			}

			// A line of only quotes, longer than the delimiter, is content.
			if (textEnd - textStart >= delimiterLength && source.startsWith(delimiter, textEnd - delimiterLength) && quoteEnd < textEnd) {
				return ". Its closing delimiter must start a line, so move the " + delimiter + " at the end of line " + line + " to a new line";
			}

			line++;
		}

		return "";
	}

	/*
	Decodes the escapes of one content line of a `"""` block. The search for a backslash stops at the end of the line, so that a document with many lines stays linear.
	*/
	private void unescapeLine(int lineStart, int lineEnd, StringBuilder value) {
		var chunkStart = lineStart;

		for (var offset = source.indexOf('\\', chunkStart, lineEnd); offset != -1; offset = source.indexOf('\\', chunkStart, lineEnd)) {
			value.append(source, chunkStart, offset);
			chunkStart = parseEscape(offset, value);
		}

		value.append(source, chunkStart, lineEnd);
	}

	private SomlValue parseNumberOrTemporal() {
		var plain = parsePlainNumber();

		if (plain == null && isDigit(code()) && code(index + 4) == '-') {
			plain = parsePlainInstant();
		}

		if (plain != null) {
			return plain;
		}

		var start = index;
		var end = findNumberEnd(start);
		var text = source.substring(start, end);
		index = end;

		if (InstantFields.hasDatePrefix(text)) {
			// A date followed by a time of day, as TOML permits with a space, still gets the reference's separator diagnostic, while a following ordinary value gets the container's missing-separator error.
			if (text.length() == 10 && !isTimePrefix(skipSpaces(index))) {
				return parseDate(text, start);
			}

			return parseInstant(text, start);
		}

		var kind = classifyNumber(text);

		if (kind == NumberKind.NONE && isDurationLike(text)) {
			return parseDuration(text, start);
		}

		return switch (kind) {
			case INT -> {
				if (text.equals("-0")) {
					throw fail("“-0” is not allowed, because zero has one spelling: 0", start);
				}

				yield new SomlInt(parseInteger(text, start));
			}
			case FLOAT -> {
				// The grammar is checked already, so `parseDouble` never gets a `+`, `NaN`, `Infinity`, a hexadecimal float, or a suffix, which it would accept.
				var value = Double.parseDouble(text.replace("_", ""));

				if (Double.isInfinite(value)) {
					throw fail(abbreviate(text) + " is too large to be a finite float. Use " + (value < 0 ? "-infinity" : "infinity") + " if you mean it", start);
				}

				if (value == 0) {
					// A nonzero digit before the exponent means the literal is not zero, so it underflowed.
					var exponent = text.indexOf('e');
					var mantissaEnd = exponent == -1 ? text.length() : exponent;

					for (var offset = 0; offset < mantissaEnd; offset++) {
						var character = text.charAt(offset);

						if (character >= '1' && character <= '9') {
							throw fail(abbreviate(text) + " is too small to be told apart from zero. Write 0.0 if you mean zero", start);
						}
					}
				}

				yield new SomlFloat(value);
			}
			case NONE -> {
				var thousands = describeThousandsSeparator(text, start);
				throw fail(thousands != null ? thousands : describeBadNumber(text, unquotedText(start)), start);
			}
		};
	}

	/*
	Whether a time of day, as `9:00` or `12:00`, starts at `offset`.
	*/
	private boolean isTimePrefix(int offset) {
		if (!isDigit(code(offset))) {
			return false;
		}

		var colon = isDigit(code(offset + 1)) ? offset + 2 : offset + 1;
		return code(colon) == ':' && isDigit(code(colon + 1)) && isDigit(code(colon + 2));
	}

	/*
	A comma used as a thousands separator, as in `[1,000]`, makes the group after it a separate item, which fails only when it has a leading zero. Writing that item in octal, as the leading zero message suggests, would parse to the wrong items.
	*/
	private @Nullable String describeThousandsSeparator(String text, int start) {
		if (!(text.length() == 3 && text.charAt(0) == '0' && isDigit(text.charAt(1)) && isDigit(text.charAt(2))) || code(start - 1) != ',') {
			return null;
		}

		var groupEnd = start - 1;
		var numberStart = getGroupStart(groupEnd);

		// Earlier groups of three digits, as in `12,345,000`.
		while (groupEnd - numberStart == 3 && code(numberStart - 1) == ',' && isDigit(code(numberStart - 2))) {
			groupEnd = numberStart - 1;
			numberStart = getGroupStart(groupEnd);
		}

		var before = code(numberStart - 1);

		// The first group has one to three digits, which are not the end of a float, of a number with underscores, or of a number with a radix prefix.
		if (numberStart == groupEnd || before == '.' || before == '_' || numberStart < groupEnd - 3 || isLetter(before)) {
			return null;
		}

		// The sign belongs to the number, as in `-1,000`.
		if (before == '-') {
			numberStart--;
		}

		var groupsStart = start + text.length();
		var groupsEnd = groupsStart;

		// Further groups of exactly three digits.
		while (code(groupsEnd) == ',' && isDigit(code(groupsEnd + 1)) && isDigit(code(groupsEnd + 2)) && isDigit(code(groupsEnd + 3)) && !isDigit(code(groupsEnd + 4)) && groupsEnd + 4 <= groupsStart + MAX_DIAGNOSED_LENGTH) {
			groupsEnd += 4;
		}

		var number = source.substring(numberStart, Math.min(groupsEnd, numberStart + MAX_DIAGNOSED_LENGTH));
		var reason = "Leading zeros are not allowed in a decimal number. A comma separates items, so " + abbreviate(number) + " is not one number";

		if (groupsEnd - numberStart > MAX_DIAGNOSED_LENGTH) {
			return reason;
		}

		// Both spellings are the same int, so they are suggested only when it is in range.
		var withUnderscores = number.replace(',', '_');
		return isValidValue(withUnderscores) ? reason + ". Write it as " + abbreviate(withUnderscores) + " or " + abbreviate(number.replace(",", "")) : reason;
	}

	private int getGroupStart(int groupEnd) {
		var groupStart = groupEnd;

		while (isDigit(code(groupStart - 1))) {
			groupStart--;
		}

		return groupStart;
	}

	/*
	The common case, a short decimal int or float such as `8080`, `-3`, or `30.5`, without the general path's maximal-run scan and classification. Anything else, including every error, returns null and is left to the general path.
	*/
	private @Nullable SomlValue parsePlainNumber() {
		var start = index;
		var offset = code(start) == '-' ? start + 1 : start;
		var integerStart = offset;
		var significand = 0L;

		while (isDigit(code(offset))) {
			if (offset - integerStart == 18) {
				return null;
			}

			significand = (significand * 10) + (characters[offset] - '0');
			offset++;
		}

		var integerLength = offset - integerStart;

		// No digits, or a leading zero followed by more digits, which the general path reports as an error or reads as a date or an instant.
		if (integerLength == 0 || (integerLength > 1 && code(integerStart) == '0')) {
			return null;
		}

		var isFloat = false;
		var fractionDigits = 0;

		if (code(offset) == '.') {
			var fractionStart = offset + 1;
			offset = fractionStart;

			while (isDigit(code(offset))) {
				if (offset - integerStart >= 15) {
					return null;
				}

				significand = (significand * 10) + (characters[offset] - '0');
				offset++;
			}

			if (offset == fractionStart) {
				return null;
			}

			isFloat = true;
			fractionDigits = offset - fractionStart;
		}

		// At most 18 integer digits fit a long. A float has at most 15 characters including its point, so it needs no slow path. A character that may continue a number, such as `e`, `_`, or `-`, needs the general path.
		if (!isValueEnd(code(offset))) {
			return null;
		}

		if (isFloat) {
			// Clinger's fast path: at most 14 digits fit a double exactly, and so does a power of ten up to 10^22, so one division, which IEEE 754 rounds correctly, gives the correctly rounded value of the decimal.
			// The limit of 15 above counts the `.`, so a float here has at most 14 digits.
			var value = significand / POWERS_OF_TEN[fractionDigits];
			index = offset;
			return new SomlFloat(start == integerStart ? value : -value);
		}

		// `-0` is an error, which the general path reports.
		if (significand == 0 && offset - start > 1) {
			return null;
		}

		index = offset;
		return new SomlInt(start == integerStart ? significand : -significand);
	}

	private static final double[] POWERS_OF_TEN = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15};

	/*
	The common instant, as `2026-09-19T14:00:00Z`, `2026-09-19T14:00:00.5Z`, or with an offset such as `+02:00`, read from the characters without the general path's token and parts. Anything else, including every error, returns null and is left to the general path, which reports it.
	*/
	private @Nullable SomlValue parsePlainInstant() {
		var start = index;

		// The shortest instant, as `2026-09-19T14:00:00Z`, has 20 characters, so the fixed fields below are inside the document.
		if (start + 20 > length || characters[start + 4] != '-' || characters[start + 7] != '-' || characters[start + 10] != 'T' || characters[start + 13] != ':' || characters[start + 16] != ':') {
			return null;
		}

		var year = digits(start, 4);
		var month = digits(start + 5, 2);
		var day = digits(start + 8, 2);
		var hour = digits(start + 11, 2);
		var minute = digits(start + 14, 2);
		var second = digits(start + 17, 2);

		if (SomlDate.calendarError(year, month, day) != null || hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59) {
			return null;
		}

		var offset = start + 19;
		var nanoseconds = 0;

		if (code(offset) == '.') {
			var fractionStart = offset + 1;
			offset = fractionStart;

			while (offset < length && isDigit(characters[offset])) {
				offset++;
			}

			var count = offset - fractionStart;

			if (count == 0 || count > 9) {
				return null;
			}

			nanoseconds = digits(fractionStart, count);

			nanoseconds *= (int) POWERS_OF_TEN[9 - count];
		}

		var offsetSeconds = 0;

		if (code(offset) == 'Z') {
			offset++;
		} else if ((code(offset) == '+' || code(offset) == '-') && offset + 6 <= length && characters[offset + 3] == ':') {
			var offsetHour = digits(offset + 1, 2);
			var offsetMinute = digits(offset + 4, 2);
			var isNegative = characters[offset] == '-';

			if (offsetHour < 0 || offsetHour > 23 || offsetMinute < 0 || offsetMinute > 59 || (isNegative && offsetHour == 0 && offsetMinute == 0)) {
				return null;
			}

			offsetSeconds = ((offsetHour * 3600) + (offsetMinute * 60)) * (isNegative ? -1 : 1);
			offset += 6;
		} else {
			return null;
		}

		// The general path reads the longest run of number characters, so an instant that one follows is its error to report.
		if (isNumberCharacter(code(offset))) {
			return null;
		}

		var seconds = (daysFromCivil(year, month, day) * 86_400L) + (hour * 3600L) + (minute * 60L) + second - offsetSeconds;
		var instant = Instant.ofEpochSecond(seconds, nanoseconds);

		if (instant.isBefore(SomlInstant.MIN) || instant.isAfter(SomlInstant.MAX)) {
			return null;
		}

		index = offset;
		return new SomlInstant(instant);
	}

	/*
	The decimal number of `count` digits at `offset`, or -1 when one of them is not a digit.
	*/
	private int digits(int offset, int count) {
		var number = 0;

		for (var position = offset; position < offset + count; position++) {
			var character = characters[position];

			if (!isDigit(character)) {
				return -1;
			}

			number = (number * 10) + (character - '0');
		}

		return number;
	}

	/*
	The grammar has already been checked. Remove radix prefixes and their leading zeros before normalizing underscores, so even a huge integer needs only a bounded temporary string or exception message.
	*/
	private long parseInteger(String text, int start) {
		// The grammar is checked already, so only a radix int can start with zero and contain more characters. A decimal zero has one spelling, and a radix int has no sign.
		var isRadix = text.length() > 1 && text.charAt(0) == '0';
		var radix = 10;
		var digitsStart = 0;
		var maximumDigits = 19;

		if (isRadix) {
			radix = switch (text.charAt(1)) {
				case 'x' -> 16;
				case 'o' -> 8;
				default -> 2;
			};
			maximumDigits = switch (radix) {
				case 16 -> 16;
				case 8 -> 21;
				default -> 63;
			};
			digitsStart = 2;

			while (digitsStart < text.length() && (text.charAt(digitsStart) == '0' || text.charAt(digitsStart) == '_')) {
				digitsStart++;
			}

			if (digitsStart == text.length()) {
				return 0;
			}
		}

		// Single underscores can at most double the digit count minus one. The decimal sign adds one character.
		if (text.length() - digitsStart > maximumDigits * 2 - (text.charAt(0) == '-' ? 0 : 1)) {
			throw failIntegerRange(text, start);
		}

		var digits = text;
		var numberStart = digitsStart;

		if (text.indexOf('_', digitsStart) != -1) {
			digits = text.substring(digitsStart).replace("_", "");
			numberStart = 0;
		}

		try {
			// Read as a signed number, so a 64-bit pattern with the top bit set, such as `0xFFFFFFFFFFFFFFFF`, is out of range rather than a negative int, as the spec requires.
			// Note: Not using `Long.parseUnsignedLong()` because radix integers with the sign bit set must be rejected, not converted to negative values.
			return Long.parseLong(digits, numberStart, digits.length(), radix);
		} catch (NumberFormatException _) {
			throw failIntegerRange(text, start);
		}
	}

	private SomlException failIntegerRange(String text, int start) {
		return fail("The integer " + abbreviate(text) + " is outside the 64-bit range (-9223372036854775808 to 9223372036854775807)", start);
	}

	/*
	An offset on its own after spaces, as in `2026-09-19T14:00:00 Z`, which belongs directly after the time, or null.
	*/
	private @Nullable String findOffsetAfterSpace() {
		var offsetStart = skipSpaces(index);

		if (offsetStart == index) {
			return null;
		}

		// An offset on its own, as after a space in `2026-09-19T14:00:00 Z`.
		var end = offsetStart;

		if (code(offsetStart) == 'Z') {
			end++;
		} else if ((code(offsetStart) == '+' || code(offsetStart) == '-') && offsetStart + 6 <= length && digits(offsetStart + 1, 2) != -1 && code(offsetStart + 3) == ':' && digits(offsetStart + 4, 2) != -1) {
			end += 6;
		}

		return end != offsetStart && isValueEndAt(end) ? source.substring(offsetStart, end) : null;
	}

	/*
	A whole date token, validated as Gregorian components without a time or timezone.
	*/
	private SomlValue parseDate(String text, int start) {
		var year = Integer.parseInt(text, 0, 4, 10);
		var month = Integer.parseInt(text, 5, 7, 10);
		var day = Integer.parseInt(text, 8, 10, 10);
		var error = SomlDate.calendarError(year, month, day);

		if (error != null) {
			throw fail("Invalid date " + text + ": " + error, start);
		}

		return new SomlDate(LocalDate.of(year, month, day));
	}

	private SomlValue parseInstant(String text, int start) {
		var fields = text.length() > MAX_DIAGNOSED_LENGTH ? null : InstantFields.match(text);

		if (fields == null) {
			// A date, spaces or tabs, and a time, as TOML and Python write a date and time.
			var timeStart = skipSpaces(index);
			var end = timeStart > index && isDigit(code(timeStart)) ? findNumberEnd(timeStart) : index;
			var time = end > timeStart ? source.substring(timeStart, end) : "";
			// More of the instant may follow, as in `14:00:00,5Z` or `14:00:00[Europe/Oslo]`, so it may have an offset.
			var isWholeValue = isValueEndAt(end) && !(code(end) == ',' && isDigit(code(end + 1)));
			throw fail(describeBadInstant(text, time, isWholeValue, findOffsetAfterSpace()), start);
		}

		check(fields.fraction() == null || fields.fraction().length() <= 9, "a fractional second has at most nine digits", text, start);
		var calendarError = SomlDate.calendarError(fields.year(), fields.month(), fields.day());

		if (calendarError != null) {
			throw fail("Invalid instant " + abbreviate(text) + ": " + calendarError, start);
		}
		check(fields.hour() <= 23, "the hour must be 00 to 23", text, start);
		check(fields.minute() <= 59, "the minute must be 00 to 59", text, start);
		check(fields.second() <= 59, "the second must be 00 to 59, and a leap second is not representable", text, start);

		if (!fields.isUtc()) {
			check(fields.offsetHour() <= 23, "the offset hour must be 00 to 23", text, start);
			check(fields.offsetMinute() <= 59, "the offset minute must be 00 to 59", text, start);
			check(!(fields.isOffsetNegative() && fields.offsetHour() == 0 && fields.offsetMinute() == 0), "-00:00 means “offset unknown” in RFC 3339, which is not representable; use Z or +00:00", text, start);
		}

		var offsetSeconds = (fields.offsetHour() * 3600L) + (fields.offsetMinute() * 60L);
		var seconds = (daysFromCivil(fields.year(), fields.month(), fields.day()) * 86_400L) + (fields.hour() * 3600L) + (fields.minute() * 60L) + fields.second() - (fields.isOffsetNegative() ? -offsetSeconds : offsetSeconds);
		var fraction = fields.fraction() == null ? "" : fields.fraction();
		var nanoseconds = fraction.isEmpty() ? 0 : Integer.parseInt(fraction);

		nanoseconds *= (int) POWERS_OF_TEN[9 - fraction.length()];
		var instant = Instant.ofEpochSecond(seconds, nanoseconds);
		check(!instant.isBefore(SomlInstant.MIN) && !instant.isAfter(SomlInstant.MAX), "in UTC it falls outside the years 0001 to 9999", text, start);
		return new SomlInstant(instant);
	}

	private void check(boolean isValid, String reason, String text, int start) {
		if (!isValid) {
			throw fail("Invalid instant " + abbreviate(text) + ": " + reason, start);
		}
	}

	/*
	The parts of a token that matches the instant grammar lexically, before the ranges are checked.
	*/
	private record InstantFields(int year, int month, int day, int hour, int minute, int second, @Nullable String fraction, boolean isUtc, boolean isOffsetNegative, int offsetHour, int offsetMinute) {
		/*
		`dddd-dd-ddTdd:dd:dd(.d+)?(Z|[+-]dd:dd)`, or null.
		*/
		static @Nullable InstantFields match(String text) {
			if (text.length() < 20 || !hasDatePrefix(text) || text.charAt(10) != 'T' || !isDigits(text, 11, 13) || text.charAt(13) != ':' || !isDigits(text, 14, 16) || text.charAt(16) != ':' || !isDigits(text, 17, 19)) {
				return null;
			}

			var offset = 19;
			String fraction = null;

			if (text.charAt(offset) == '.') {
				var fractionEnd = offset + 1;

				while (fractionEnd < text.length() && isDigit(text.charAt(fractionEnd))) {
					fractionEnd++;
				}

				if (fractionEnd == offset + 1) {
					return null;
				}

				fraction = text.substring(offset + 1, fractionEnd);
				offset = fractionEnd;
			}

			var year = Integer.parseInt(text, 0, 4, 10);
			var month = Integer.parseInt(text, 5, 7, 10);
			var day = Integer.parseInt(text, 8, 10, 10);
			var hour = Integer.parseInt(text, 11, 13, 10);
			var minute = Integer.parseInt(text, 14, 16, 10);
			var second = Integer.parseInt(text, 17, 19, 10);

			if (offset == text.length() - 1 && text.charAt(offset) == 'Z') {
				return new InstantFields(year, month, day, hour, minute, second, fraction, true, false, 0, 0);
			}

			var sign = offset < text.length() ? text.charAt(offset) : 0;

			if (offset + 6 != text.length() || (sign != '+' && sign != '-') || !isDigits(text, offset + 1, offset + 3) || text.charAt(offset + 3) != ':' || !isDigits(text, offset + 4, offset + 6)) {
				return null;
			}

			var offsetHour = Integer.parseInt(text, offset + 1, offset + 3, 10);
			var offsetMinute = Integer.parseInt(text, offset + 4, offset + 6, 10);
			return new InstantFields(year, month, day, hour, minute, second, fraction, false, sign == '-', offsetHour, offsetMinute);
		}

		static boolean hasDatePrefix(String text) {
			return text.length() >= 10 && isDigits(text, 0, 4) && text.charAt(4) == '-' && isDigits(text, 5, 7) && text.charAt(7) == '-' && isDigits(text, 8, 10);
		}

	}

	/*
	The days from 1970-01-01 to a date in the proleptic Gregorian calendar, by Howard Hinnant's `days_from_civil`.
	*/
	private static long daysFromCivil(int year, int month, int day) {
		long shiftedYear = month <= 2 ? year - 1 : year;
		var era = Math.floorDiv(shiftedYear, 400);
		var yearOfEra = shiftedYear - (era * 400);
		var dayOfYear = (((153L * (month > 2 ? month - 3 : month + 9)) + 2) / 5) + day - 1;
		var dayOfEra = (yearOfEra * 365) + (yearOfEra / 4) - (yearOfEra / 100) + dayOfYear;
		return (era * 146_097) + dayOfEra - 719_468;
	}

	/*
	A scan of the parts in order, so that each error names the part it is about. The total is counted as an unsigned magnitude, so that the most negative duration fits.
	*/
	private SomlValue parseDuration(String text, int start) {
		var isNegative = text.charAt(0) == '-';
		var offset = isNegative ? 1 : 0;
		DurationUnit previousUnit = null;
		var total = 0L;
		var isOutOfRange = false;
		// The fraction of the last part, and that part's unit size. Only the last part may have one, so the value checks wait until every part is read.
		var fraction = "";
		var size = 0L;

		while (offset < text.length()) {
			var digitsEnd = skipIntegerPart(text, offset);
			var next = digitsEnd < text.length() ? text.charAt(digitsEnd) : -1;

			if (digitsEnd == offset) {
				var code = text.charAt(offset);

				if (code == '-' && offset + 1 < text.length() && isDigit(text.charAt(offset + 1))) {
					throw failDuration(text, start, "only the whole duration takes a sign, as in -1h30m");
				}

				throw failDuration(text, start, code == '+' ? "a “+” sign is not allowed" : "expected a number at “" + abbreviate(text.substring(offset, Math.min(text.length(), offset + 11)), 10) + "”");
			}

			if (text.charAt(offset) == '0' && (next == '_' || isDigit(next))) {
				throw failDuration(text, start, "leading zeros are not allowed");
			}

			if (next == '_') {
				throw failDuration(text, start, "an underscore must be between two digits");
			}

			fraction = "";
			var unitStart = digitsEnd;

			if (next == '.') {
				unitStart = skipDigits(text, digitsEnd + 1);

				if (unitStart == digitsEnd + 1) {
					throw failDuration(text, start, "a “.” must be followed by a digit");
				}

				if (unitStart < text.length() && text.charAt(unitStart) == '_') {
					throw failDuration(text, start, "an underscore must be between two digits");
				}

				// Trailing zeros change nothing, and leaving them out keeps the arithmetic small however many there are.
				var fractionEnd = unitStart;

				while (fractionEnd > digitsEnd + 1 && (text.charAt(fractionEnd - 1) == '0' || text.charAt(fractionEnd - 1) == '_')) {
					fractionEnd--;
				}

				fraction = text.substring(digitsEnd + 1, fractionEnd).replace("_", "");
			}

			var unitEnd = unitStart;

			while (unitEnd < text.length() && isLetter(text.charAt(unitEnd))) {
				unitEnd++;
			}

			var unit = DurationUnit.parse(text, unitStart, unitEnd);

			if (unit == null) {
				throw failDuration(text, start, describeBadDurationUnit(text.substring(unitStart, unitEnd), text));
			}

			// Note: Not using `EnumSet` because the previous-unit comparison enforces both descending order and uniqueness without another collection.
			if (previousUnit != null && unit.nanoseconds >= previousUnit.nanoseconds) {
				throw failDuration(text, start, "the units are in the order h, m, s, ms, us, ns, and each appears at most once");
			}

			if (next == '.' && unitEnd < text.length() && isDigit(text.charAt(unitEnd))) {
				throw failDuration(text, start, "only the last part may have a fraction");
			}

			size = unit.nanoseconds;
			var digits = text;
			var numberStart = offset;
			var numberEnd = digitsEnd;

			// Nineteen digits with single underscores occupy at most 37 characters. Longer parts are already out of range.
			if (digitsEnd - offset <= 37 && text.indexOf('_', offset, digitsEnd) != -1) {
				digits = text.substring(offset, digitsEnd).replace("_", "");
				numberStart = 0;
				numberEnd = digits.length();
			}

			// More than 19 digits is out of range in any unit. Up to 19 digits fit an unsigned long.
			if (numberEnd - numberStart > 19) {
				isOutOfRange = true;
			} else {
				var number = Long.parseUnsignedLong(digits, numberStart, numberEnd, 10);
				var product = number * size;

				if (Math.unsignedMultiplyHigh(number, size) != 0) {
					isOutOfRange = true;
				}

				var sum = total + product;

				if (Long.compareUnsigned(sum, total) < 0) {
					isOutOfRange = true;
				}

				total = sum;
			}

			offset = unitEnd;
			previousUnit = unit;
		}

		// More than 13 fraction digits without a trailing zero is finer than a nanosecond in any unit.
		if (fraction.length() > 13) {
			throw failDuration(text, start, "it is not a whole number of nanoseconds");
		}

		if (!fraction.isEmpty()) {
			var scale = (long) POWERS_OF_TEN[fraction.length()];
			var divisor = scale;
			var remainder = size;

			while (remainder != 0) {
				var next = divisor % remainder;
				divisor = remainder;
				remainder = next;
			}

			var number = Long.parseLong(fraction);
			var denominator = scale / divisor;

			if (number % denominator != 0) {
				throw failDuration(text, start, "it is not a whole number of nanoseconds");
			}

			// The quotient is below the unit size, so it fits. Reduce before multiplying to keep every intermediate in a long.
			var sum = total + (number / denominator) * (size / divisor);

			if (Long.compareUnsigned(sum, total) < 0) {
				isOutOfRange = true;
			}

			total = sum;
		}

		if (isNegative && total == 0 && !isOutOfRange) {
			throw failDuration(text, start, "“-” is not allowed before zero, because zero has one spelling: 0s");
		}

		// The limit is 2^63 for a negative duration and 2^63 - 1 for a positive one, as unsigned numbers.
		if (isOutOfRange || Long.compareUnsigned(total, isNegative ? Long.MIN_VALUE : Long.MAX_VALUE) > 0) {
			throw failDuration(text, start, "it is outside the 64-bit range of nanoseconds, about 292 years either way");
		}

		return SomlDuration.ofNanos(isNegative ? -total : total);
	}

	private enum DurationUnit {
		HOURS(3_600_000_000_000L),
		MINUTES(60_000_000_000L),
		SECONDS(1_000_000_000L),
		MILLISECONDS(1_000_000L),
		MICROSECONDS(1000L),
		NANOSECONDS(1L);

		final long nanoseconds;

		DurationUnit(long nanoseconds) {
			this.nanoseconds = nanoseconds;
		}

		/*
		Note: Not using `String.substring()` to match duration units because it would allocate a string for every part of a duration. Returns the unit within the token, or null.
		*/
		static @Nullable DurationUnit parse(String text, int start, int end) {
			if (end - start == 1) {
				return switch (text.charAt(start)) {
					case 'h' -> HOURS;
					case 'm' -> MINUTES;
					case 's' -> SECONDS;
					default -> null;
				};
			}

			if (end - start == 2 && text.charAt(start + 1) == 's') {
				return switch (text.charAt(start)) {
					case 'm' -> MILLISECONDS;
					case 'u' -> MICROSECONDS;
					case 'n' -> NANOSECONDS;
					default -> null;
				};
			}

			return null;
		}
	}

	private SomlException failDuration(String text, int start, String reason) {
		return fail("Invalid duration " + abbreviate(text) + ": " + reason, start);
	}

	private static boolean isYearUnit(String text, int start, int end) {
		return switch (end - start) {
			case 1 -> text.regionMatches(true, start, "y", 0, 1);
			case 2 -> text.regionMatches(true, start, "yr", 0, 2);
			case 3 -> text.regionMatches(true, start, "yrs", 0, 3);
			case 4 -> text.regionMatches(true, start, "year", 0, 4);
			case 5 -> text.regionMatches(true, start, "years", 0, 5);
			default -> false;
		};
	}

	private static String describeBadDurationUnit(String unit, String text) {
		if (unit.isEmpty()) {
			return "every number needs a unit: h, m, s, ms, us, or ns";
		}

		if (unit.equals("d") || unit.equals("day") || unit.equals("days")) {
			return "there is no day unit, because a day is not a fixed length. Write 24h for a fixed 24 hours";
		}

		if (unit.equals("w") || unit.equals("week") || unit.equals("weeks")) {
			return "there is no week unit, because a day is not a fixed length. Write 168h for a fixed 168 hours";
		}

		if (isYearUnit(unit, 0, unit.length())) {
			return "there is no year unit, because a year is not a fixed length. Write 8760h for a fixed 365 days";
		}

		if (unit.equals("M")) {
			// A number with `M` alone is more often a size, as in `memory: 512M`, and `512m` would read as minutes.
			var isSize = text.length() <= MAX_DIAGNOSED_LENGTH && MONTH_SIZE.matcher(text).matches();
			return "there is no month unit, because a month is not a fixed length" + (isSize ? ". A size, such as 512M, is a string: '" + abbreviate(text) + "'" : "");
		}

		var lowercase = unit.length() <= 2 ? unit.toLowerCase(Locale.ROOT) : "";
		return DurationUnit.parse(lowercase, 0, lowercase.length()) != null ? "the units are lowercase: " + lowercase : "“" + abbreviate(unit, 10) + "” is not a unit. The units are h, m, s, ms, us, and ns, and a string must be quoted";
	}

	/*
	Whether `text` is one valid value, so that an error message only suggests a fix that works.
	*/
	private static boolean isValidValue(String text) {
		// Note: Not using `Soml.parse()` on a bracketed candidate because direct parsing with the discard sink avoids allocating the bracketed document and its array tree.
		var parser = new Parser(text, null);
		parser.sink = DISCARD;

		try {
			parser.skipTrivia();
			parser.parseValue(2);
			parser.skipTrivia();
			return parser.isAtEnd();
		} catch (SomlException _) {
			parser.hasFailed = true;
			return false;
		} finally {
			parser.release();
		}
	}

	private enum NumberKind {
		INT,
		FLOAT,
		NONE
	}

	/*
	Whether `text` is an int, in any radix, or a float, following the grammar. A run of digits may have single underscores between digits.
	*/
	private static NumberKind classifyNumber(String text) {
		if (text.length() > 1 && text.charAt(0) == '0' && (text.charAt(1) == 'x' || text.charAt(1) == 'o' || text.charAt(1) == 'b')) {
			var radix = text.charAt(1);
			var end = skipRadixDigits(text, 2, radix);
			return end > 2 && end == text.length() ? NumberKind.INT : NumberKind.NONE;
		}

		var offset = text.charAt(0) == '-' ? 1 : 0;
		// The integer part is `0` or has no leading zero.
		var integerEnd = skipIntegerPart(text, offset);

		if (integerEnd == offset) {
			return NumberKind.NONE;
		}

		offset = integerEnd;
		var kind = NumberKind.INT;

		if (offset < text.length() && text.charAt(offset) == '.') {
			var end = skipDigits(text, offset + 1);

			if (end == offset + 1) {
				return NumberKind.NONE;
			}

			offset = end;
			kind = NumberKind.FLOAT;
		}

		if (offset < text.length() && text.charAt(offset) == 'e') {
			var isNegative = offset + 1 < text.length() && text.charAt(offset + 1) == '-';
			var exponentStart = offset + (isNegative ? 2 : 1);
			// The exponent follows the same leading-zero rule as the integer part, and its zero has one spelling, `e0`, so `e-0` is not an exponent.
			var end = isNegative && exponentStart < text.length() && text.charAt(exponentStart) == '0' ? exponentStart : skipIntegerPart(text, exponentStart);

			if (end == exponentStart) {
				return NumberKind.NONE;
			}

			offset = end;
			kind = NumberKind.FLOAT;
		}

		return offset == text.length() ? kind : NumberKind.NONE;
	}

	private static boolean isRadixDigit(int code, char radix) {
		return switch (radix) {
			case 'x' -> isDigit(code) || (code >= 'A' && code <= 'F');
			case 'o' -> code >= '0' && code <= '7';
			case 'b' -> code == '0' || code == '1';
			default -> isDigit(code);
		};
	}

	/*
	Returns the offset after a run of digits of `radix`, which is `x`, `o`, `b`, or `d` for decimal, that may have single underscores between them, or `offset` when there is no digit there.
	*/
	private static int skipRadixDigits(String text, int offset, char radix) {
		if (offset >= text.length() || !isRadixDigit(text.charAt(offset), radix)) {
			return offset;
		}

		offset++;

		for (;;) {
			var code = offset < text.length() ? text.charAt(offset) : -1;

			if (isRadixDigit(code, radix)) {
				offset++;
			} else if (code == '_' && offset + 1 < text.length() && isRadixDigit(text.charAt(offset + 1), radix)) {
				offset += 2;
			} else {
				return offset;
			}
		}
	}

	private static int skipDigits(String text, int offset) {
		return skipRadixDigits(text, offset, 'd');
	}

	/*
	Returns the offset after an integer part, which is `0` or digits without a leading zero, or `offset` when there is none. Anything after a leading `0`, such as the `5` in `05`, is left for the caller to reject.
	*/
	private static int skipIntegerPart(String text, int offset) {
		return offset < text.length() && text.charAt(offset) == '0' ? offset + 1 : skipDigits(text, offset);
	}

	/*
	Whether a token that is not a number or an instant was meant as a duration: its first letter could begin a unit, or a day or a week, or it is a year unit. A radix prefix, an exponent, and other letters, such as the `T` in `20260919T140000Z` or the `x` in `1.5x`, are left to the number errors.
	*/
	private static boolean isDurationLike(String text) {
		var start = text.charAt(0) == '-' ? 1 : 0;

		if (start >= text.length() || !isDigit(text.charAt(start))) {
			return false;
		}

		var offset = start;

		while (offset < text.length() && (isDigit(text.charAt(offset)) || text.charAt(offset) == '_')) {
			offset++;
		}

		if (offset < text.length() && text.charAt(offset) == '.') {
			offset++;

			while (offset < text.length() && (isDigit(text.charAt(offset)) || text.charAt(offset) == '_')) {
				offset++;
			}
		}

		// A year unit, as in `1y` or `2years`, is only one when the token or another part follows it, so that a word such as `100yen` stays a string to quote. The longest unit and the character after it fit in 6 characters.
		if (offset >= text.length()) {
			return false;
		}

		if ("dhmnsuwDHMNSUW".indexOf(text.charAt(offset)) != -1) {
			return true;
		}

		var unitEnd = offset;

		while (unitEnd < text.length() && unitEnd < offset + 6 && isLetter(text.charAt(unitEnd))) {
			unitEnd++;
		}

		return isYearUnit(text, offset, unitEnd) && (unitEnd == text.length() || isDigit(text.charAt(unitEnd)));
	}

	private static boolean isHexDigits(String text, int start, int end) {
		if (end > text.length()) {
			return false;
		}

		for (var offset = start; offset < end; offset++) {
			if (!HexFormat.isHexDigit(text.charAt(offset))) {
				return false;
			}
		}

		return true;
	}

	private String describeBadUnicodeEscape(int offset) {
		var rest = source.substring(offset, Math.min(source.length(), offset + 12));

		if (isHexDigits(rest, 1, 5)) {
			return describeFourDigitEscape(rest);
		}

		if (rest.startsWith("u{")) {
			for (var position = 2; position < rest.length() && HexFormat.isHexDigit(rest.charAt(position)); position++) {
				if (rest.charAt(position) >= 'A' && rest.charAt(position) <= 'F') {
					return "A Unicode escape uses lowercase hexadecimal digits";
				}
			}
		}

		if (rest.startsWith("u{}")) {
			return "A Unicode escape needs one to six hexadecimal digits";
		}

		return rest.startsWith("u{") && isHexDigits(rest, 2, 9) ? "A Unicode escape has at most six hexadecimal digits" : "A Unicode escape is written \\u{…} with one to six lowercase hexadecimal digits";
	}

	/*
	The four-digit form of JSON, a backslash, a `u`, and four hexadecimal digits, from its `u`, with the escape to write instead. JSON writes a character above U+FFFF as two of them, a surrogate pair, which is one braced escape here. Here, a lone surrogate and a carriage return have no escape.
	*/
	private static String describeFourDigitEscape(String rest) {
		var code = HexFormat.fromHexDigits(rest, 1, 5);
		var lowCode = rest.startsWith("\\u", 5) && isHexDigits(rest, 7, 11) ? HexFormat.fromHexDigits(rest, 7, 11) : -1;

		if (Character.isSurrogatePair((char) code, (char) lowCode)) {
			var codePoint = Character.toCodePoint((char) code, (char) lowCode);
			return "The four-digit \\" + rest.substring(0, 5) + "\\u" + rest.substring(7, 11) + " form is not an escape. Write \\u{" + Integer.toHexString(codePoint) + "}";
		}

		var form = "The four-digit \\" + rest.substring(0, 5) + " form is not an escape";

		if (Character.isSurrogate((char) code)) {
			return form + ", and a lone surrogate is not a Unicode scalar value. Write the character it is half of as one \\u{…} escape";
		}

		return code == 0x0D ? form + ", and a carriage return (U+000D) cannot be represented" : form + ". Write \\u{" + Integer.toHexString(code) + "}";
	}

	/*
	`unquotedText` is the unquoted string that `fullText` begins, for the suggestion to quote it.
	*/
	private static String describeBadNumber(String fullText, String unquotedText) {
		var text = fullText.length() > MAX_DIAGNOSED_LENGTH ? fullText.substring(0, MAX_DIAGNOSED_LENGTH) : fullText;

		if (text.contains("+")) {
			return "A “+” sign is not allowed in a number, including in an exponent";
		}

		var signLength = fullText.startsWith("-") ? 1 : 0;

		if (text.length() >= signLength + 2 && text.charAt(signLength) == '0' && "BOX".indexOf(text.charAt(signLength + 1)) != -1) {
			return "A number prefix is lowercase: 0x, 0o, or 0b";
		}

		// The whole number, not the part cut at the diagnosed length, because a cut can end before the digit or the underscore that the message is about. Each check below takes linear time.
		if (fullText.length() >= signLength + 2 && fullText.charAt(signLength) == '0' && "box".indexOf(fullText.charAt(signLength + 1)) != -1) {
			var radix = fullText.charAt(signLength + 1);
			var digits = fullText.substring(signLength + 2);
			var name = switch (radix) {
				case 'x' -> "hexadecimal";
				case 'o' -> "octal";
				default -> "binary";
			};

			if (signLength == 1) {
				return (radix == 'o' ? "An " : "A ") + name + " integer cannot have a sign, because it states a bit pattern rather than a quantity";
			}

			if (digits.isEmpty()) {
				return "Expected " + name + " digits after “0" + radix + "”";
			}

			if (radix == 'x' && isLowercaseHexDigits(digits)) {
				// The uppercase spelling is only suggested when it is valid, so a misplaced underscore is reported first, and a value outside the 64-bit range gets no example.
				var uppercaseDigits = digits.toUpperCase(Locale.ROOT);

				if (skipRadixDigits(uppercaseDigits, 0, 'x') != digits.length()) {
					return "An underscore in a number must be between two digits";
				}

				var uppercase = "0x" + uppercaseDigits;
				return isValidValue(uppercase) ? "Hexadecimal digits are uppercase: " + abbreviate(uppercase) : "Hexadecimal digits are uppercase";
			}

			// A lowercase hexadecimal digit is a digit in the wrong case, so the one named is a character that is no digit in either case.
			for (var offset = 0; offset < digits.length(); ) {
				var codePoint = digits.codePointAt(offset);
				var isValid = codePoint == '_' || switch (radix) {
					case 'x' -> HexFormat.isHexDigit(codePoint);
					case 'o' -> codePoint >= '0' && codePoint <= '7';
					default -> codePoint == '0' || codePoint == '1';
				};

				if (!isValid) {
					return "Invalid " + name + " digit “" + Character.toString(codePoint) + "”";
				}

				offset += Character.charCount(codePoint);
			}

			return "An underscore in a number must be between two digits";
		}

		// Only when the “E” is where an exponent marker goes, with digits, a `-`, or nothing after it. In `5EUR` or `10EB`, it is part of a word, so lowercasing it would not help.
		if (UPPERCASE_EXPONENT.matcher(text).find()) {
			return "An exponent marker is a lowercase “e”";
		}

		if (MISSING_FRACTION.matcher(text).find()) {
			return "A decimal point must be followed by a digit";
		}

		if (text.startsWith("-.")) {
			return "A number cannot begin with “.”; write a digit before it, as in -0.5";
		}

		if (COLON_NUMBER.matcher(text).find()) {
			return TIME_OF_DAY.matcher(text).find() ? "A time of day is a string, so it must be quoted" : "Invalid number “" + abbreviate(text) + "”. A value that contains “:” must be quoted, as in '" + abbreviate(text) + "'";
		}

		// A value with a `:` that starts with a zero, such as the MAC address `00:1A:2B`, is not a number with a leading zero, because removing the zero would not make it valid.
		if ((text.length() >= signLength + 2 && text.charAt(signLength) == '0' && (isDigit(text.charAt(signLength + 1)) || text.charAt(signLength + 1) == '_')) && !text.contains(":")) {
			// Removing the zero gives a valid number with a different meaning, so the message says what the zero usually meant. A '...' string cannot hold a ', so then there is no example.
			var identifier = "an identifier, such as a ZIP code, as a string" + (unquotedText.contains("'") ? "" : ": '" + abbreviate(unquotedText) + "'");

			// The octal suggestion is only for the number on its own, not for one that more text follows, as in `0412 345 678`, and only when it is in range. It is decided on the whole number, because a cut can end before a digit that is not octal or that changes the value.
			if (unquotedText.equals(fullText) && isOctalWithLeadingZero(fullText)) {
				var digits = stripLeadingZeros(fullText);
				var octal = "0o" + (digits.isEmpty() ? "0" : digits);

				if (isValidValue(octal)) {
					return "Leading zeros are not allowed in a decimal number. Write an octal number, such as a file mode, as " + abbreviate(octal) + ", and " + identifier;
				}
			}

			return signLength == 0 && isDigits(text, 1, text.length()) ? "Leading zeros are not allowed in a decimal number. Write " + identifier : "Leading zeros are not allowed in a decimal number";
		}

		// Only in a number: an underscore next to a letter other than the exponent marker is part of a word, such as `4k_video`.
		if (MISPLACED_UNDERSCORE.matcher(text).find() && !NON_NUMBER_CHARACTER.matcher(text).find()) {
			return "An underscore in a number must be between two digits";
		}

		if (ZERO_PREFIXED_EXPONENT.matcher(text).find()) {
			return "Leading zeros are not allowed in an exponent";
		}

		if (NEGATIVE_ZERO_EXPONENT.matcher(text).find()) {
			return "“e-0” is not allowed, because an exponent of zero has one spelling: e0";
		}

		// Only a number ends with its exponent marker. A word that ends with an “e”, such as `1byte` or `-verbose`, has no exponent.
		if (MISSING_EXPONENT.matcher(text).find()) {
			return "Expected digits after the exponent marker “e”";
		}

		if (text.indexOf('.') != text.lastIndexOf('.')) {
			return "Invalid number “" + abbreviate(text) + "”. A value with several dots, such as a version number, must be quoted";
		}

		if (signLength == 1 && (text.length() == 1 || !isDigit(text.charAt(1)))) {
			// The whole word, as for one without the “-”, so that a word such as `-nano` or `-info` is not taken for NaN or infinity.
			if (text.equalsIgnoreCase("-nan")) {
				return "NaN is not representable. Use null for a missing value";
			}

			return (text.equalsIgnoreCase("-inf") || text.equalsIgnoreCase("-infinity")) ? "“" + abbreviate(text) + "” is not a value. Negative infinity is written -infinity" : "Expected a digit or “infinity” after “-”";
		}

		return containsLetter(text) ? "Invalid number “" + abbreviate(text) + "”. A string value must be quoted" + quotingExample(unquotedText) : "Invalid number “" + abbreviate(text) + "”";
	}

	private static boolean containsLetter(String text) {
		for (var offset = 0; offset < text.length(); offset++) {
			if (isLetter(text.charAt(offset))) {
				return true;
			}
		}

		return false;
	}

	private static boolean isLowercaseHexDigits(String digits) {
		var hasLowercase = false;

		for (var offset = 0; offset < digits.length(); offset++) {
			var character = digits.charAt(offset);

			if (character >= 'a' && character <= 'f') {
				hasLowercase = true;
			} else if (!HexFormat.isHexDigit(character) && character != '_') {
				return false;
			}
		}

		return hasLowercase;
	}

	private static boolean isOctalWithLeadingZero(String text) {
		if (text.length() < 2 || text.charAt(0) != '0') {
			return false;
		}

		for (var offset = 1; offset < text.length(); offset++) {
			var character = text.charAt(offset);

			if (character < '0' || character > '7') {
				return false;
			}
		}

		return true;
	}

	/*
	`time` is the token after the spaces or tabs that follow `text`, or an empty string. `isWholeValue` is whether nothing that may be part of the instant follows `text`. `offset` is an offset after spaces that follow `text`, or null.
	*/
	private static String describeBadInstant(String text, String time, boolean isWholeValue, @Nullable String offset) {
		if (text.length() > MAX_DIAGNOSED_LENGTH) {
			return "Invalid instant “" + abbreviate(text) + "”. " + INSTANT_FORMAT;
		}

		if (text.length() == 10 && InstantFields.hasDatePrefix(text)) {
			return describeSpaceSeparatedInstant(text, time, isWholeValue);
		}

		if (text.length() > 10 && text.charAt(10) == 't') {
			return "The date and time separator in an instant is an uppercase “T”";
		}

		if (text.endsWith("z")) {
			return "The UTC offset in an instant is an uppercase “Z”";
		}

		if (text.length() >= 5 && (text.charAt(text.length() - 5) == '+' || text.charAt(text.length() - 5) == '-') && isDigits(text, text.length() - 4, text.length())) {
			return "An instant's offset is written with a colon, as in +07:00";
		}

		if (!LOCAL_DATE_TIME.matcher(text).matches()) {
			return "Invalid instant “" + abbreviate(text) + "”. " + INSTANT_FORMAT;
		}

		if (offset != null) {
			// The instant has the offset it was meant in, so it is not a local time to write as a string.
			var reason = "An instant's offset follows its time directly, without a space";
			return isValidValue(text + offset) ? reason + ", as in " + abbreviate(text + offset) : reason;
		}

		return isWholeValue ? "An instant needs an offset: Z or ±HH:MM. A date and time without an offset is a local time, which is not an instant, so write it as a string, as in '" + abbreviate(text) + "', or add the offset it was meant in" : "An instant needs an offset: Z or ±HH:MM";
	}

	/*
	A date and a time with spaces or tabs between them, where an instant has a "T". Following the example must not turn a local time into UTC silently, so a time without an offset is described as what it is. An instant is only shown when it is valid, so a date that does not exist, a time out of range, or an instant outside the years 0001 to 9999 in UTC gets the general format instead.
	*/
	private static String describeSpaceSeparatedInstant(String date, String time, boolean isWholeValue) {
		var separator = "The date and time separator in an instant is an uppercase “T”, not a space";
		var instant = date + "T" + time;

		if (instant.length() > MAX_DIAGNOSED_LENGTH) {
			return separator + ". " + INSTANT_FORMAT;
		}

		if (isValidValue(instant)) {
			return separator + ", as in " + abbreviate(instant);
		}

		return isWholeValue && LOCAL_DATE_TIME.matcher(instant).matches() && isValidValue(instant + "Z") ? separator + ", and an instant needs the offset it was meant in, as in " + abbreviate(instant) + "Z for UTC. A date and time without an offset is a local time, which is not an instant, so write it as a string, as in '" + abbreviate(date + " " + time) + "'" : separator + ". " + INSTANT_FORMAT;
	}

	/*
	`text` is the unquoted string that `word` begins, which the suggestion quotes.
	*/
	private static String describeUnknownWord(String word, String text) {
		// A keyword hint is only for the word on its own. In `Yes please` or `Nan Goldin`, following it would leave text behind or change the value.
		var lowercase = text.equals(word) ? word.toLowerCase(Locale.ROOT) : "";

		if (switch (lowercase) {
			case "true", "false", "yes", "no", "on", "off" -> true;
			default -> false;
		}) {
			return "“" + word + "” is not a value. Booleans are written true and false, in lowercase";
		}

		if (switch (lowercase) {
			case "null", "nil", "none", "undefined" -> true;
			default -> false;
		}) {
			return "“" + word + "” is not a value. Null is written null, in lowercase";
		}

		if (lowercase.equals("nan")) {
			return "NaN is not representable. Use null for a missing value";
		}

		return (lowercase.equals("inf") || lowercase.equals("infinity")) ? "“" + word + "” is not a value. Infinity is written infinity, in lowercase" : "Unexpected “" + abbreviate(word) + "”. A string value must be quoted" + quotingExample(text);
	}

	/*
	The example in a message that a string must be quoted, which quotes `text` as a `'...'` string. That cannot hold a `'`, so then there is no simple example.
	*/
	private static String quotingExample(String text) {
		return text.contains("'") ? "" : ", as in '" + abbreviate(text) + "'";
	}

	private boolean isWordsWithSpaces(int start, int end) {
		for (var offset = start; offset < end; offset++) {
			var code = characters[offset];

			if (!isSpace(code) && !Writer.isBareKeyCharacter(code)) {
				return false;
			}
		}

		return true;
	}

	/*
	The end of the number, instant, or duration token that starts at `start`: a maximal run of letters, digits, and `_.:+-`.
	*/
	private int findNumberEnd(int start) {
		var end = start + 1;

		while (isNumberCharacter(code(end))) {
			end++;
		}

		return end;
	}

	static boolean isNumberCharacter(int code) {
		return isDigit(code) || isLetter(code) || code == '_' || code == '.' || code == ':' || code == '+' || code == '-';
	}

	private int findLineEnd(int offset) {
		var end = source.indexOf('\n', offset);
		return end == -1 ? source.length() : end;
	}

	private int skipSpaces(int offset) {
		while (isSpace(code(offset))) {
			offset++;
		}

		return offset;
	}

	private int skipSpacesBack(int offset) {
		while (offset > 0 && isSpace(code(offset - 1))) {
			offset--;
		}

		return offset;
	}

	/*
	Whether a code unit may directly follow a scalar value: whitespace, a separator, a closing bracket, a comment, or the end.
	*/
	private static boolean isValueEnd(int code) {
		return switch (code) {
			case -1, ' ', '\t', '\n', ',', ']', '}', '#', '/' -> true;
			default -> false;
		};
	}

	/*
	Whether a value ends at `offset`, so that a suggestion that ends there leaves nothing out. A `/` only starts a comment with a `*` after it, or a `//` comment of another language, which the error for what follows names, so in `km/h` the text goes on.
	*/
	private boolean isValueEndAt(int offset) {
		var code = code(offset);
		var next = code(offset + 1);
		return isValueEnd(code) && (code != '/' || next == '*' || next == '/');
	}

	private static boolean isSpaceOrLineEnd(int code) {
		return isSpace(code) || code == '\n' || code == -1;
	}

	static boolean isSpace(int code) {
		return code == ' ' || code == '\t';
	}

	private static boolean isDigits(String text, int start, int end) {
		if (end > text.length()) {
			return false;
		}

		for (var offset = start; offset < end; offset++) {
			if (!isDigit(text.charAt(offset))) {
				return false;
			}
		}

		return true;
	}

	private static boolean isDigit(int code) {
		return code >= '0' && code <= '9';
	}

	private static boolean isLetter(int code) {
		return (code >= 'A' && code <= 'Z') || (code >= 'a' && code <= 'z');
	}

	/*
	A key as an error message shows it: bare when it can be, and otherwise quoted, and cut short when it is long.
	*/
	static String describeKey(String key) {
		return Writer.isBareKey(key) ? abbreviate(key, 40) : quoteJson(abbreviate(key, 40));
	}

	private static String abbreviate(String text) {
		return abbreviate(text, 40);
	}

	/*
	Shortens text quoted in an error message, so a huge token does not make a huge message. The cut never splits a surrogate pair.
	*/
	static String abbreviate(String text, int maximumLength) {
		if (text.length() <= maximumLength) {
			return text;
		}

		var end = Character.isHighSurrogate(text.charAt(maximumLength - 1)) ? maximumLength - 1 : maximumLength;
		return text.substring(0, end) + "…";
	}

	/*
	A string as JSON writes it, as JavaScript's `JSON.stringify` does, for a key in a message.
	*/
	static String quoteJson(String text) {
		var output = new StringBuilder(text.length() + 2).append('"');
		var chunkStart = 0;

		for (var offset = 0; offset < text.length(); offset++) {
			var character = text.charAt(offset);

			// A lone surrogate is escaped and a surrogate pair is kept, as the well-formed `JSON.stringify` of ES2019 does.
			if (Character.isHighSurrogate(character) && offset + 1 < text.length() && Character.isLowSurrogate(text.charAt(offset + 1))) {
				offset++;
				continue;
			}

			if (character >= 0x20 && character != '"' && character != '\\' && !Character.isSurrogate(character)) {
				continue;
			}

			output.append(text, chunkStart, offset);

			switch (character) {
				case '"' -> output.append("\\\"");
				case '\\' -> output.append("\\\\");
				case '\b' -> output.append("\\b");
				case '\f' -> output.append("\\f");
				case '\n' -> output.append("\\n");
				case '\r' -> output.append("\\r");
				case '\t' -> output.append("\\t");
				// Note: Not using `HexFormat.toHexDigits(Appendable, byte)` because two calls benchmark slower here without reducing allocations.
				default -> output.append("\\u").append(HexFormat.of().toHexDigits(character));
			}

			chunkStart = offset + 1;
		}

		return output.append(text, chunkStart, text.length()).append('"').toString();
	}

	static String formatCodePoint(int codePoint) {
		// Four digits for the BMP, with further digits only when the value needs them.
		var digits = Math.max(4, (Integer.SIZE - Integer.numberOfLeadingZeros(codePoint) + 3) / 4);
		return "U+" + UPPERCASE_HEX.toHexDigits(codePoint, digits);
	}

	/*
	Describes a character for an error message, such as `“$”` or `U+00A0 (NO-BREAK SPACE)`.
	*/
	static String describeCharacter(int codePoint) {
		var hasName = (codePoint > ' ' && isWhiteSpace(codePoint))
			|| codePoint == 0x061C
			|| codePoint == 0xAD
			|| (codePoint >= 0x200B && codePoint <= 0x200F)
			|| (codePoint >= 0x202A && codePoint <= 0x202E)
			|| codePoint == 0x2060
			|| (codePoint >= 0x2066 && codePoint <= 0x2069)
			|| codePoint == 0xFEFF;

		if (hasName) {
			var name = codePoint == 0x85 ? "NEXT LINE" : Character.getName(codePoint);
			var isWhitespace = isWhiteSpace(codePoint) || codePoint == 0x200B || codePoint == 0xFEFF;
			return formatCodePoint(codePoint) + " (" + name + (isWhitespace ? "; only space, tab, and line feed are whitespace" : "") + ")";
		}

		// Control, format, private-use, unassigned, surrogate, separator, and default-ignorable characters are invisible or ambiguous, so they are shown by code point.
		if (codePoint != ' ' && (isDefaultIgnorable(codePoint) || isOther(codePoint) || isSeparator(codePoint))) {
			return formatCodePoint(codePoint);
		}

		return "“" + Character.toString(codePoint) + "”";
	}

	/*
	The Unicode property White_Space.
	*/
	private static boolean isWhiteSpace(int codePoint) {
		return (codePoint >= 0x09 && codePoint <= 0x0D)
			|| codePoint == 0x20
			|| codePoint == 0x85
			|| codePoint == 0xA0
			|| codePoint == 0x1680
			|| (codePoint >= 0x2000 && codePoint <= 0x200A)
			|| codePoint == 0x2028
			|| codePoint == 0x2029
			|| codePoint == 0x202F
			|| codePoint == 0x205F
			|| codePoint == 0x3000;
	}

	/*
	The Unicode general category Other: control, format, surrogate, private use, and unassigned.
	*/
	private static boolean isOther(int codePoint) {
		// Note: Not using `Character.isDefined()` because defined controls, format characters, surrogates, and private-use characters also belong to Other.
		return switch (Character.getType(codePoint)) {
			case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true;
			default -> false;
		};
	}

	/*
	The Unicode general category Separator: space, line, and paragraph separators.
	*/
	private static boolean isSeparator(int codePoint) {
		return Character.isSpaceChar(codePoint);
	}

	/*
	The Unicode property Default_Ignorable_Code_Point, which Java's regular expressions do not support.
	*/
	private static boolean isDefaultIgnorable(int codePoint) {
		return codePoint == 0x00AD
			|| codePoint == 0x034F
			|| codePoint == 0x061C
			|| (codePoint >= 0x115F && codePoint <= 0x1160)
			|| (codePoint >= 0x17B4 && codePoint <= 0x17B5)
			|| (codePoint >= 0x180B && codePoint <= 0x180F)
			|| (codePoint >= 0x200B && codePoint <= 0x200F)
			|| (codePoint >= 0x202A && codePoint <= 0x202E)
			|| (codePoint >= 0x2060 && codePoint <= 0x206F)
			|| codePoint == 0x3164
			|| (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
			|| codePoint == 0xFEFF
			|| codePoint == 0xFFA0
			|| (codePoint >= 0xFFF0 && codePoint <= 0xFFF8)
			|| (codePoint >= 0x1BCA0 && codePoint <= 0x1BCA3)
			|| (codePoint >= 0x1D173 && codePoint <= 0x1D17A)
			|| (codePoint >= 0xE0000 && codePoint <= 0xE0FFF);
	}

	/*
	The members of the object being parsed, in order, with a check for a duplicate key: a scan of the keys while there are few, and a hash table of their indexes after that.
	*/
	private static final class Members {
		private static final int MAX_SCANNED = 8;
		private static final String[] NO_KEYS = {};
		private static final SomlValue[] NO_VALUES = {};

		private String[] keys = new String[16];
		private SomlValue[] values = new SomlValue[16];
		private int count;
		private SomlObject.@Nullable KeyTable table;

		void reset() {
			// Keys and their lookup table can hold long strings from an earlier document, outside the bounded short-key cache.
			Arrays.fill(keys, 0, count, null);
			Arrays.fill(values, 0, count, null);
			count = 0;
			table = null;
		}

		/*
		Adds a member, or returns false when the key is already there.
		*/
		boolean add(String key, SomlValue value) {
			if (table == null) {
				for (var index = 0; index < count; index++) {
					if (keys[index].equals(key)) {
						return false;
					}
				}
			} else if (table.find(keys, key) != -1) {
				return false;
			}

			if (count == keys.length) {
				keys = Arrays.copyOf(keys, count * 2);
				values = Arrays.copyOf(values, count * 2);
			}

			keys[count] = key;
			values[count] = value;
			count++;

			// A full table is made again with room for twice the keys, so the rebuilds add up to linear time, as the growth of an array does.
			if (table != null && table.hasRoom()) {
				table.add(keys);
			} else if (table != null || count > MAX_SCANNED) {
				table = new SomlObject.KeyTable(keys, count, count * 2);
			}

			return true;
		}

		SomlObject toObject() {
			if (count == 0) {
				return SomlObject.ofTrusted(NO_KEYS, NO_VALUES);
			}

			// The scratch is kept between parses, so reset() lets go of the values before reuse and when the parse finishes.
			return SomlObject.ofTrusted(Arrays.copyOf(keys, count), Arrays.copyOf(values, count));
		}
	}

	/*
	The items of the array being parsed, in order.
	*/
	private static final class Items {
		private static final SomlValue[] NO_ITEMS = {};

		private SomlValue[] items = new SomlValue[16];
		private int count;

		void reset() {
			count = 0;
		}

		void add(SomlValue item) {
			if (count == items.length) {
				items = Arrays.copyOf(items, count * 2);
			}

			items[count++] = item;
		}

		SomlArray toArray() {
			if (count == 0) {
				return SomlArray.ofTrusted(NO_ITEMS);
			}

			var array = SomlArray.ofTrusted(Arrays.copyOf(items, count));
			Arrays.fill(items, 0, count, null);
			return array;
		}
	}
}
