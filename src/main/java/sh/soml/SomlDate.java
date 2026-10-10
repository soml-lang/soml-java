package sh.soml;

import java.time.LocalDate;
import java.time.Year;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
A calendar date in the proleptic Gregorian calendar, from {@code 0001-01-01} to {@code 9999-12-31}.

<p>A date has no time of day, timezone, or duration. Its year, month, and day are preserved exactly, without conversion through an instant.

@param value The calendar date.
*/
public record SomlDate(LocalDate value) implements SomlValue {
	/**
	Creates a date.

	@throws IllegalArgumentException When {@code value} is outside the years 0001 to 9999.
	*/
	public SomlDate {
		checkValue(value);
	}

	static void checkValue(LocalDate value) {
		Objects.requireNonNull(value, "value");

		var year = value.getYear();

		if (year < 1 || year > 9999) {
			throw new IllegalArgumentException("The date " + value + " is outside the years 0001 to 9999");
		}
	}

	/*
	The reason the first invalid component is invalid, shared by dates and the calendar part of instants. A date alone is a calendar value, not an instant, and is accepted only when its components exist. The lexical grammar has already checked the component widths and ASCII digits.
	*/
	static @Nullable String calendarError(int year, int month, int day) {
		if (year < 1 || year > 9999) {
			return "the year must be 0001 to 9999";
		}

		if (month < 1 || month > 12) {
			return "the month must be 01 to 12";
		}

		var lastDay = daysInMonth(year, month);
		return day < 1 || day > lastDay ? "the day must be 01 to " + lastDay + " in that month" : null;
	}

	/*
	The length of a month in the proleptic Gregorian calendar, for a month from 1 to 12.
	*/
	private static int daysInMonth(int year, int month) {
		if (month == 2) {
			return Year.isLeap(year) ? 29 : 28;
		}

		return month == 4 || month == 6 || month == 9 || month == 11 ? 30 : 31;
	}

	/**
	The date's canonical ten-character spelling: {@code YYYY-MM-DD}.
	*/
	@Override
	public String toString() {
		return value.toString();
	}
}
