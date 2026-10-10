package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParserStreamliningTest {
	@Test
	void bareKeysBeyondTheCacheLimitKeepTheirFullIdentity() {
		for (var length : new int[] {31, 32, 33, 1024}) {
			var prefix = "k".repeat(length);
			var first = prefix + "Aa";
			var second = prefix + "BB";
			var source = "[{" + first + ": 1, " + second + ": 2}, {" + second + ": 3, " + first + ": 4}]";
			var array = (SomlArray) Soml.parse(source);
			assertEquals(new SomlInt(1), ((SomlObject) array.get(0)).get(first));
			assertEquals(new SomlInt(2), ((SomlObject) array.get(0)).get(second));
			assertEquals(new SomlInt(3), ((SomlObject) array.get(1)).get(second));
			assertEquals(new SomlInt(4), ((SomlObject) array.get(1)).get(first));
			assertEquals(array, Soml.parse(Soml.format(source)));
			assertThrows(SomlException.class, () -> Soml.parse(first + ": 1\n" + first + ": 2"));
		}
	}

	@Test
	void integerRangesHandleLongRadixPaddingWithoutChangingSignedLimits() {
		var padding = "0_".repeat(10_000);
		var integers = Map.of(
			"0x" + padding + "0", 0L,
			"0x" + padding + "7FFF_FFFF_FFFF_FFFF", Long.MAX_VALUE,
			"0o" + padding + "777777777777777777777", Long.MAX_VALUE,
			"0b" + padding + "1".repeat(63), Long.MAX_VALUE,
			"9_223_372_036_854_775_807", Long.MAX_VALUE,
			"-9_223_372_036_854_775_808", Long.MIN_VALUE
		);

		for (var entry : integers.entrySet()) {
			assertEquals(new SomlInt(entry.getValue()), ((SomlArray) Soml.parse("[" + entry.getKey() + "]")).get(0));
		}

		for (var token : List.of("0x" + padding + "8000000000000000", "0o" + padding + "1" + "0".repeat(21), "0b" + padding + "1".repeat(64), "9_".repeat(10_000) + "9", "-" + "9".repeat(10_000))) {
			var reason = assertThrows(SomlException.class, () -> Soml.parse("[" + token + "]")).reason();
			assertTrue(reason.contains("outside the 64-bit range"));
			assertTrue(reason.length() < 200);
		}
	}

	@Test
	void longMalformedTokensKeepSpecificBoundedDiagnostics() {
		for (var source : List.of("key: " + "word".repeat(10_000), "key: 1 " + "meter".repeat(10_000), "first " + "word ".repeat(10_000) + ": 1")) {
			var reason = assertThrows(SomlException.class, () -> Soml.parse(source)).reason();
			assertTrue(reason.length() < 400);
			assertTrue(reason.contains("…"));
		}
	}

	@Test
	void durationPartRangesPreserveUnitsUnderscoresAndSignedLimits() {
		var durations = Map.of(
			"1h2m3s4ms5us6ns", 3_723_004_005_006L,
			"1_000ms2_000us3_000ns", 1_002_003_000L,
			"0.0_0_0_0_0_0_0_0_0_0_0_5h", 18L,
			"0.000000001" + "0_0".repeat(1000) + "s", 1L,
			"0.0_0h", 0L,
			"9223372036854775807ns", Long.MAX_VALUE,
			"-9_223_372_036_854_775_808ns", Long.MIN_VALUE
		);

		for (var entry : durations.entrySet()) {
			assertEquals(SomlDuration.ofNanos(entry.getValue()), ((SomlArray) Soml.parse("[" + entry.getKey() + "]")).get(0), entry.getKey());
		}

		for (var token : List.of("1hms", "1h1h", "1ns1us", "1mS", "1xs", "1.0_0_0_0_0_0_0_0_0_1s", "9223372036854775808ns", "-9223372036854775809ns")) {
			assertThrows(SomlException.class, () -> Soml.parse("[" + token + "]"), token);
		}
	}

	@Test
	void allInstantFractionLengthsKeepExactNanosecondsAndOffsets() {
		var fraction = "123456789";

		for (var length = 1; length <= fraction.length(); length++) {
			var digits = fraction.substring(0, length);
			var expected = Instant.parse("2026-01-02T03:04:05." + digits + "Z");
			var utc = ((SomlArray) Soml.parse("[2026-01-02T03:04:05." + digits + "Z]")).get(0);
			var offset = ((SomlArray) Soml.parse("[2026-01-02T10:04:05." + digits + "+07:00]")).get(0);
			assertEquals(new SomlInstant(expected), utc);
			assertEquals(utc, offset);
		}
	}

	@Test
	void syntaxKeyMatchingDistinguishesLiteralBackslashesAndEscapes() {
		var source = "plain: 1\n'line\\n': 2\n\"line\\n\": 3\n\"\\u{1f600}\": 4\n\"prefix-extra\": 5\n'prefix': 6";
		var tree = SyntaxTree.parse(source);
		var object = (SyntaxTree.ObjectNode) tree.body;
		assertEquals(List.of("plain", "line\\n", "line\n", "😀", "prefix-extra", "prefix"), object.members().stream().map(member -> member.key().value()).toList());

		var document = SomlDocument.parse(source);

		for (var key : List.of("plain", "line\\n", "line\n", "😀", "prefix-extra", "prefix")) {
			document.set(List.of(key), key);
			assertEquals(new SomlString(key), document.get(List.of(key)));
		}

		assertEquals(Soml.parse(document.toString()), document.value());
		assertEquals(Soml.parse(source), Soml.parse(Soml.format(source)));
	}

	@Test
	void unicodeEscapeValidationKeepsValueErrorsBeforeSpellingErrors() {
		for (var codePoint : new int[] {0, 1, 9, 10, 11, 12, 14, 0x7f, 0x80, 0xfff, 0xffff, 0x10000, 0x10ffff}) {
			var escape = "\\u{" + Integer.toHexString(codePoint) + "}";
			assertEquals(new SomlString("prefix" + Character.toString(codePoint) + "suffix"), ((SomlArray) Soml.parse("[\"prefix" + escape + "suffix\"]")).get(0));
		}

		for (var token : List.of("D", "000d", "D800", "110000")) {
			var reason = assertThrows(SomlException.class, () -> Soml.parse("[\"\\u{" + token + "}\"]")).reason();
			assertFalse(reason.contains("lowercase"));
			assertFalse(reason.contains("leading zeros"));
		}

		assertTrue(assertThrows(SomlException.class, () -> Soml.parse("[\"\\u{00a}\"]")).reason().contains("write \\u{a}"));
		assertTrue(assertThrows(SomlException.class, () -> Soml.parse("[\"\\u{aA}\"]")).reason().contains("lowercase"));
	}

	@Test
	void removingTheOnlyItemKeepsOnlyCommentsOutsideItsRemoval() {
		for (var opening : List.of("[", "{")) {
			var closing = opening.equals("[") ? "]" : "}";
			var item = opening.equals("[") ? "1" : "item: 1";
			var component = opening.equals("[") ? (Object) 0 : "item";

			for (var comments : List.of("", "\n# keep before\n", "/* keep before */\n")) {
				var document = SomlDocument.parse("# outside\na: " + opening + comments + item + " # owned\n" + closing + "\n# afterwards");
				assertTrue(document.remove(List.of("a", component)));
				assertEquals(comments.isEmpty(), !document.toString().contains("keep before"));
				assertTrue(document.toString().contains("# outside"));
				assertTrue(document.toString().endsWith("# afterwards"));
				assertFalse(document.toString().contains("# owned"));
				assertEquals(Soml.parse(document.toString()), document.value());
			}
		}
	}
}
