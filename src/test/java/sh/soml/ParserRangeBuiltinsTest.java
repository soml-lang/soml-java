package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ParserRangeBuiltinsTest {
	@Test
	void tableHeaderHintsStayWithinTheirLineAndDiagnosticLength() {
		for (var prefix : List.of("", "# preceding comment\n", "\n\n# preceding comment\n")) {
			for (var header : List.of("[server]", "\t[[server]] ")) {
				var source = prefix + header + "\nport: 8080";
				var error = assertThrows(SomlException.class, () -> Soml.parse(source));
				assertEquals("There are no table headers. Write the table as an object, as in server: {…}", error.reason());
			}
		}

		var longestName = "a".repeat(998);
		var bounded = assertThrows(SomlException.class, () -> Soml.parse("# comment\n[" + longestName + "]\nport: 8080"));
		assertEquals("There are no table headers. Write the table as an object, as in " + "a".repeat(40) + "…: {…}", bounded.reason());
		for (var source : List.of("[server] trailing\nport: 8080", "[server\n]\nport: 8080", "# comment\n[" + "a".repeat(999) + "]\nport: 8080", "[[server], 1]")) {
			assertFalse(assertThrows(SomlException.class, () -> Soml.parse(source)).reason().contains("There are no table headers"));
		}
	}

	@Test
	void blockCommentLineHintsOnlyConsiderTheGapBeforeTheUnexpectedItem() {
		var ordinary = "Expected “,”, a line break, or “]” after an array item, but found “2”";
		for (var source : List.of("[1 2]", "[1 2\n]", "[1 /* no break */ 2\n]", "[1 2" + "x".repeat(65_536) + "\n]")) {
			assertEquals(ordinary, assertThrows(SomlException.class, () -> Soml.parse(source)).reason());
		}

		for (var source : List.of("[1 /* a\nb */ 2]", "[1 /* a\n\nb */ 2\n]")) {
			assertEquals(ordinary + ". A line break inside a block comment does not separate items", assertThrows(SomlException.class, () -> Soml.parse(source)).reason());
		}
	}
}
