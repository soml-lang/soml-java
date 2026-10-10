package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/*
The float vectors of the C implementation, which must be checked out next to this repository as `../soml-c`. They are too large to copy here: 12,000 short and 7,996 long literals with their correctly rounded values, and 33,293 doubles with their shortest digits by canonical rule 11.
*/
class FloatVectorsTest {
	private static final Path FIXTURES = Path.of("../soml-c/test/fixtures");

	@ParameterizedTest
	@ValueSource(strings = {"float-parse", "float-parse-long"})
	void parsesCorrectlyRounded(String name) throws IOException {
		var casesPath = FIXTURES.resolve(name + "-cases.txt");
		assumeTrue(Files.exists(casesPath), "The soml-c repository is not next to this one");
		var cases = Files.readAllLines(casesPath);
		var expected = Files.readAllLines(FIXTURES.resolve(name + "-expected.txt"));
		var failures = new ArrayList<String>();

		for (var index = 0; index < cases.size(); index++) {
			// The expected bits are little-endian bytes in hexadecimal.
			var bits = Long.reverseBytes(Long.parseUnsignedLong(expected.get(index), 16));
			var literal = somlLiteral(cases.get(index));
			var magnitude = bits & Long.MAX_VALUE;
			SomlValue value;

			try {
				value = ((SomlObject) Soml.parse("a: " + literal)).get("a");
			} catch (SomlException _) {
				value = null;
			}

			boolean isCorrect;

			if (magnitude == 0x7FF0000000000000L) {
				// Too large to be finite is an error.
				isCorrect = value == null;
			} else if (magnitude == 0) {
				// A nonzero literal that rounds to zero is an error.
				isCorrect = value == null || literal.equals("0.0");
			} else {
				isCorrect = value instanceof SomlFloat(var number) && Double.doubleToRawLongBits(number) == bits;
			}

			if (!isCorrect && failures.size() < 5) {
				failures.add(literal.substring(0, Math.min(60, literal.length())) + " read as " + value + ", expected " + Double.longBitsToDouble(bits));
			}
		}

		assertTrue(cases.size() > 7000);
		assertEquals(new ArrayList<String>(), failures);
	}

	@Test
	void writesShortestDigits() throws IOException {
		var bitsPath = FIXTURES.resolve("float-shortest-bits.txt");
		assumeTrue(Files.exists(bitsPath), "The soml-c repository is not next to this one");
		var bitsLines = Files.readAllLines(bitsPath);
		var expected = Files.readAllLines(FIXTURES.resolve("float-shortest-expected.txt"));
		var failures = new ArrayList<String>();

		for (var index = 0; index < bitsLines.size(); index++) {
			var value = Double.longBitsToDouble(Long.parseUnsignedLong(bitsLines.get(index), 16));
			var text = new SomlFloat(value).toString();
			var isCorrect = shortestDigits(text).equals(expected.get(index)) && ((SomlObject) Soml.parse("a: " + text)).get("a").equals(new SomlFloat(value));

			if (!isCorrect && failures.size() < 5) {
				failures.add(bitsLines.get(index) + " written as " + text + ", expected " + expected.get(index));
			}
		}

		assertTrue(bitsLines.size() > 30_000);
		assertEquals(new ArrayList<String>(), failures);
	}

	/*
	A decimal literal in any spelling as a SOML float literal, `0.<digits>e<exponent>`.
	*/
	private static String somlLiteral(String text) {
		var index = 0;
		var isNegative = false;

		if (text.charAt(0) == '-' || text.charAt(0) == '+') {
			isNegative = text.charAt(0) == '-';
			index++;
		}

		var digits = new StringBuilder();
		var point = 0L;
		var hasSeenPoint = false;

		for (; index < text.length() && text.charAt(index) != 'e' && text.charAt(index) != 'E'; index++) {
			var character = text.charAt(index);

			if (character == '.') {
				hasSeenPoint = true;
				continue;
			}

			if (digits.isEmpty() && character == '0') {
				if (hasSeenPoint) {
					point--;
				}

				continue;
			}

			if (!hasSeenPoint) {
				point++;
			}

			digits.append(character);
		}

		if (digits.isEmpty()) {
			return "0.0";
		}

		var exponent = index < text.length() ? Long.parseLong(text.substring(index + 1).replace("+", "")) : 0;
		var total = point + exponent;
		return (isNegative ? "-" : "") + "0." + digits + "e" + total;
	}

	/*
	The digits and the exponent of a written float, as `<digits> <exponent>` for digits × 10^exponent, without leading or trailing zeros.
	*/
	private static String shortestDigits(String text) {
		var body = text.startsWith("-") ? text.substring(1) : text;
		var exponentIndex = body.indexOf('e');
		var mantissa = exponentIndex == -1 ? body : body.substring(0, exponentIndex);
		var power = exponentIndex == -1 ? 0 : Long.parseLong(body.substring(exponentIndex + 1));
		var digits = new StringBuilder();
		var fractionDigits = 0L;
		var isAfterPoint = false;

		for (var character : mantissa.toCharArray()) {
			if (character == '.') {
				isAfterPoint = true;
				continue;
			}

			if (digits.isEmpty() && character == '0') {
				if (isAfterPoint) {
					fractionDigits++;
				}

				continue;
			}

			digits.append(character);

			if (isAfterPoint) {
				fractionDigits++;
			}
		}

		while (!digits.isEmpty() && digits.charAt(digits.length() - 1) == '0') {
			digits.setLength(digits.length() - 1);
			fractionDigits--;
		}

		return digits + " " + (power - fractionDigits);
	}
}
