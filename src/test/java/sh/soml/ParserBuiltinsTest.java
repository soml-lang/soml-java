package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParserBuiltinsTest {
	@Test
	void parsingFebruaryTransitionsAgreesWithTheJdkForEverySupportedYear() {
		for (var year = 1; year <= 9999; year++) {
			var date = LocalDate.of(year, 2, 28).plusDays(1);
			var expected = new SomlInstant(date.atStartOfDay().toInstant(ZoneOffset.UTC));
			assertEquals(SomlArray.of(expected), Soml.parse("[" + date + "T00:00:00Z]"));
		}

		for (var year : List.of("0001", "0100", "1900", "2023", "9999")) {
			var source = "[" + year + "-02-29T00:00:00Z]";
			assertTrue(assertThrows(SomlException.class, () -> Soml.parse(source)).reason().contains("the day must be 01 to 28"));
		}
	}

	@Test
	void jsonSurrogateHintsComposeTheWholeSupplementaryRange() {
		for (var codePoint : new int[] {0x10000, 0x10400, 0x1f600, 0x20000, 0x10ffff}) {
			var pair = Character.toChars(codePoint);
			var hex = HexFormat.of().withUpperCase();
			var source = "[\"\\u" + hex.toHexDigits(pair[0]) + "\\u" + hex.toHexDigits(pair[1]) + "\"]";
			var expected = "Write \\u{" + Integer.toHexString(codePoint) + "}";
			assertTrue(assertThrows(SomlException.class, () -> Soml.parse(source)).reason().endsWith(expected));
		}
	}

	@Test
	void invalidRadixDigitsTakePriorityOverValidLowercaseHexadecimalDigits() {
		for (var digit : "0123456789ABCDEFabcdef".toCharArray()) {
			assertEquals("Invalid hexadecimal digit “z”", assertThrows(SomlException.class, () -> Soml.parse("[0x" + digit + "z]")).reason());
		}
	}

	@Test
	void unicodeSeparatorsKeepTheirNamesAndNeverBecomeSomlWhitespace() {
		for (var codePoint : new int[] {0xa0, 0x1680, 0x2000, 0x2007, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000}) {
			var source = "[1" + Character.toString(codePoint) + "]";
			var reason = assertThrows(SomlException.class, () -> Soml.parse(source)).reason();
			assertTrue(reason.contains(Character.getName(codePoint)));
			assertTrue(reason.contains("only space, tab, and line feed are whitespace"));
		}

		assertTrue(assertThrows(SomlException.class, () -> Soml.parse("[é]")).reason().contains("“é”"));
	}
}
