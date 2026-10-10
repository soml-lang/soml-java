package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParserRefinementTest {
	@Test
	void unicodeCharacterNamesKeepTheirDiagnosticBoundariesAndSpecSpelling() {
		assertEquals("U+0085 (NEXT LINE; only space, tab, and line feed are whitespace)", Parser.describeCharacter(0x85));
		assertEquals("U+200A (HAIR SPACE; only space, tab, and line feed are whitespace)", Parser.describeCharacter(0x200A));
		assertEquals("U+200B (ZERO WIDTH SPACE; only space, tab, and line feed are whitespace)", Parser.describeCharacter(0x200B));
		assertEquals("U+200C (ZERO WIDTH NON-JOINER)", Parser.describeCharacter(0x200C));
		assertEquals("U+202E (RIGHT-TO-LEFT OVERRIDE)", Parser.describeCharacter(0x202E));
		assertEquals("U+2066 (LEFT-TO-RIGHT ISOLATE)", Parser.describeCharacter(0x2066));
		assertEquals("U+2069 (POP DIRECTIONAL ISOLATE)", Parser.describeCharacter(0x2069));
		assertEquals("U+FEFF (ZERO WIDTH NO-BREAK SPACE; only space, tab, and line feed are whitespace)", Parser.describeCharacter(0xFEFF));

		for (var codePoint : new int[] {0x84, 0x200F + 1, 0x202E + 2, 0x2066 - 1, 0x2069 + 1}) {
			assertFalse(Parser.describeCharacter(codePoint).contains("("));
		}
	}

	@Test
	void quotedKeysKeepTheirDecodedIdentityAcrossSyntaxAndEditing() {
		var source = "'literal\\path': 1\n\"plain😀\": 2\n\"escaped\\n\\u{1f600}\": {nested: 3}";
		var tree = SyntaxTree.parse(source);
		var members = ((SyntaxTree.ObjectNode) tree.body).members();
		assertEquals(List.of("literal\\path", "plain😀", "escaped\n😀"), members.stream().map(member -> member.key().value()).toList());

		assertEquals("'literal\\path': 1", source.substring(members.getFirst().start(), members.getFirst().end()));
		assertEquals("\"escaped\\n\\u{1f600}\": {nested: 3}", source.substring(members.getLast().start(), members.getLast().end()));

		var document = SomlDocument.parse(source);
		document.set(List.of("escaped\n😀", "nested"), 4);
		document.set(List.of("literal\\path"), 5);
		assertEquals(new SomlInt(4), document.get(List.of("escaped\n😀", "nested")));
		assertTrue(document.toString().startsWith("'literal\\path': 5\n\"plain😀\": 2\n"));
		assertEquals(Soml.parse(document.toString()), document.value());
	}

	@Test
	void decimalFastPathBoundariesKeepExactValuesAndFallbackDiagnostics() {
		for (var token : List.of("999999999999999999", "-999999999999999999", "9223372036854775807", "-9223372036854775808")) {
			assertEquals(new SomlInt(Long.parseLong(token)), ((SomlArray) Soml.parse("[" + token + "]")).get(0));
		}

		for (var token : List.of("1234567890123.4", "12345678901234.5", "0.0000000000001", "0.00000000000001", "-1234567890123.4", "123456789012345678.0", "0." + "0".repeat(10_000))) {
			assertEquals(new SomlFloat(Double.parseDouble(token)), ((SomlArray) Soml.parse("[" + token + "]")).get(0), token);
		}

		assertTrue(assertThrows(SomlException.class, () -> Soml.parse("[" + "9".repeat(10_000) + "]")).reason().contains("outside the 64-bit range"));
		assertTrue(assertThrows(SomlException.class, () -> Soml.parse("[0." + "0".repeat(10_000) + "1]")).reason().contains("too small"));
	}

	@Test
	void blockStringBulkEscapeSearchStaysWithinEachLine() {
		var plain = "plain".repeat(1000);
		var source = "text: \"\"\"\n\t" + plain + "\\n\\u{1f600}\n\tsecond\\t\\\\\\\"\n\tlast\n\t\"\"\"";
		var expected = new SomlString(plain + "\n😀\nsecond\t\\\"\nlast");
		assertEquals(expected, ((SomlObject) Soml.parse(source)).get("text"));
		assertEquals(Soml.parse(source), Soml.parse(Soml.format(source)));

		var error = assertThrows(SomlException.class, () -> Soml.parse("text: \"\"\"\n\tbad\\\n\t\"\"\""));
		assertTrue(error.reason().startsWith("A backslash must be followed"));
		assertEquals(2, error.line());
		assertEquals(5, error.column());
	}

	@Test
	void removingNestedValuesPreservesCommentsBeyondTheirContainer() {
		var document = SomlDocument.parse("a: {\n\tremove: [1]\n}\n" + "# afterwards\n".repeat(1000) + "b: 2\n");
		assertTrue(document.remove(List.of("a", "remove")));
		assertTrue(document.toString().startsWith("a: {}\n# afterwards\n"));
		assertEquals(1000, document.toString().split("# afterwards", -1).length - 1);
		assertFalse(document.remove(List.of("a", "remove")));
		assertEquals(new SomlInt(2), document.get(List.of("b")));
	}

	@Test
	void punctuationAndCommentDelimitersRemainContentAtEveryScanBoundary() {
		for (var character = 0x20; character < 0x7F; character++) {
			for (var prefixLength = 0; prefixLength < 4; prefixLength++) {
				var value = new SomlString("x".repeat(prefixLength) + (char) character + "tail");
				var source = Soml.encode(SomlArray.of(value));
				assertEquals(SomlArray.of(value), Soml.parse(source));
				assertEquals(SomlArray.of(value), Soml.parse(source.getBytes(StandardCharsets.UTF_8)));
				assertEquals(SomlArray.of(value), Soml.parse(Soml.format(source)));
			}
		}
	}
}
