package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ValueRoundTwoTest {
	@Test
	void diagnosticWindowsIgnoreDistantSuffixesAtEveryClippingBoundary() {
		for (var offset : new int[] {0, 1, 49, 50, 51, 99, 100, 101, 150, 199}) {
			var shortSource = "x".repeat(400);
			var longSource = shortSource + "\u001B\u202E😀".repeat(100_000);
			var expected = SomlException.at("bad", shortSource, offset);
			var actual = SomlException.at("bad", longSource, offset);
			assertEquals(expected.getMessage(), actual.getMessage());
			assertEquals(expected.column(), actual.column());
			assertTrue(actual.codeFrame().length() < 180);
		}
	}

	@Test
	void boundedLineSearchDistinguishesAnActualLineEndingFromAWindowEnding() {
		for (var length : new int[] {0, 1, 99, 100, 101, 149, 150, 151, 200}) {
			var line = "x".repeat(length);
			var expected = SomlException.at("bad", line, length);
			var actual = SomlException.at("bad", line + "\n" + "z".repeat(10_000), length);
			assertEquals(expected.getMessage(), actual.getMessage());
			assertEquals(length + 1, actual.column());
		}
	}

	@Test
	void errorAtEndOfDocumentKeepsLongContextLinesAndEmptyLines() {
		var source = "x".repeat(10_000) + "\n\n\t😀\u001B";
		var error = SomlException.at("bad", source, source.length());
		assertEquals(3, error.line());
		assertEquals(4, error.column());
		assertEquals("  1 | " + "x".repeat(100) + "…\n  2 |\n> 3 | \t😀�\n    | \t  ^", error.codeFrame());
	}

	@Test
	void scientificFloatLayoutKeepsCanonicalThresholdsAndSubnormalDigits() {
		var examples = Map.ofEntries(
			Map.entry(Double.MIN_VALUE, "5e-324"),
			Map.entry(1e-7, "1e-7"),
			Map.entry(1e-6, "0.000001"),
			Map.entry(1.2345e-5, "0.000012345"),
			Map.entry(1e7, "10000000.0"),
			Map.entry(1.2345e7, "12345000.0"),
			Map.entry(1.23456789e7, "12345678.9"),
			Map.entry(1e20, "100000000000000000000.0"),
			Map.entry(1e21, "1e21"),
			Map.entry(Double.MAX_VALUE, "1.7976931348623157e308")
		);

		for (var example : examples.entrySet()) {
			assertEquals(example.getValue(), new SomlFloat(example.getKey()).toString());
			assertEquals("-" + example.getValue(), new SomlFloat(-example.getKey()).toString());
		}
	}

	@Test
	void randomizedFloatDigitCopiesRoundTripToTheExactBinaryValue() {
		var random = new Random(397127);

		for (var index = 0; index < 10_000; index++) {
			var value = Double.longBitsToDouble(random.nextLong());

			if (!Double.isFinite(value)) {
				continue;
			}

			var formatted = new SomlFloat(value).toString();
			assertEquals(Double.doubleToLongBits(value == 0 ? 0 : value), Double.doubleToLongBits(Double.parseDouble(formatted)), formatted);
			assertEquals("[" + formatted + "]\n", Soml.encode(List.of(value)).replace("\n\t", "").replace("\n]", "]"));
		}
	}

	@Test
	void shortestSubnormalDigitsMatchAnIndependentExactDistanceSearch() {
		var shortened = 0;

		for (var bits = 1L; bits <= 1_000; bits++) {
			var value = Double.longBitsToDouble(bits);
			var exact = new BigDecimal(value);
			String closest = null;
			BigDecimal distance = null;
			var closestDigit = 0;

			for (var exponent = -324; exponent <= -320; exponent++) {
				for (var digit = 1; digit <= 9; digit++) {
					var candidate = digit + "e" + exponent;

					if (Double.parseDouble(candidate) != value) {
						continue;
					}

					var candidateDistance = exact.subtract(new BigDecimal(candidate)).abs();
					var comparison = distance == null ? -1 : candidateDistance.compareTo(distance);

					if (comparison < 0 || (comparison == 0 && digit % 2 == 0 && closestDigit % 2 != 0)) {
						closest = candidate;
						distance = candidateDistance;
						closestDigit = digit;
					}
				}
			}

			if (closest != null) {
				assertEquals(closest, new SomlFloat(value).toString());
				shortened++;
			}
		}

		assertTrue(shortened > 10);
	}

	@Test
	void dateArithmeticMatchesTheJdkAcrossLeapYearsAndTheWholeSupportedRange() {
		for (var year = 1; year <= 9999; year++) {
			var date = LocalDate.of(year, 2, 28).plusDays(1);
			var instant = date.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
			assertEquals(instant.toString(), new SomlInstant(instant).toString());
		}

		for (var instant : List.of(SomlInstant.MIN, SomlInstant.MIN.plusNanos(1), SomlInstant.MAX.minusNanos(1), SomlInstant.MAX)) {
			assertEquals(instant, Instant.parse(new SomlInstant(instant).toString()));
		}

		assertThrows(IllegalArgumentException.class, () -> new SomlInstant(SomlInstant.MIN.minusNanos(1)));
		assertThrows(IllegalArgumentException.class, () -> new SomlInstant(SomlInstant.MAX.plusNanos(1)));
	}

	@Test
	void fractionalFormattingAgreesWithAnIndependentDecimalOracle() {
		var random = new Random(913827);

		for (var index = 0; index < 2_000; index++) {
			var nanoseconds = index < 10 ? (int) Math.pow(10, index % 9) : random.nextInt(1_000_000_000);
			var decimal = BigDecimal.ONE.add(BigDecimal.valueOf(nanoseconds, 9)).stripTrailingZeros().toPlainString();
			var duration = new SomlDuration(Duration.ofSeconds(1, nanoseconds));
			assertEquals(decimal + "s", duration.toString());
			var fraction = nanoseconds == 0 ? "" : decimal.substring(1);
			assertEquals("2000-01-01T00:00:01" + fraction + "Z", new SomlInstant(Instant.parse("2000-01-01T00:00:01Z").plusNanos(nanoseconds)).toString());
		}
	}

	@Test
	void emptyObjectStorageAndZeroDurationFactoriesCanBeSharedSafely() {
		var source = new LinkedHashMap<String, SomlValue>();
		var object = new SomlObject(source);
		source.put("added", new SomlInt(1));
		var nonempty = new SomlObject(source);
		source.clear();
		assertTrue(object.members().isEmpty());
		assertEquals(new SomlInt(1), nonempty.get("added"));
		assertTrue(new SomlObject(source).members().isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> object.members().put("x", SomlNull.INSTANCE));
		var zero = SomlDuration.ofNanos(0);
		assertEquals(Duration.ZERO, zero.value());
		assertEquals(SomlArray.of(zero, zero, zero), Soml.parse("[0ns, 0h, 0.000s]"));
		assertEquals("0s", zero.toString());
	}

	@Test
	void removingTheOnlyMemberProducesAnEmptyObjectAndRejectsInvalidIndexes() {
		var object = new SomlObject(Map.of("tag", new SomlString("empty")));
		assertEquals(new SomlObject(Map.of()), object.withoutMember(0));
		assertThrows(IndexOutOfBoundsException.class, () -> object.withoutMember(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> object.withoutMember(1));
	}
}
