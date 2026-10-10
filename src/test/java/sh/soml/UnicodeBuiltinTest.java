package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class UnicodeBuiltinTest {
	@Test
	void sourceCharactersDistinguishPairsAndLoneSurrogates() {
		for (var character = Character.MIN_SURROGATE; character <= Character.MAX_SURROGATE; character++) {
			var source = "[\"" + character + "\"]";
			var error = assertThrows(SomlException.class, () -> Soml.parse(source));
			assertTrue(error.reason().startsWith("A lone surrogate"), error.reason());
		}

		var text = "\uD7FF\uE000𐀀😀􏿿";
		assertEquals(SomlArray.of(new SomlString(text)), Soml.parse("[\"" + text + "\"]"));
	}

	@Test
	void fourDigitEscapeHintsDistinguishPairsAndLoneSurrogates() {
		var examples = Map.of(
			"\\uD800\\uDC00", "Write \\u{10000}",
			"\\uDBFF\\uDFFF", "Write \\u{10ffff}",
			"\\uD800", "lone surrogate",
			"\\uD800\\uDBFF", "lone surrogate",
			"\\uDC00\\uDFFF", "lone surrogate",
			"\\uD7FF", "Write \\u{d7ff}",
			"\\uE000", "Write \\u{e000}"
		);

		for (var example : examples.entrySet()) {
			var error = assertThrows(SomlException.class, () -> Soml.parse("value: \"" + example.getKey() + "\""));
			assertTrue(error.reason().contains(example.getValue()), error.reason());
		}
	}
}
