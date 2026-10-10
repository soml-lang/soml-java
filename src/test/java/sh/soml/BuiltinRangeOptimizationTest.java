package sh.soml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltinRangeOptimizationTest {
	@Test
	void canonicalIndexesCoverLargeObjectsAndIgnoreUnusedCapacity() {
		var keys = new String[301];
		var expected = new Integer[300];

		for (var index = 0; index < 300; index++) {
			keys[index] = "key" + (1299 - index);
			expected[index] = 299 - index;
		}

		keys[300] = "unused";
		assertArrayEquals(expected, Writer.canonicalOrder(keys, 300));
		assertArrayEquals(new Integer[] {2, 1, 0}, Writer.canonicalOrder(new String[] {"😀", "\uE000", "z"}, 3));
		assertNull(Writer.canonicalOrder(new String[] {"a", "b", "unused"}, 2));
	}

	@Test
	void dottedKeySuggestionsRequireNonemptyWords() {
		assertEquals("A bare key cannot contain “.”. Quote it, as in 'a.b.c', or use braces to nest, as in a: {b: {c: …}}", assertThrows(SomlException.class, () -> Soml.parse("a.b.c: 1")).reason());

		for (var key : List.of("a.", "a..b", "a.b.")) {
			assertEquals("A key cannot contain “.” unless it is quoted. Quote the whole key, or use braces to nest, as in a: {b: …}", assertThrows(SomlException.class, () -> Soml.parse(key + ": 1")).reason());
		}
	}

	@Test
	void blockIndentationMatchesExactSourceRangesAndPreservesContent() {
		for (var indentation : List.of("", "  ", "\t ", " ".repeat(256))) {
			for (var content : List.of("plain", "😀\u2003")) {
				var source = "value: '''\n" + indentation + "first  \n \t\n" + indentation + content + "\n" + indentation + "'''";
				assertEquals(new SomlString("first  \n\n" + content), ((SomlObject) Soml.parse(source)).get("value"));
			}
		}

		var source = "value: '''\n \tcontent\n\t '''";
		var error = assertThrows(SomlException.class, () -> Soml.parse(source));
		assertEquals("This line does not start with the indentation of its block string's closing delimiter. Every line except a blank one must start with exactly the same spaces and tabs", error.reason());
		assertEquals(2, error.line());
	}

	@Test
	void inlineBlockDelimiterHintsCountOnlyThePrecedingLines() {
		for (var precedingLines : new int[] {0, 1, 1000}) {
			for (var prefix : List.of("# comment\n", "# 😀\n", "# " + "x".repeat(1000) + "\n")) {
				var source = prefix.repeat(precedingLines) + "value: '''\ncontent '''\nmore content";
				var error = assertThrows(SomlException.class, () -> Soml.parse(source));
				assertEquals("Unterminated block string. Its closing delimiter must start a line, so move the ''' at the end of line " + (precedingLines + 2) + " to a new line", error.reason());
			}
		}
	}
}
