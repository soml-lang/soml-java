package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TransformationTest {
	@Test
	void formattingPreservesMixedScalarSpellingsAndValues() {
		var source = """
			# service
			hex: 0xFF
			octal: 0o644
			float: 1_000.50e-2
			time: 2026-09-19T16:00:00.120+02:00
			duration: 90m
			text:
			    '''
			    literal ' quote
			    line with spaces\s\s
			    '''
			nested: { /* comma , brace } */ items: [true,null,"a\\tb",], }
			""";
		var formatted = Soml.format(source);
		assertEquals(Soml.parse(source), Soml.parse(formatted));
		assertEquals(formatted, Soml.format(formatted));

		for (var spelling : List.of("0xFF", "0o644", "1_000.50e-2", "2026-09-19T16:00:00.120+02:00", "90m")) {
			assertTrue(formatted.contains(spelling), spelling);
		}
	}

	@Test
	void formattingIgnoresPunctuationInsideManyAdjacentComments() {
		// Each comment contains punctuation that would end the collection if scanned as tokens.
		var source = "a: [1," + " /* , ] } ' \" */".repeat(200) + " 2,]\nb: 3";
		var formatted = Soml.format(source);
		assertEquals(Soml.parse(source), Soml.parse(formatted));
		assertEquals(formatted, Soml.format(formatted));
		assertEquals(200, formatted.split("/\\*", -1).length - 1);
	}

	@Test
	void sequentialEditsKeepTheTextAndTreeInAgreement() {
		var document = SomlDocument.parse("original: 0xFF # Keep\nitems: []\n");

		for (var index = 0; index < 30; index++) {
			document.set(List.of("created", "key" + index), List.of(index, "value" + index));
			document.set(List.of("items", index), Map.of("index", index));
			assertEquals(Soml.parse(document.toString()), document.value());
			assertTrue(document.toString().startsWith("original: 0xFF # Keep\n"));
		}

		for (var index = 29; index >= 0; index--) {
			assertTrue(document.remove(List.of("created", "key" + index)));
			assertTrue(document.remove(List.of("items", index)));
			assertFalse(document.remove(List.of("created", "key" + index)));
			assertEquals(Soml.parse(document.toString()), document.value());
		}
	}

	@Test
	void quotedUnicodeKeysCanBeEditedByTheirDecodedValues() {
		var document = SomlDocument.parse("\"line\\n😀\": {\"it's\": [1]}\n");
		document.set(List.of("line\n😀", "it's", 1), "é");
		assertEquals(new SomlString("é"), document.get(List.of("line\n😀", "it's", 1)));
		assertTrue(document.toString().startsWith("\"line\\n😀\": {\"it's\": "));
		assertEquals(Soml.parse(document.toString()), document.value());
		assertTrue(document.remove(List.of("line\n😀", "it's", 0)));
		assertEquals(Soml.parse(document.toString()), document.value());
	}

	@Test
	void parserReuseDoesNotLeakCollidingKeysOrPriorValues() {
		for (var round = 0; round < 100; round++) {
			var source = "Aa: {BB: " + round + "}\nBB: ['value" + round + "']";
			var object = (SomlObject) Soml.parse(source);
			assertEquals(new SomlInt(round), ((SomlObject) object.get("Aa")).get("BB"));
			assertEquals(SomlArray.of(new SomlString("value" + round)), object.get("BB"));
			assertThrows(SomlException.class, () -> Soml.parse("Aa: 1\nAa: 2"));
			assertEquals(new SomlObject(Map.of("BB", new SomlBool(true))), Soml.parse("BB: true"));
		}
	}

	@Test
	void decimalFastAndFallbackPathsMatchTheJdk() {
		var random = new Random(91);

		for (var index = 0; index < 2000; index++) {
			var integer = random.nextInt(1_000_000);
			var digits = random.nextInt(1_000_000);
			var text = (index % 2 == 0 ? "" : "-") + integer + "." + digits;
			var expected = new SomlFloat(Double.parseDouble(text));
			assertEquals(expected, ((SomlArray) Soml.parse("[" + text + "]")).get(0));
			var separated = text.replace(".", "_0.");
			assertEquals(new SomlFloat(Double.parseDouble(separated.replace("_", ""))), ((SomlArray) Soml.parse("[" + separated + "]")).get(0));
		}
	}

	@Test
	void instantOffsetsAndFractionsMatchTheJdk() {
		var base = Instant.parse("2026-09-19T14:00:00Z");

		for (var offsetSeconds : new int[] {-43_200, -3600, 0, 19_800, 50_400}) {
			for (var fraction : new int[] {0, 1, 10_000, 120_000_000, 999_999_999}) {
				var expected = base.plusNanos(fraction);
				var text = expected.atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds)).toString();
				// OffsetDateTime omits zero seconds; SOML requires them.
				if (fraction == 0) {
					text = expected.atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds)).format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX"));
				}

				assertEquals(new SomlInstant(expected), ((SomlArray) Soml.parse("[" + text + "]")).get(0), text);
			}
		}
	}

	@Test
	void unicodeScansWorkAcrossEveryFourCharacterBoundary() {
		for (var prefixLength = 0; prefixLength < 12; prefixLength++) {
			for (var character : List.of("é", "😀", "\uE000", "𐀀")) {
				var value = "a".repeat(prefixLength) + character + "tail";
				var source = "# " + value + "\nx: '" + value + "'\ny: \"" + value + "\\t\"";
				var parsed = (SomlObject) Soml.parse(source);
				assertEquals(new SomlString(value), parsed.get("x"));
				assertEquals(new SomlString(value + "\t"), parsed.get("y"));
				assertEquals(parsed, Soml.parse(source.getBytes(StandardCharsets.UTF_8)));
				assertEquals(parsed, Soml.parse(Soml.format(source)));
			}
		}
	}

	@Test
	void truncatedUtf8ScalarsAreRejectedAtTheirStart() {
		var prefix = "a: '".getBytes(StandardCharsets.UTF_8);

		for (var scalar : List.of("é", "€", "😀")) {
			var encoded = scalar.getBytes(StandardCharsets.UTF_8);

			for (var length = 1; length < encoded.length; length++) {
				var partial = Arrays.copyOf(prefix, prefix.length + length);
				System.arraycopy(encoded, 0, partial, prefix.length, length);
				var error = assertThrows(SomlException.class, () -> Soml.parse(partial));
				assertEquals("Incomplete UTF-8 sequence at the end of the input", error.reason());
				assertEquals(1, error.line());
				assertEquals(5, error.column());
			}
		}
	}

	@Test
	void failedEncodingLeavesTheOutputUntouchedAndThePoolReusable() throws IOException {
		var output = new ByteArrayOutputStream();
		output.write('!');
		assertThrows(SomlException.class, () -> Soml.encode(Map.of("nested", List.of(Double.NaN)), output));
		assertEquals("!", output.toString(StandardCharsets.UTF_8));
		Soml.encode(Map.of("ok", true), output);
		assertEquals("!ok: true\n", output.toString(StandardCharsets.UTF_8));
		assertEquals("a: 'next'\n", Soml.encode(Map.of("a", "next")));
	}
}
