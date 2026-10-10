package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DiagnosticGutterBuiltinsTest {
	@Test
	void decimalLineTransitionsAlignContextAndCaretWithoutChangingMessages() {
		for (var line : new int[] {1, 2, 9, 10, 11, 99, 100, 101, 999, 1000, 1001, 9999, 10000}) {
			var text = "\n".repeat(line - 1) + "\t😀!";
			var error = SomlException.at("example", text, text.length() - 1);
			var digits = Integer.toString(line).length();
			var frame = new StringBuilder();

			for (var previous = Math.max(1, line - 2); previous < line; previous++) {
				frame.append("  ").append(" ".repeat(digits - Integer.toString(previous).length())).append(previous).append(" |\n");
			}

			frame.append("> ").append(line).append(" | \t😀!\n  ").append(" ".repeat(digits)).append(" | \t ^");
			assertEquals(line, error.line());
			assertEquals(3, error.column());
			assertEquals(frame.toString(), error.codeFrame());
			assertEquals("example at line " + line + ", column 3\n\n" + frame, error.getMessage());
		}
	}
}
