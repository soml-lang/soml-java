package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParserRoundTwoTest {
	@Test
	void sharedContentValidationHandlesEveryAsciiBoundaryAcrossItsCallers() {
		for (var character = 0; character < 128; character++) {
			if (character == '\n' || character == '\'' || character == '"' || character == '\\') {
				continue;
			}

			var content = "abc" + (char) character + "defgh";
			var documents = List.of("['" + content + "']", "[\"" + content + "\"]", "#" + content + "\nx: 1", "['''\n" + content + "\n''']");

			for (var document : documents) {
				if ((character < 32 && character != '\t') || character == 127) {
					var tree = assertThrows(SomlException.class, () -> Soml.parse(document));
					var validation = assertThrows(SomlException.class, () -> Parser.validate(document));
					assertEquals(tree.reason(), validation.reason());
					assertEquals(tree.line(), validation.line());
					assertEquals(tree.column(), validation.column());
				} else {
					Parser.validate(document);
					assertEquals(Soml.parse(document), Soml.parse(Soml.format(document)));
				}
			}
		}
	}

	@Test
	void quotedKeyCachingHandlesDecodedAliasesCollisionsAndLengthBoundaries() {
		for (var length : new int[] {1, 31, 32, 33}) {
			var key = "k".repeat(length);
			var source = "[{" + key + ": 1}, {'" + key + "': 2}, {\"" + key + "\": 3}, {\"\\u{6b}" + key.substring(1) + "\": 4}]";
			var objects = ((SomlArray) Soml.parse(source)).items().stream().map(SomlObject.class::cast).toList();
			var firstKey = objects.getFirst().members().keySet().iterator().next();

			for (var position = 0; position < objects.size(); position++) {
				var object = objects.get(position);
				assertEquals(new SomlInt(position + 1), object.get(key));

				if (length <= 32) {
					assertSame(firstKey, object.members().keySet().iterator().next());
				}
			}
		}

		var source = "[{'Aa': 1, \"BB\": 2}, {\"Aa\": 3, 'BB': 4}]";
		assertEquals(new SomlObject(Map.of("Aa", new SomlInt(3), "BB", new SomlInt(4))), ((SomlArray) Soml.parse(source)).get(1));
		assertTrue(assertThrows(SomlException.class, () -> Soml.parse("Aa: 1\n\"\\u{41}a\": 2")).reason().contains("Duplicate key"));
	}

	@Test
	void escapeDiagnosticsKeepTheirPriorityAndSurrogatePairSuggestions() {
		var cases = Map.of(
			"u1234", "Write \\u{1234}",
			"uD83D\\uDE00", "Write \\u{1f600}",
			"uD83D\\u0041", "lone surrogate",
			"u{aA123456}", "lowercase hexadecimal",
			"u{}", "needs one to six",
			"u{1234567}", "at most six",
			"u{12345gA}", "is written \\u{…}"
		);

		for (var entry : cases.entrySet()) {
			var source = "[\"\\" + entry.getKey() + "\"]";
			assertTrue(assertThrows(SomlException.class, () -> Soml.parse(source)).reason().contains(entry.getValue()), entry.getKey());
		}
	}

	@Test
	void boundedHintParsingRetainsNumericAndDurationSpellings() {
		assertEquals(SomlArray.of(new SomlDate(LocalDate.of(2026, 1, 2))), Soml.parse("[2026-01-02]"));
		assertEquals(SomlDuration.ofNanos(18), ((SomlArray) Soml.parse("[0.0_0_0_0_0_0_0_0_0_0_0_5h]")).get(0));

		for (var source : List.of("[0X10]", "[-NaN]", "[-INF]", "[01]", "[0_1]", "[-x]", "[1word]", "[2026-01-02 03:04:05]", "[1," + "000,".repeat(1000) + "000]", "[" + "9_".repeat(1000) + "9ns]", "[1h_" + "9".repeat(100_000) + "]")) {
			var tree = assertThrows(SomlException.class, () -> Soml.parse(source));
			var validation = assertThrows(SomlException.class, () -> Parser.validate(source));
			assertEquals(tree.reason(), validation.reason());
			assertTrue(tree.reason().length() < 400);
		}
	}

	@Test
	void fixedInstantAndYearUnitDiagnosticsRespectTokenBoundaries() {
		for (var unit : List.of("y", "Y", "yr", "yrs", "year", "years", "YeArS")) {
			assertTrue(assertThrows(SomlException.class, () -> Soml.parse("[1" + unit + "]")).reason().contains("no year unit"), unit);
		}

		for (var token : List.of("1yen", "1yearly", "1yearsfoo", "1yrs_extra")) {
			assertTrue(assertThrows(SomlException.class, () -> Soml.parse("[" + token + "]")).reason().contains("string value must be quoted"), token);
		}

		for (var offset : List.of("Z", "+07:00", "-02:30")) {
			assertTrue(assertThrows(SomlException.class, () -> Soml.parse("[2026-01-02T03:04:05 " + offset + "]")).reason().contains("without a space"));
		}

		assertTrue(assertThrows(SomlException.class, () -> Soml.parse("[2026-01-02T03:04:05+0700]")).reason().contains("written with a colon"));
		assertTrue(assertThrows(SomlException.class, () -> Soml.parse("[2026-01-02 03:04:05Z]")).reason().contains("not a space"));
	}

	@Test
	void diagnosticHexAndJsonFormattingKeepExactRepresentations() {
		assertEquals("U+0000", Parser.formatCodePoint(0));
		assertEquals("U+000A", Parser.formatCodePoint(10));
		assertEquals("U+FFFF", Parser.formatCodePoint(0xffff));
		assertEquals("U+10000", Parser.formatCodePoint(0x10000));
		assertEquals("U+10FFFF", Parser.formatCodePoint(0x10ffff));
		assertEquals("\"safe😀é\\\"\\\\\\n\\t\\u0000\\ud800x\\udc00\"", Parser.quoteJson("safe😀é\"\\\n\t\u0000\ud800x\udc00"));
		assertEquals("Invalid UTF-8 byte 0xFF", assertThrows(SomlException.class, () -> Parser.decode(new byte[] {(byte) 0xff})).reason());
		assertEquals("Invalid UTF-8 byte 0xC0", assertThrows(SomlException.class, () -> Parser.decode(new byte[] {(byte) 0xc0})).reason());
	}
}
