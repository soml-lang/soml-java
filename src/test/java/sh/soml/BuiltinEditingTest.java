package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltinEditingTest {
	@Test
	void blockStringDelimitersMatchExactlyAcrossFormattingAndEditing() {
		for (var quote : List.of("'", "\"")) {
			for (var length : List.of(3, 4, 5, 16, 64)) {
				var delimiter = quote.repeat(length);
				var content = "\t" + quote.repeat(length - 1) + "\n\t" + quote.repeat(length + 1) + "\n\t😀 ordinary content\n";
				var source = "value: " + delimiter + "\n" + content + "\t" + delimiter + "\nother: 0xCAFE\n";
				var value = Soml.parse(source);
				var formatted = Soml.format(source);
				assertEquals(value, Soml.parse(formatted));
				assertEquals(formatted, Soml.format(formatted));
				var document = SomlDocument.parse(source);
				document.set(List.of("added"), 1);
				assertTrue(document.toString().startsWith(source));
				assertEquals(((SomlObject) value).get("value"), document.get(List.of("value")));
			}
		}
	}

	@Test
	void controlEscapesKeepMinimalLowercaseHexSpelling() {
		var value = "\0\u000F\u0010\u001F\u007F\t\n\"\\'";
		assertEquals("\"\\u{0}\\u{f}\\u{10}\\u{1f}\\u{7f}\\t\\n\\\"\\\\'\"", new SomlString(value).toString());
		var encoded = Soml.encode(List.of(value));
		assertEquals(SomlArray.of(new SomlString(value)), Soml.parse(encoded));
	}

	@Test
	void writerAcceptsUnicodePairsAndRejectsMalformedPairsAfterEscapes() {
		var value = "quoted '\t😀\uDBFF\uDFFF";
		assertEquals(SomlArray.of(new SomlString(value)), Soml.parse(Soml.encode(List.of(value))));
		for (var invalid : List.of("'\uD800", "'\uDC00", "'\uD800x", "'\uDC00\uD800")) {
			assertTrue(assertThrows(IllegalArgumentException.class, () -> new SomlString(invalid)).getMessage().contains("lone surrogate"));
			assertTrue(assertThrows(SomlException.class, () -> Soml.encode(List.of(invalid))).reason().contains("lone surrogate"));
		}
	}
}
