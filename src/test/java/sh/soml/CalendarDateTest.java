package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CalendarDateTest {
	record Schedule(LocalDate date, String label, Instant instant, List<LocalDate> dates, Optional<LocalDate> optional) {}

	@Test
	void datesPreserveTheirComponentsAndCanonicalSpelling() {
		for (var text : List.of("0001-01-01", "9999-12-31", "1582-10-10", "2011-12-30", "2000-02-29", "2026-11-01")) {
			var date = LocalDate.parse(text);
			var value = new SomlDate(date);
			assertEquals(date, value.value());
			assertEquals(text, value.toString());
			assertEquals(SomlArray.of(value), Soml.parse("[" + text + "]"));
			assertEquals(SomlArray.of(value), Soml.parse(("[" + text + "]").getBytes(StandardCharsets.UTF_8)));
			assertEquals("[\n\t" + text + "\n]\n", Soml.encodeCanonical(List.of(date)));
			assertNotEquals(new SomlString(text), value);
			assertNotEquals(new SomlInstant(Instant.parse(text + "T00:00:00Z")), value);
		}
	}

	@Test
	void nativeValuesRejectUnsupportedYearsAndNull() {
		for (var year : List.of(-1, 0, 10_000)) {
			var date = LocalDate.of(year, 1, 1);
			assertThrows(IllegalArgumentException.class, () -> new SomlDate(date));
			assertThrows(SomlException.class, () -> Soml.toValue(date));
			var error = assertThrows(SomlException.class, () -> Soml.encode(Map.of("date", date)));
			assertEquals(List.of("date"), error.path());
		}

		assertThrows(NullPointerException.class, () -> new SomlDate(null));
	}

	@Test
	void strictGrammarRejectsInvalidCalendarDatesAndLongerTokens() {
		for (var text : List.of("1900-02-29", "2026-02-30", "2026-04-31", "0000-01-01", "2026-00-01", "2026-13-01", "2026-01-00", "2026-01-32", "2026-1-01", "10000-01-01", "2026-11-01T", "2026-11-01Z", "2026-11-01foo", "2026-11-01T12:00:00", "２０２６-１１-０１")) {
			var source = "date: " + text;
			assertThrows(SomlException.class, () -> Soml.parse(source), text);
			assertThrows(SomlException.class, () -> Soml.decode(source, Object.class), text);
			assertThrows(SomlException.class, () -> Soml.format(source), text);
		}

		var error = assertThrows(SomlException.class, () -> Soml.parse("date: 2026-02-30"));
		assertEquals("Invalid date 2026-02-30: the day must be 01 to 28 in that month", error.reason());
		assertEquals(1, error.line());
		assertEquals(7, error.column());
		assertThrows(SomlException.class, () -> Soml.parse("2026-11-01"));
	}

	@Test
	void boundariesCommentsAndAdjacentLinesDoNotChangeTheType() {
		var date = new SomlDate(LocalDate.of(2026, 11, 1));
		for (var source : List.of("date: 2026-11-01", "date: 2026-11-01\nnext: 1", "date: 2026-11-01 # comment\nnext: 1", "date: 2026-11-01/* comment */", "{date: 2026-11-01, next: 1}", "{date: 2026-11-01}")) {
			assertEquals(date, ((SomlObject) Soml.parse(source)).get("date"));
			assertEquals(Soml.parse(source), Soml.parse(Soml.format(source)));
		}

		assertEquals(SomlArray.of(date, date), Soml.parse("[2026-11-01, 2026-11-01]"));
		assertEquals(SomlArray.of(date, date), Soml.parse("[2026-11-01\n2026-11-01]"));
		assertEquals(new SomlString("2026-11-01"), ((SomlObject) Soml.parse("date: '2026-11-01'")).get("date"));
	}

	@Test
	void aDateFollowedByATimeOfDayGetsTheInstantSeparatorDiagnostic() {
		for (var separator : List.of(" ", "\t", "   ")) {
			var error = assertThrows(SomlException.class, () -> Soml.parse("date: 2026-11-01" + separator + "12:00:00Z"));
			assertEquals("The date and time separator in an instant is an uppercase “T”, not a space, as in 2026-11-01T12:00:00Z", error.reason());
			assertEquals(7, error.column());
		}

		var oneDigitHour = assertThrows(SomlException.class, () -> Soml.parse("date: 2026-11-01 9:00:00Z"));
		assertEquals("The date and time separator in an instant is an uppercase “T”, not a space. An instant is written as 2026-09-19T14:00:00Z, with an optional fraction of up to nine digits and an offset of Z or ±HH:MM", oneDigitHour.reason());
		var localTime = assertThrows(SomlException.class, () -> Soml.parse("date: 2026-11-01 12:00:00"));
		assertEquals("The date and time separator in an instant is an uppercase “T”, not a space, and an instant needs the offset it was meant in, as in 2026-11-01T12:00:00Z for UTC. A date and time without an offset is a local time, which is not an instant, so write it as a string, as in '2026-11-01 12:00:00'", localTime.reason());
	}

	@Test
	void typedAndUntypedBindingPreserveDatesStringsAndInstants() {
		var date = LocalDate.of(1582, 10, 10);
		var expected = new Schedule(date, "1582-10-10", Instant.parse("2026-11-01T12:00:00Z"), List.of(LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31)), Optional.of(date));
		var source = "date: 1582-10-10\nlabel: '1582-10-10'\ninstant: 2026-11-01T12:00:00Z\ndates: [0001-01-01, 9999-12-31]\noptional: 1582-10-10";
		var tree = Soml.parse(source);
		assertEquals(expected, Soml.decode(source, Schedule.class));
		assertEquals(expected, Soml.fromValue(tree, Schedule.class));
		assertEquals(tree, Soml.toValue(expected));
		assertEquals(tree, Soml.parse(Soml.encode(expected)));
		assertEquals(Soml.encodeCanonical(tree), Soml.encodeCanonical(expected));
		var plain = assertInstanceOf(Map.class, Soml.decode(source, Object.class));
		assertEquals(date, plain.get("date"));
		assertEquals("1582-10-10", plain.get("label"));
		assertInstanceOf(Instant.class, plain.get("instant"));
		assertEquals(List.of(date), Soml.decode("[1582-10-10]", new TypeReference<List<LocalDate>>() {}));
		assertEquals(date, Soml.decode("[1582-10-10]", LocalDate[].class)[0]);
		assertEquals(new SomlDate(date), Soml.fromValue(new SomlDate(date), SomlDate.class));
	}

	@Test
	void typedDecodingDoesNotCoerceDatesStringsOrInstants() {
		for (var source : List.of("['2026-11-01']", "[2026-11-01T00:00:00Z]", "[1]")) {
			assertThrows(SomlException.class, () -> Soml.decode(source, LocalDate[].class));
			assertThrows(SomlException.class, () -> Soml.fromValue(Soml.parse(source), LocalDate[].class));
		}

		assertThrows(SomlException.class, () -> Soml.decode("[2026-11-01]", String[].class));
		assertThrows(SomlException.class, () -> Soml.decode("[2026-11-01]", Instant[].class));
		assertThrows(SomlException.class, () -> Soml.decode("[2026-11-01]", int[].class));
	}

	@Test
	void formattingAndEditingPreserveTheDateTypeAndUnchangedText() {
		var source = "# launch\ndate: 2026-11-01 # retained\nlabel: '2026-11-01'\ndates: [1582-10-10, 2011-12-30]\n";
		var document = SomlDocument.parse(source);
		assertEquals(source, document.toString());
		document.set(List.of("date"), LocalDate.of(2000, 2, 29));
		document.set(List.of("dates", 0), LocalDate.of(1, 1, 1));
		document.set(List.of("dates", 2), LocalDate.of(9999, 12, 31));
		assertEquals("# launch\ndate: 2000-02-29 # retained\nlabel: '2026-11-01'\ndates: [0001-01-01, 2011-12-30, 9999-12-31]\n", document.toString());
		assertInstanceOf(SomlDate.class, document.get(List.of("date")));
		assertInstanceOf(SomlString.class, document.get(List.of("label")));
		assertEquals(document.value(), Soml.parse(document.toString()));
		var before = document.toString();
		var beforeValue = document.value();
		assertThrows(SomlException.class, () -> document.set(List.of("date"), LocalDate.of(0, 1, 1)));
		assertEquals(before, document.toString());
		assertEquals(beforeValue, document.value());
		assertTrue(document.remove(List.of("date")));
		assertNull(document.get(List.of("date")));
		assertEquals("# launch\nlabel: '2026-11-01'\ndates: [0001-01-01, 2011-12-30, 9999-12-31]\n", document.toString());
		assertEquals(document.value(), Soml.parse(document.toString()));
		var text = document.toString();
		var value = document.value();
		assertThrows(SomlException.class, () -> document.set(List.of("dates", 5), LocalDate.of(2026, 1, 1)));
		assertEquals(text, document.toString());
		assertEquals(value, document.value());
	}

	@Test
	void optionalDatesAreLeftOutWhenEmptyAndNeverAcceptStrings() {
		record Launch(LocalDate date, Optional<LocalDate> optional) {}

		var date = LocalDate.of(2026, 11, 1);
		assertEquals(new Launch(date, Optional.empty()), Soml.decode("date: 2026-11-01", Launch.class));
		assertEquals("date: 2026-11-01\n", Soml.encode(new Launch(date, Optional.empty())));
		assertEquals(new Launch(date, Optional.of(LocalDate.of(2026, 11, 2))), Soml.decode("date: 2026-11-01\noptional: 2026-11-02", Launch.class));
		var error = assertThrows(SomlException.class, () -> Soml.decode("date: '2026-11-01'", Launch.class));
		assertEquals("Expected a date, but found the string '2026-11-01'", error.reason());
		assertEquals(List.of("date"), error.path());
	}

	@Test
	void everyDayInAGregorianCycleAgreesWithTheNativeCalendar() {
		for (var year = 1; year <= 400; year++) {
			for (var month = 1; month <= 12; month++) {
				var first = LocalDate.of(year, month, 1);

				for (var day = 1; day <= 31; day++) {
					var text = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);

					if (day <= first.lengthOfMonth()) {
						assertEquals(SomlArray.of(new SomlDate(first.withDayOfMonth(day))), Soml.parse("[" + text + "]"));
					} else {
						assertThrows(SomlException.class, () -> Soml.parse("[" + text + "]"));
					}
				}
			}
		}
	}

	@Test
	void explicitAdaptersOverrideDatesWithoutChangingOtherMappers() {
		var date = LocalDate.of(2027, 9, 19);
		var mapper = SomlMapper.builder().adapter(LocalDate.class, SomlAdapter.of(
			value -> new SomlString(value.toString()),
			value -> LocalDate.parse(((SomlString) value).value())
		)).build();
		assertEquals(new SomlString("2027-09-19"), mapper.toValue(date));
		assertEquals("[\n\t'2027-09-19'\n]\n", mapper.encode(List.of(date)));
		assertEquals(List.of(date), mapper.decode("['2027-09-19']", new TypeReference<List<LocalDate>>() {}));
		assertEquals(new SomlDate(date), Soml.toValue(date));
		var unrelated = SomlMapper.builder().adapter(String.class, SomlAdapter.of(SomlString::new, value -> ((SomlString) value).value())).build();
		assertEquals(SomlArray.of(new SomlDate(date)), Soml.parse(unrelated.encode(List.of(date))));
	}

	@Test
	void malformedDateDiagnosticsAreBoundedAndPrecedeBindingErrors() {
		for (var suffix : List.of("T".repeat(100_000), "_".repeat(100_000), "1".repeat(100_000))) {
			var source = "date: 2026-11-01" + suffix;
			var error = assertThrows(SomlException.class, () -> Soml.parse(source));
			assertTrue(error.reason().length() < 400);
			assertEquals(error.reason(), assertThrows(SomlException.class, () -> Soml.decode(source, Object.class)).reason());
		}

		var error = assertThrows(SomlException.class, () -> Soml.decode("['wrong', 2026-02-30]", LocalDate[].class));
		assertEquals("Invalid date 2026-02-30: the day must be 01 to 28 in that month", error.reason());
		assertEquals(List.of(), error.path());
		var mismatch = assertThrows(SomlException.class, () -> Soml.decode("[2026-11-01]", String[].class));
		assertEquals("Expected a string, but found the date 2026-11-01", mismatch.reason());
		assertEquals(List.of(0), mismatch.path());
		assertEquals(2, mismatch.column());
	}
}
