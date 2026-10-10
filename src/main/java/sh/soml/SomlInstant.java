package sh.soml;

import java.time.Instant;
import java.util.Objects;

/**
An instant: a point in time with nanosecond precision, from {@code 0001-01-01T00:00:00Z} to {@code 9999-12-31T23:59:59.999999999Z}.

<p>The offset a document writes an instant with is not part of its value, so {@code 2026-09-19T16:00:00+02:00} and {@code 2026-09-19T14:00:00Z} are the same instant.

@param value The instant.
*/
public record SomlInstant(Instant value) implements SomlValue {
	/**
	The earliest instant: {@code 0001-01-01T00:00:00Z}.
	*/
	public static final Instant MIN = Instant.ofEpochSecond(-62_135_596_800L);

	/**
	The latest instant: {@code 9999-12-31T23:59:59.999999999Z}.
	*/
	public static final Instant MAX = Instant.ofEpochSecond(253_402_300_799L, 999_999_999);

	/**
	Creates an instant.

	@throws IllegalArgumentException When {@code value} is outside the years 0001 to 9999 in UTC.
	*/
	public SomlInstant {
		checkValue(value);
	}

	static void checkValue(Instant value) {
		Objects.requireNonNull(value, "value");

		var seconds = value.getEpochSecond();

		if (seconds < MIN.getEpochSecond() || seconds > MAX.getEpochSecond()) {
			throw new IllegalArgumentException("The instant " + value + " is outside the years 0001 to 9999");
		}
	}

	/**
	The instant as canonical form writes it: in UTC, with {@code Z}, and with a fraction of a second only when it is not zero, without trailing zeros, as in {@code 2026-09-19T14:00:00Z} and {@code 2026-09-19T14:00:00.5Z}.
	*/
	@Override
	public String toString() {
		var text = new Writer.TextBuffer(32);
		appendTo(text, value);
		return text.toString();
	}

	/*
	The date comes from Howard Hinnant's `civil_from_days`, which is exact for every day of the proleptic Gregorian calendar.
	*/
	static void appendTo(Writer.TextBuffer text, Instant value) {
		// Note: Not using `DateTimeFormatterBuilder.appendInstant(-1)` because the matching formatter benchmarked slower and allocated more than direct output.
		var seconds = value.getEpochSecond();
		var days = Math.floorDiv(seconds, 86_400);
		var secondOfDay = Math.floorMod(seconds, 86_400);
		// The days from 0000-03-01, so that a year starts in March and a leap day is the last day of its year.
		var shiftedDays = (int) (days + 719_468);
		// Every supported year follows 0000-03-01, so this range is positive and fits in an int.
		var era = shiftedDays / 146_097;
		var dayOfEra = shiftedDays - (era * 146_097);
		var yearOfEra = (dayOfEra - (dayOfEra / 1460) + (dayOfEra / 36_524) - (dayOfEra / 146_096)) / 365;
		var dayOfYear = dayOfEra - ((365 * yearOfEra) + (yearOfEra / 4) - (yearOfEra / 100));
		var monthIndex = ((5 * dayOfYear) + 2) / 153;
		var day = dayOfYear - (((153 * monthIndex) + 2) / 5) + 1;
		var month = monthIndex < 10 ? monthIndex + 3 : monthIndex - 9;
		var year = yearOfEra + (era * 400) + (month <= 2 ? 1 : 0);
		text.appendPadded(year, 4);
		text.append('-');
		text.appendPadded(month, 2);
		text.append('-');
		text.appendPadded(day, 2);
		text.append('T');
		text.appendPadded(secondOfDay / 3600, 2);
		text.append(':');
		text.appendPadded((secondOfDay / 60) % 60, 2);
		text.append(':');
		text.appendPadded(secondOfDay % 60, 2);
		appendFraction(text, value.getNano());
		text.append('Z');
	}

	/*
	The fraction of a second as canonical form writes it for an instant and a duration: nothing when it is zero, and otherwise a `.` and nine digits without the trailing zeros.
	*/
	static void appendFraction(Writer.TextBuffer text, int nanoseconds) {
		if (nanoseconds == 0) {
			return;
		}

		var width = 9;

		while (nanoseconds % 10 == 0) {
			nanoseconds /= 10;
			width--;
		}

		text.append('.').appendPadded(nanoseconds, width);
	}
}
