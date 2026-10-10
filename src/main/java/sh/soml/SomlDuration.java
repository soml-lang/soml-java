package sh.soml;

import java.time.Duration;
import java.util.Objects;

/**
A duration: a signed length of time in whole nanoseconds, in the 64-bit range, which is about 292 years either way.

@param value The duration.
*/
public record SomlDuration(Duration value) implements SomlValue {
	/**
	The shortest duration: {@code -9223372036854775808ns}.
	*/
	public static final Duration MIN = Duration.ofNanos(Long.MIN_VALUE);

	/**
	The longest duration: {@code 9223372036854775807ns}.
	*/
	public static final Duration MAX = Duration.ofNanos(Long.MAX_VALUE);

	private static final SomlDuration ZERO = new SomlDuration(Duration.ZERO);

	private static final long NANOSECONDS_PER_HOUR = 3_600_000_000_000L;
	private static final long NANOSECONDS_PER_MINUTE = 60_000_000_000L;
	private static final long NANOSECONDS_PER_SECOND = 1_000_000_000L;

	/**
	Creates a duration.

	@throws IllegalArgumentException When {@code value} is outside the 64-bit range of nanoseconds.
	*/
	public SomlDuration {
		checkValue(value);
	}

	static void checkValue(Duration value) {
		Objects.requireNonNull(value, "value");

		if (value.compareTo(MIN) < 0 || value.compareTo(MAX) > 0) {
			throw new IllegalArgumentException("The duration " + value + " is outside the 64-bit range of nanoseconds");
		}
	}

	/**
	Creates a duration of {@code nanoseconds}.
	*/
	public static SomlDuration ofNanos(long nanoseconds) {
		return nanoseconds == 0 ? ZERO : new SomlDuration(Duration.ofNanos(nanoseconds));
	}

	/**
	The length in nanoseconds.
	*/
	public long nanoseconds() {
		return value.toNanos();
	}

	/**
	The duration as canonical form writes it: hours, minutes, and seconds with a fraction, each left out when it is zero, as in {@code 1h30m}, {@code 1.5s}, and {@code 0s}.
	*/
	@Override
	public String toString() {
		var text = new Writer.TextBuffer(32);
		appendTo(text, value);
		return text.toString();
	}

	static void appendTo(Writer.TextBuffer text, Duration value) {
		// Note: Not using `Duration.abs()` with part accessors because negative durations benchmarked slower and allocated more.
		var nanoseconds = value.toNanos();

		if (nanoseconds == 0) {
			text.append("0s");
			return;
		}

		// Work in the negative range, which includes the magnitude of the most negative duration too.
		var negative = nanoseconds > 0 ? -nanoseconds : nanoseconds;
		var hours = -(negative / NANOSECONDS_PER_HOUR);
		var remainder = -(negative % NANOSECONDS_PER_HOUR);
		var minutes = remainder / NANOSECONDS_PER_MINUTE;
		remainder %= NANOSECONDS_PER_MINUTE;
		var seconds = remainder / NANOSECONDS_PER_SECOND;
		var fraction = (int) (remainder % NANOSECONDS_PER_SECOND);

		if (nanoseconds < 0) {
			text.append('-');
		}

		if (hours > 0) {
			text.append(hours).append('h');
		}

		if (minutes > 0) {
			text.append(minutes).append('m');
		}

		if (seconds > 0 || fraction > 0) {
			text.append(seconds);
			SomlInstant.appendFraction(text, fraction);
			text.append('s');
		}
	}
}
