package sh.soml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ValueTest {
	@Test
	void keepsTheMapEqualityContractAcrossImplementations() {
		for (var size : new int[] {0, 1, 8, 9, 128}) {
			var expected = new LinkedHashMap<String, SomlValue>();

			for (var index = 0; index < size; index++) {
				expected.put("key" + index, new SomlInt(index));
			}

			var members = new SomlObject(expected).members();

			for (var other : List.of(expected, new HashMap<>(expected), new TreeMap<>(expected), new SomlObject(expected).members())) {
				assertEquals(other, members);
				assertEquals(members, other);
				assertEquals(other.hashCode(), members.hashCode());
				assertEquals(other.entrySet(), members.entrySet());
			}

			assertFalse(members.equals(null));
			assertFalse(members.equals(List.of()));

			if (size > 0) {
				var changed = new HashMap<>(expected);
				changed.put("key0", SomlNull.INSTANCE);
				assertNotEquals(members, changed);
				changed.put("key0", null);
				assertNotEquals(members, changed);
				changed.remove("key0");
				changed.put("missing", expected.get("key0"));
				assertNotEquals(members, changed);

				var integerKeys = new TreeMap<Integer, SomlValue>();

				for (var index = 0; index < size; index++) {
					integerKeys.put(index, new SomlInt(index));
				}

				assertFalse(members.equals(integerKeys));
			}
		}
	}

	@Test
	void findsValuesByEqualityIncludingTheSomlNullValue() {
		var members = ((SomlObject) Soml.parse("a: null\nb: [1]\nc: {x: true}\n")).members();
		assertTrue(members.containsValue(SomlNull.INSTANCE));
		assertTrue(members.containsValue(SomlArray.of(new SomlInt(1))));
		assertTrue(members.containsValue(new SomlObject(Map.of("x", new SomlBool(true)))));
		assertFalse(members.containsValue(null));
		assertFalse(members.containsValue(1));
		assertFalse(members.containsValue(new SomlInt(1)));
		assertFalse(new SomlObject(Map.of()).members().containsValue(SomlNull.INSTANCE));
	}

	@Test
	void returnsIndependentArraysFromImmutableLists() {
		for (var items : List.of(((SomlArray) Soml.parse("[1, null, 'x']")).items(), ((SomlObject) Soml.parse("a: 1\nb: null\nc: 'x'")).members().values())) {
			var expected = new SomlValue[] {new SomlInt(1), SomlNull.INSTANCE, new SomlString("x")};
			var copy = items.toArray();
			assertEquals(Object[].class, copy.getClass());
			assertArrayEquals(expected, copy);
			copy[0] = "changed";
			assertArrayEquals(expected, items.toArray());
			assertArrayEquals(expected, items.toArray(new SomlValue[0]));

			var destination = new SomlValue[] {SomlNull.INSTANCE, SomlNull.INSTANCE, SomlNull.INSTANCE, new SomlInt(4), new SomlInt(5)};
			assertSame(destination, items.toArray(destination));
			assertArrayEquals(new SomlValue[] {expected[0], expected[1], expected[2], null, new SomlInt(5)}, destination);
			assertThrows(ArrayStoreException.class, () -> items.toArray(new String[0]));
		}

		assertArrayEquals(new Object[0], ((SomlArray) Soml.parse("[]")).items().toArray());
	}

	@Test
	void canonicalOrderingMatchesUnicodeCodePointOrder() {
		var keys = new ArrayList<>(List.of("", "a", "aa", "a😀", "a\uE000", "z", "\uD7FF", "\uE000", "\uFFFF", "𐀀", "😀", "\uDBFF\uDFFF"));

		for (var index = 0; index < 128; index++) {
			keys.add("key" + index);
		}

		var sorted = new ArrayList<>(keys);
		// Compare scalar values independently of the writer's UTF-16 comparator.
		sorted.sort((left, right) -> Arrays.compare(left.codePoints().toArray(), right.codePoints().toArray()));
		var random = new Random(43);

		for (var attempt = 0; attempt < 20; attempt++) {
			var members = new LinkedHashMap<String, SomlValue>();

			for (var key : keys) {
				members.put(key, new SomlString(key));
			}

			var object = new SomlObject(members);
			var before = new ArrayList<>(object.members().keySet());
			var canonical = Soml.encodeCanonical(object);
			assertEquals(sorted, new ArrayList<>(((SomlObject) Soml.parse(canonical)).members().keySet()));
			assertEquals(object, Soml.parse(canonical));
			assertEquals(canonical, Soml.encodeCanonical(Soml.parse(canonical)));
			assertEquals(before, new ArrayList<>(object.members().keySet()));
			Collections.shuffle(keys, random);
		}
	}

	@Test
	void doesNotRetainDocumentKeysInTheParserPool() throws ReflectiveOperationException {
		var text = new StringBuilder("nested: {\n");

		for (var index = 0; index < 128; index++) {
			// Longer than the bounded short-key cache, and colliding so the duplicate-key table also uses its HashMap fallback.
			text.append("Aa".repeat(20));

			for (var bit = 0; bit < 8; bit++) {
				text.append((index >> bit & 1) == 0 ? "Aa" : "BB");
			}

			text.append(": ").append(index).append('\n');
		}

		text.append("}\n");

		for (var streams : List.of(false, true)) {
			if (streams) {
				Soml.decode(text.toString(), Object.class);
			} else {
				Soml.parse(text.toString());
			}

			// Inspect references rather than depending on whether the garbage collector happens to run.
			var spareField = Parser.class.getDeclaredField("SPARE_SCRATCH");
			spareField.setAccessible(true);
			var scratch = ((AtomicReference<?>) spareField.get(null)).get();

			for (var members : (Object[]) readField(scratch, "membersByDepth")) {
				if (members == null) {
					continue;
				}

				for (var key : (Object[]) readField(members, "keys")) {
					assertNull(key, "A pooled parser must not retain document keys");
				}

				assertNull(readField(members, "table"), "A pooled parser must not retain duplicate-key lookups");
			}
		}
	}

	private static Object readField(Object object, String name) throws ReflectiveOperationException {
		var field = object.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(object);
	}

	@Test
	void keepsIntAndFloatApart() {
		assertNotEquals(new SomlInt(3), new SomlFloat(3.0));
		assertEquals("3", new SomlInt(3).toString());
		assertEquals("3.0", new SomlFloat(3.0).toString());
	}

	@Test
	void normalizesNegativeZero() {
		assertEquals(new SomlFloat(0.0), new SomlFloat(-0.0));
		assertEquals("0.0", new SomlFloat(-0.0).toString());
		assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(new SomlFloat(-0.0).value()));
	}

	@Test
	void refusesNaN() {
		assertThrows(IllegalArgumentException.class, () -> new SomlFloat(Double.NaN));
	}

	@Test
	void formatsFloats() {
		assertEquals("infinity", new SomlFloat(Double.POSITIVE_INFINITY).toString());
		assertEquals("-infinity", new SomlFloat(Double.NEGATIVE_INFINITY).toString());
		assertEquals("0.30000000000000004", new SomlFloat(0.1 + 0.2).toString());
		assertEquals("1e21", new SomlFloat(1e21).toString());
		assertEquals("100000000000000000000.0", new SomlFloat(1e20).toString());
		assertEquals("1e-7", new SomlFloat(1e-7).toString());
		assertEquals("0.000001", new SomlFloat(1e-6).toString());
		assertEquals("5e-324", new SomlFloat(Double.MIN_VALUE).toString());
		assertEquals("1.7976931348623157e308", new SomlFloat(Double.MAX_VALUE).toString());
		assertEquals("-1.5", new SomlFloat(-1.5).toString());
		assertEquals("2e23", new SomlFloat(2e23).toString());
	}

	@Test
	void refusesTextThatIsNotRepresentable() {
		assertThrows(IllegalArgumentException.class, () -> new SomlString("a\rb"));
		assertThrows(IllegalArgumentException.class, () -> new SomlString("\uD800"));
		assertThrows(IllegalArgumentException.class, () -> new SomlObject(Map.of("a\r", SomlNull.INSTANCE)));
		assertThrows(NullPointerException.class, () -> new SomlArray(java.util.Arrays.asList(SomlNull.INSTANCE, null)));
	}

	@Test
	void formatsStrings() {
		assertEquals("'plain'", new SomlString("plain").toString());
		assertEquals("'C:\\Users\\sindre'", new SomlString("C:\\Users\\sindre").toString());
		assertEquals("\"it's\"", new SomlString("it's").toString());
		assertEquals("\"a\\tb\\nc\\u{0}\\u{7f} \\\"q\\\" \\\\\"", new SomlString("a\tb\nc\u0000\u007F \"q\" \\").toString());
		assertEquals("'\u2028😀'", new SomlString("\u2028😀").toString());
	}

	@Test
	void comparesObjectsWithoutOrder() {
		var first = new LinkedHashMap<String, SomlValue>();
		first.put("a", new SomlInt(1));
		first.put("b", new SomlInt(2));
		var second = new LinkedHashMap<String, SomlValue>();
		second.put("b", new SomlInt(2));
		second.put("a", new SomlInt(1));
		assertEquals(new SomlObject(first), new SomlObject(second));
		assertEquals(new SomlObject(first).hashCode(), new SomlObject(second).hashCode());
		assertEquals(List.of("b", "a"), new ArrayList<>(new SomlObject(second).members().keySet()));
	}

	@Test
	void sortsKeysByScalarValue() {
		var members = new LinkedHashMap<String, SomlValue>();
		members.put("😀", new SomlInt(1));
		members.put("\uFB00", new SomlInt(2));
		members.put("b", new SomlInt(3));
		members.put("a b", new SomlInt(4));
		assertEquals("'a b': 4\nb: 3\n'\uFB00': 2\n'😀': 1\n", Soml.encodeCanonical(new SomlObject(members)));
		assertEquals("{\n\t'a b': 4\n\tb: 3\n\t'\uFB00': 2\n\t'😀': 1\n}", new SomlObject(members).toString());
	}

	@Test
	void comparesInstantsAsPointsInTime() {
		var parsed = (SomlObject) Soml.parse("a: 2026-09-19T16:00:00+02:00\nb: 2026-09-19T14:00:00.120Z");
		assertEquals(new SomlInstant(Instant.parse("2026-09-19T14:00:00Z")), parsed.get("a"));
		assertEquals("2026-09-19T14:00:00Z", parsed.get("a").toString());
		assertEquals("2026-09-19T14:00:00.12Z", parsed.get("b").toString());
		assertEquals("0001-01-01T00:00:00Z", new SomlInstant(SomlInstant.MIN).toString());
		assertEquals("9999-12-31T23:59:59.999999999Z", new SomlInstant(SomlInstant.MAX).toString());
		assertThrows(IllegalArgumentException.class, () -> new SomlInstant(SomlInstant.MAX.plusNanos(1)));
	}

	@Test
	void roundTripsEveryMonthBoundaryInTheInstantRange() {
		// The JDK is the oracle for the custom civil-date calculation and canonical fractional seconds.
		var formatter = new DateTimeFormatterBuilder().appendInstant(-1).toFormatter(Locale.ROOT);
		var fractions = new int[] {0, 1, 10, 100, 1000, 10_000, 1_000_000, 100_000_000, 120_000_000, 999_999_999};

		for (var year = 1; year <= 9999; year++) {
			for (var month = 1; month <= 12; month++) {
				var calendarMonth = YearMonth.of(year, month);
				var nanoseconds = fractions[(year + month) % fractions.length];

				for (var dateTime : List.of(calendarMonth.atDay(1).atStartOfDay(), calendarMonth.atEndOfMonth().atTime(23, 59, 59))) {
					var instant = dateTime.withNano(nanoseconds).toInstant(ZoneOffset.UTC);
					var text = formatter.format(instant);
					var value = new SomlInstant(instant);
					assertEquals(text, value.toString());
					assertEquals(value, ((SomlArray) Soml.parse("[" + text + "]")).get(0), text);
				}
			}
		}
	}

	@Test
	void formatsDurations() {
		assertEquals("1h30m", new SomlDuration(Duration.ofMinutes(90)).toString());
		assertEquals("1.5s", new SomlDuration(Duration.ofMillis(1500)).toString());
		assertEquals("0.00025s", new SomlDuration(Duration.ofNanos(250_000)).toString());
		assertEquals("0s", new SomlDuration(Duration.ZERO).toString());
		assertEquals("-5m", new SomlDuration(Duration.ofMinutes(-5)).toString());
		assertEquals("-2562047h47m16.854775808s", SomlDuration.ofNanos(Long.MIN_VALUE).toString());
		assertEquals("2562047h47m16.854775807s", SomlDuration.ofNanos(Long.MAX_VALUE).toString());
		assertThrows(IllegalArgumentException.class, () -> new SomlDuration(SomlDuration.MAX.plusNanos(1)));
	}

	@Test
	void refusesDeepTrees() {
		SomlValue value = SomlArray.of();

		for (var level = 1; level < 100; level++) {
			value = SomlArray.of(value);
		}

		assertEquals(100, Soml.encodeCanonical(value).lines().filter(line -> line.contains("[")).count());
		var tooDeep = SomlArray.of(value);
		var error = assertThrows(SomlException.class, () -> Soml.encode(tooDeep));
		assertEquals("Cannot encode a value nested more than 100 levels deep", error.reason());
		// `toString()` never throws.
		assertTrue(tooDeep.toString().startsWith("[\n\t["));
	}

	@Test
	void formatsErrors() {
		var error = assertThrows(SomlException.class, () -> Soml.parse("name: 'api'\nport: 80\nhost: localhost\n"));
		assertEquals("Unexpected “localhost”. A string value must be quoted, as in 'localhost'", error.reason());
		assertEquals(3, error.line());
		assertEquals(7, error.column());
		assertEquals("""
			  1 | name: 'api'
			  2 | port: 80
			> 3 | host: localhost
			    |       ^""", error.codeFrame());
		assertEquals(error.reason() + " at line 3, column 7\n\n" + error.codeFrame(), error.getMessage());
		assertEquals(List.of(), error.path());
	}

	@Test
	void checksTheValueOfAnUppercaseUnicodeEscapeBeforeItsSpelling() {
		assertReason("a: \"\\u{D}\"", "A carriage return (U+000D) cannot be represented, so \\u{D} is not allowed");
		assertReason("a: \"\\u{dFfF}\"", "\\u{dFfF} is a surrogate, which is not a Unicode scalar value");
		assertReason("a: \"\\u{FFFFFF}\"", "\\u{FFFFFF} is above U+10FFFF, the largest Unicode scalar value");
		assertReason("a: \"\\u{E9}\"", "A Unicode escape uses lowercase hexadecimal digits");
	}

	@Test
	void reportsAnOffsetAfterASpace() {
		assertReason("a: 2026-09-19T14:00:00 Z", "An instant's offset follows its time directly, without a space, as in 2026-09-19T14:00:00Z");
		assertReason("a: [2026-09-19T14:00:00 +02:00, 1]", "An instant's offset follows its time directly, without a space, as in 2026-09-19T14:00:00+02:00");
		assertReason("a: 2026-02-30T14:00:00 Z", "An instant's offset follows its time directly, without a space");
		assertReason("a: 2026-09-19T14:00:00 Zulu", "An instant needs an offset: Z or ±HH:MM. A date and time without an offset is a local time, which is not an instant, so write it as a string, as in '2026-09-19T14:00:00', or add the offset it was meant in");
	}

	@Test
	void reportsAValueWithAColonThatStartsWithAZeroAsAString() {
		assertReason("mac: 00:1A:2B:3C:4D:5E", "Invalid number “00:1A:2B:3C:4D:5E”. A string value must be quoted, as in '00:1A:2B:3C:4D:5E'");
		assertReason("a: 01A", "Leading zeros are not allowed in a decimal number");
	}

	@Test
	void reportsADurationInYears() {
		assertReason("a: 2years", "Invalid duration 2years: there is no year unit, because a year is not a fixed length. Write 8760h for a fixed 365 days");
		assertReason("a: 1y6m", "Invalid duration 1y6m: there is no year unit, because a year is not a fixed length. Write 8760h for a fixed 365 days");
		assertReason("a: 100yen", "Invalid number “100yen”. A string value must be quoted, as in '100yen'");
	}

	@Test
	void onlyReportsMissingExponentDigitsForANumber() {
		assertReason("a: 1byte", "Invalid number “1byte”. A string value must be quoted, as in '1byte'");
		assertReason("a: -verbose", "Expected a digit or “infinity” after “-”");
		assertReason("a: 1.5e-", "Expected digits after the exponent marker “e”");
	}

	@Test
	void onlyReportsNegativeNaNOrInfinityForTheWholeWord() {
		assertReason("a: -nano", "Expected a digit or “infinity” after “-”");
		assertReason("a: -info", "Expected a digit or “infinity” after “-”");
		assertReason("a: -NaN", "NaN is not representable. Use null for a missing value");
		assertReason("a: -Inf", "“-Inf” is not a value. Negative infinity is written -infinity");
	}

	@Test
	void onlyEndsAValueAtASlashThatStartsAComment() {
		assertReason("speed: 100 km/h", "Expected a line break before the next entry, but found “k”");
		assertReason("a: 2026-09-19T14:00:00/P1D", "An instant needs an offset: Z or ±HH:MM");
		assertReason("a: 2026-09-19T14:00:00 Z/x", "An instant needs an offset: Z or ±HH:MM. A date and time without an offset is a local time, which is not an instant, so write it as a string, as in '2026-09-19T14:00:00', or add the offset it was meant in");
		assertReason("a: 5 m/* c */", "A unit cannot follow a number after a space. Write a duration without the space, as in 5m, and anything else, such as a size, as a string, as in '5 m'");
	}

	@Test
	void onlyReportsAMisplacedUnderscoreInANumber() {
		assertReason("a: 4k_video", "Invalid number “4k_video”. A string value must be quoted, as in '4k_video'");
		assertReason("a: 1e_5", "An underscore in a number must be between two digits");
	}

	@Test
	void onlyReportsAnUppercaseExponentMarkerWhereOneGoes() {
		assertReason("price: 5EUR", "Invalid number “5EUR”. A string value must be quoted, as in '5EUR'");
		assertReason("a: 1.5E-3", "An exponent marker is a lowercase “e”");
	}

	private static void assertReason(String source, String reason) {
		var error = assertThrows(SomlException.class, () -> Soml.parse(source));
		assertEquals(reason, error.reason(), source);
	}

	@Test
	void sanitizesErrors() {
		var error = assertThrows(SomlException.class, () -> Soml.parse("'a\u202Eb': 1\nx"));
		assertTrue(error.codeFrame().contains("'a\uFFFDb': 1"), error.codeFrame());
	}

	@Test
	void parsesBytes() {
		assertEquals(new SomlObject(Map.of("a", new SomlString("é"))), Soml.parse("a: 'é'".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

		var error = assertThrows(SomlException.class, () -> Soml.parse(new byte[] {'a', ':', ' ', '\'', (byte) 0xC3, '\''}));
		assertEquals("Invalid UTF-8 byte 0xC3", error.reason());
		assertEquals(5, error.column());

		var truncated = assertThrows(SomlException.class, () -> Soml.parse(new byte[] {'a', ':', ' ', '\'', (byte) 0xE2, (byte) 0x82}));
		assertEquals("Incomplete UTF-8 sequence at the end of the input", truncated.reason());
	}

	@Test
	void placesCaretByCodePoint() {
		var error = assertThrows(SomlException.class, () -> Soml.parse("a: ['😀😀', 1 x]"));
		assertEquals("> 1 | a: ['😀😀', 1 x]\n    |             ^", error.codeFrame());
	}

	@Test
	void serializesErrors() throws Exception {
		var error = assertThrows(SomlException.class, () -> Soml.fromValue(new SomlObject(Map.of("host", new SomlInt(1))), BindingTest.Server.class));
		var bytes = new java.io.ByteArrayOutputStream();

		try (var output = new java.io.ObjectOutputStream(bytes)) {
			output.writeObject(error);
		}

		try (var input = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
			var copy = (SomlException) input.readObject();
			assertEquals(List.of("host"), copy.path());
			assertEquals(error.getMessage(), copy.getMessage());
		}
	}

	@Test
	void parsesReplacementCharacter() {
		var expected = new SomlObject(Map.of("a", new SomlString("x\uFFFDy")));
		assertEquals(expected, Soml.parse("a: 'x\uFFFDy'".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

		// A U+FFFD before an invalid byte takes the strict path, which reports the byte.
		var error = assertThrows(SomlException.class, () -> Soml.parse(new byte[] {'a', ':', ' ', '\'', (byte) 0xEF, (byte) 0xBF, (byte) 0xBD, (byte) 0xC0, (byte) 0x80, '\''}));
		assertEquals("Invalid UTF-8 byte 0xC0", error.reason());
		assertEquals(6, error.column());
	}

	@Test
	void endsBlockCommentsAtTheFirstClosing() {
		assertEquals(new SomlObject(Map.of("a", new SomlInt(1))), Soml.parse("/* x /*/ a: 1"));
		var error = assertThrows(SomlException.class, () -> Soml.parse("/*/* */ a: 1"));
		assertEquals("Block comments cannot be nested, and their body may not contain “/*”", error.reason());
		assertEquals(3, error.column());
		assertEquals(new SomlObject(Map.of("a", new SomlInt(1), "b", new SomlInt(2))), Soml.parse("/* c */ a: 1\n/* d */ b: 2"));
	}

	@Test
	void refusesLoneSurrogatesAndControlCharactersAnywhere() {
		// The parser checks the characters of strings and comments as it reads them, and reports a character that is an error anywhere before any other error, with a control character before a lone surrogate.
		for (var document : List.of("a: 'x\uD800y'", "a: \"x\uDC00\"", "a: 1 # x\uD800", "a: 1 /* x\uD800 */", "'k\uD800': 1", "a:\n\t\"\"\"\n\tx\uD800\n\t\"\"\"")) {
			var error = assertThrows(SomlException.class, () -> Soml.parse(document), document);
			assertTrue(error.reason().startsWith("A lone surrogate (U+D"), error.reason());
		}

		var controlAfterSurrogate = assertThrows(SomlException.class, () -> Soml.parse("a: '\uD800'\nb: '\u0001'"));
		assertEquals("A raw control character (U+0001) is not allowed anywhere, including in strings and comments. In a string, write it as the escape \\u{1} inside \"...\"", controlAfterSurrogate.reason());
		assertEquals(2, controlAfterSurrogate.line());

		var controlAfterSyntaxError = assertThrows(SomlException.class, () -> Soml.parse("a: 1 b\nc: '\u007F'"));
		assertEquals(2, controlAfterSyntaxError.line());

		assertEquals(new SomlObject(Map.of("a", new SomlString("😀\t"))), Soml.parse("a: '😀\t' # 😀 /* 😀 */"));
	}

	@Test
	void parsesShortFloatsCorrectlyRounded() {
		// Short floats take a fast path that divides by a power of ten, which must give what `Double.parseDouble` gives.
		var random = new java.util.Random(1);

		for (var attempt = 0; attempt < 200_000; attempt++) {
			var integerDigits = 1 + random.nextInt(8);
			var fractionDigits = 1 + random.nextInt(14 - integerDigits);
			var integerPart = random.nextLong(1, (long) Math.pow(10, integerDigits));
			var fraction = String.format("%0" + fractionDigits + "d", random.nextLong((long) Math.pow(10, fractionDigits)));
			var text = (random.nextBoolean() ? "-" : "") + (random.nextInt(4) == 0 ? "0" : Long.toString(integerPart)) + "." + fraction;
			var value = ((SomlArray) Soml.parse("[" + text + "]")).get(0);
			assertEquals(new SomlFloat(Double.parseDouble(text)), value, text);
		}
	}

	@Test
	void findsSpecialCharactersAtEveryPositionOfFourCharacterBlocks() {
		// The parser skips plain text four characters at a time, so each special character is tried at every position of a block.
		for (var before = 0; before < 9; before++) {
			var padding = "x".repeat(before);
			var tail = "y".repeat(9 - before);
			var column = 5 + before;

			assertEquals(new SomlString(padding + "é😀" + tail), ((SomlObject) Soml.parse("a: '" + padding + "é😀" + tail + "'")).get("a"));
			assertEquals(new SomlString(padding + "\"\\" + tail), ((SomlObject) Soml.parse("a: \"" + padding + "\\\"\\\\" + tail + "\"")).get("a"));
			assertEquals(new SomlString(padding), ((SomlObject) Soml.parse("a: '" + padding + "' # " + padding + "*/" + tail + "\nb: 1")).get("a"));
			assertEquals(new SomlInt(1), ((SomlObject) Soml.parse("a: /* " + padding + "* / *" + tail + " */ 1")).get("a"));

			for (var bad : List.of("\u007F", "\u0001", "\uD800")) {
				for (var document : List.of("a: '" + padding + bad + tail + "'", "a: \"" + padding + bad + tail + "\"", "a: 1 #" + padding + bad + tail, "a: 1 /*" + padding + bad + tail + "*/")) {
					var error = assertThrows(SomlException.class, () -> Soml.parse(document), document);
					assertTrue(error.reason().startsWith(bad.equals("\uD800") ? "A lone surrogate" : "A raw control character"), error.reason());
					assertEquals(document.indexOf(bad.charAt(0)) + 1, error.column(), document);
				}
			}

			var unterminated = assertThrows(SomlException.class, () -> Soml.parse("a: '" + padding + "\n'"));
			assertTrue(unterminated.reason().startsWith("Unterminated string"), unterminated.reason());
			assertEquals(4, unterminated.column(), "column " + column);
		}
	}

	@Test
	void findsKeysWithOneHashCode() {
		// `Aa` and `BB` have the same hash code, so these keys move the tables of keys to their fallback.
		var text = new StringBuilder();
		var keys = new ArrayList<String>();

		for (var index = 0; index < 2000; index++) {
			var key = new StringBuilder();

			for (var bit = 0; bit < 11; bit++) {
				key.append((index >> bit & 1) == 0 ? "Aa" : "BB");
			}

			keys.add(key.toString());
			text.append(key).append(": ").append(index).append('\n');
		}

		var object = (SomlObject) Soml.parse(text.toString());

		for (var index = 0; index < keys.size(); index++) {
			assertEquals(new SomlInt(index), object.get(keys.get(index)));
		}

		assertEquals(null, object.get("AaAa"));
		var error = assertThrows(SomlException.class, () -> Soml.parse(text + keys.get(1500) + ": 0\n"));
		assertEquals(2001, error.line());
	}
}
