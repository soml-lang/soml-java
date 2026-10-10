package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditingRoundTwoTest {
	@Test
	void quotedStringsKeepEscapeParityWhenFormattingAndEditing() {
		for (var count = 0; count < 12; count++) {
			var value = "long plain text ".repeat(200) + "\\".repeat(count) + "\"middle" + "\\".repeat(count);
			var source = "text: " + Parser.quoteJson(value) + "\nuntouched: 0xCAFE\n";
			var formatted = Soml.format(source);
			assertEquals(Soml.parse(source), Soml.parse(formatted));
			assertEquals(formatted, Soml.format(formatted));
			var document = SomlDocument.parse(source);
			document.set(List.of("added"), count);
			assertEquals(new SomlString(value), document.get(List.of("text")));
			assertTrue(document.toString().startsWith(source));
		}
	}

	@Test
	void formattingFindsOnlyCommasOutsideComments() {
		for (var gap : List.of(" ", "\n", "\n\n", "\n\t")) {
			var source = "[/* opening , ] */" + gap + "1 /* before , */," + gap + "/* after , */ 2," + gap + "/* closing , [ */]";
			var formatted = Soml.format(source);
			assertEquals(SomlArray.of(new SomlInt(1), new SomlInt(2)), Soml.parse(formatted));
			assertEquals(formatted, Soml.format(formatted));

			for (var comment : List.of("/* opening , ] */", "/* before , */", "/* after , */", "/* closing , [ */")) {
				assertEquals(1, formatted.split(java.util.regex.Pattern.quote(comment), -1).length - 1);
			}
		}
	}

	@Test
	void removalOwnsOnlyAdjacentCommentsAndKeepsUnrelatedTextExact() {
		var document = SomlDocument.parse("values: [1, /* before */ 2 /* after */, /* next */ 3] # outside\nuntouched: 0xCAFE\n");
		assertTrue(document.remove(List.of("values", 1)));
		assertEquals("values: [1, /* next */ 3] # outside\nuntouched: 0xCAFE\n", document.toString());
		assertTrue(document.remove(List.of("values", 1)));
		assertEquals("values: [1] # outside\nuntouched: 0xCAFE\n", document.toString());
		assertFalse(document.remove(List.of("values", 1)));
		assertEquals(Soml.parse(document.toString()), document.value());
	}

	@Test
	void insertionAndRemovalCrossManyCommentsWithoutTakingTheirPunctuation() {
		var source = "# keep\nvalues: [1," + " /* , ] } */".repeat(1000) + " 2]\nother: 0o644\n";
		var document = SomlDocument.parse(source);
		document.set(List.of("values", 2), List.of(3, 4));
		assertEquals(SomlArray.of(new SomlInt(1), new SomlInt(2), SomlArray.of(new SomlInt(3), new SomlInt(4))), document.get(List.of("values")));
		assertTrue(document.toString().startsWith("# keep\n"));
		assertTrue(document.toString().endsWith("\nother: 0o644\n"));
		assertTrue(document.remove(List.of("values", 1)));
		assertEquals(Soml.parse(document.toString()), document.value());
		assertEquals(SomlArray.of(new SomlInt(1), SomlArray.of(new SomlInt(3), new SomlInt(4))), document.get(List.of("values")));
	}

	@Test
	void queriesValidateTheWholePathAfterAMissingValue() {
		var document = SomlDocument.parse("a: [1]");
		assertEquals(document.value(), document.get(List.of()));
		assertNull(document.get(List.of("missing", 0, "deep")));
		assertNull(document.get(List.of("a", 0, "deep")));

		for (var invalid : List.of(List.of("missing", -1), List.of("missing", "bad\rkey"), List.of("a", 0, new Object()))) {
			assertThrows(IllegalArgumentException.class, () -> document.get(invalid));
		}
	}

	@Test
	void excessiveMissingPathsFailAtomicallyAndTheDocumentRemainsUsable() {
		var source = "original: 0xCAFE # keep\n";
		var document = SomlDocument.parse(source);
		var error = assertThrows(SomlException.class, () -> document.set(Collections.nCopies(100_000, "nested"), true));
		assertTrue(error.reason().contains("more than 100 levels deep"));
		assertEquals(source, document.toString());
		document.set(List.of("ok"), 1);
		assertEquals(new SomlInt(1), document.get(List.of("ok")));
		assertEquals(Soml.parse(document.toString()), document.value());
	}

	@Test
	void scalarEditsPreserveEveryCanonicalSpelling() {
		var values = List.<SomlValue>of(new SomlString("quotes ' and \" plus\t😀"), new SomlInt(Long.MIN_VALUE), new SomlFloat(Double.MIN_VALUE), new SomlBool(true), SomlNull.INSTANCE, new SomlInstant(Instant.parse("0001-01-01T00:00:00.000000001Z")), new SomlDuration(Duration.ofNanos(Long.MIN_VALUE)));

		for (var value : values) {
			var document = SomlDocument.parse("value: 0\nuntouched: 0xCAFE\n");
			document.set(List.of("value"), value);
			assertEquals("value: " + value + "\nuntouched: 0xCAFE\n", document.toString());
			assertEquals(value, document.get(List.of("value")));
		}
	}

	@Test
	void maximumMissingObjectPathAcceptsAScalarAndRejectsAnotherContainer() {
		var path = new ArrayList<Object>(Collections.nCopies(Writer.MAX_DEPTH, "child"));
		var document = SomlDocument.parse("{}");
		document.set(path, 1);
		assertEquals(new SomlInt(1), document.get(path));
		var before = document.toString();
		assertThrows(SomlException.class, () -> document.set(path, List.of(1)));
		assertEquals(before, document.toString());
	}

	@Test
	void emptyContainersKeepTheirDepthChecksAcrossWritingRoutes() {
		for (var value : List.<SomlValue>of(new SomlObject(java.util.Map.of()), SomlArray.of())) {
			assertEquals(value + "\n", Soml.encode(value));
			assertEquals(value.toString(), Writer.writeValueAt(value, Writer.MAX_DEPTH, "\t", true));
			assertThrows(Binding.BindingError.class, () -> Writer.writeValueAt(value, Writer.MAX_DEPTH + 1, "", false));
			var output = new Writer.TextBuffer(2);
			assertThrows(Binding.BindingError.class, () -> Writer.writeValueInto(output, value, Writer.MAX_DEPTH + 1, 0, false));
			assertEquals("", output.toString());
			Writer.writeValueInto(output, value, Writer.MAX_DEPTH, 0, true);
			assertEquals(value.toString(), output.toString());
		}
	}
}
