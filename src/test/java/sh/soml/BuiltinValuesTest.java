package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuiltinValuesTest {
	@Test
	void diagnosticContextIncludesEmptyLinesAtTheBeginningOfADocument() {
		var source = "\n\ntext";
		var frames = new String[] {
			"> 1 |\n    | ^",
			"  1 |\n> 2 |\n    | ^",
			"  1 |\n  2 |\n> 3 | text\n    | ^"
		};

		for (var offset = 0; offset < frames.length; offset++) {
			var error = SomlException.at("bad", source, offset);
			assertEquals(offset + 1, error.line());
			assertEquals(1, error.column());
			assertEquals(frames[offset], error.codeFrame());
		}
	}

	@Test
	void diagnosticWindowBoundariesKeepEveryValidSurrogatePairWhole() {
		for (var position = 0; position <= 200; position++) {
			var source = "x".repeat(position) + "😀" + "x".repeat(200 - position);

			for (var offset : new int[] {0, 1, 49, 50, 51, 99, 100, 101, 149, 150, 151, 201, 202}) {
				var error = SomlException.at("bad", source, offset);
				assertFalse(error.codeFrame().contains("�"), error.codeFrame());
				assertTrue(error.codeFrame().endsWith("^"));
				assertEquals(1, error.line());
			}
		}
	}
}
