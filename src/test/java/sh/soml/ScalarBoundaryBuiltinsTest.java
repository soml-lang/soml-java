package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScalarBoundaryBuiltinsTest {
	@Test
	void unicodeEscapesKeepTheirFullWidthWhenCheckingScalarBounds() {
		for (var codePoint : new int[] {0xD7FF, 0xE000, 0x10000, 0x1D800, 0x1DFFF, 0x10FFFF}) {
			var expected = new SomlString(Character.toString(codePoint));
			assertEquals(new SomlArray(List.of(expected)), Soml.parse("[\"\\u{" + Integer.toHexString(codePoint) + "}\"]"));
		}

		for (var escape : List.of("d800", "dfff", "D800", "DFFF")) {
			var error = assertThrows(SomlException.class, () -> Soml.parse("[\"\\u{" + escape + "}\"]"));
			assertEquals("\\u{" + escape + "} is a surrogate, which is not a Unicode scalar value", error.reason());
			assertEquals(3, error.column());
		}

		for (var escape : List.of("110000", "ffffff", "FFFFFF")) {
			var error = assertThrows(SomlException.class, () -> Soml.parse("[\"\\u{" + escape + "}\"]"));
			assertEquals("\\u{" + escape + "} is above U+10FFFF, the largest Unicode scalar value", error.reason());
			assertEquals(3, error.column());
		}
	}

	@Test
	void globalChecksKeepPairsAndTheirBoundaryNeighborsDistinct() {
		var text = "\uD7FF\uD800\uDC00\uDBFF\uDFFF\uE000";
		assertEquals(new SomlArray(List.of(new SomlString(text))), Soml.parse("['" + text + "'] # " + text));

		for (var surrogate : new char[] {'\uD800', '\uDBFF', '\uDC00', '\uDFFF'}) {
			var error = assertThrows(SomlException.class, () -> Soml.parse("['" + surrogate + "'] # " + text));
			assertEquals("A lone surrogate (U+" + Integer.toHexString(surrogate).toUpperCase(java.util.Locale.ROOT) + ") is not a Unicode scalar value", error.reason());
			assertEquals(3, error.column());
		}
	}
}
