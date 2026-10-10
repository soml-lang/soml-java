package sh.soml;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
A float: an IEEE 754 binary64 value, finite or infinite. A float is never equal to an int, so {@code 3.0} and {@code 3} are different values.

<p>NaN is not a SOML value, and zero has no sign: {@code -0.0} becomes {@code 0.0}, so the two are equal, as the spec requires.

@param value The float.
*/
public record SomlFloat(double value) implements SomlValue {
	/**
	Creates a float.

	@throws IllegalArgumentException When {@code value} is NaN, which is not representable.
	*/
	public SomlFloat {
		if (Double.isNaN(value)) {
			throw new IllegalArgumentException("NaN is not representable. Use null for a missing value");
		}

		// Adding positive zero turns -0.0 into 0.0 and changes nothing else.
		value += 0.0;
	}

	/**
	The float as canonical form writes it: the shortest decimal that reads back as the same value, with a fractional part or an exponent so that it never reads as an int, as in {@code 30.0}, {@code 0.1}, {@code 1e21}, and {@code infinity}.
	*/
	@Override
	public String toString() {
		return format(value);
	}

	/*
	Note: Not using `Double.toString()` directly because it may choose two closer digits when one suffices, such as `4.9E-324` for the smallest subnormal instead of canonical `5e-324`. Canonical rule 11 uses ECMAScript's `Number::toString` layout with `.0` added.
	*/
	static String format(double value) {
		var simple = simpleFormat(value);

		if (simple != null) {
			return simple;
		}

		var text = new Writer.TextBuffer(32);
		appendComplex(text, value);
		return text.toString();
	}

	/*
	Writes canonical form directly into the encoder's buffer, without an intermediate result string.
	*/
	static void appendTo(Writer.TextBuffer text, double value) {
		var simple = simpleFormat(value);

		if (simple != null) {
			text.append(simple);
			return;
		}

		appendComplex(text, value);
	}

	private static void appendComplex(Writer.TextBuffer text, double value) {
		var magnitude = Math.abs(value);
		var javaText = Double.toString(magnitude);
		var exponentIndex = javaText.indexOf('E');
		// Outside the plain-notation range above, the JDK always gives a nonzero leading digit, a decimal point, and an exponent.
		var exponent = Integer.parseInt(javaText, exponentIndex + 1, javaText.length(), 10);
		var last = exponentIndex;

		while (javaText.charAt(last - 1) == '0' || javaText.charAt(last - 1) == '.') {
			last--;
		}

		// The value is 0.<digits> × 10^n.
		var digitCount = last == 1 ? 1 : last - 1;
		var n = exponent + 1;

		// A one-digit decimal and another two-digit one can only both read back as a value with few bits of precision, which is a subnormal. For any other value, the interval that reads back is far narrower than the step between them.
		if (digitCount == 2 && magnitude < Double.MIN_NORMAL) {
			var shorter = oneDigit(magnitude, javaText, n);

			if (shorter != 0) {
				if (value < 0) {
					text.append('-');
				}

				// The candidate rounded up to 10, which is the digit 1 one place up.
				var carries = shorter == 10;
				text.append(carries ? 1 : shorter).append('e').append(exponent + (carries ? 1 : 0));
				return;
			}
		}

		layout(text, javaText, digitCount, n, value < 0);
	}

	/*
	Canonical spellings that need no layout buffer, shared by standalone formatting and direct encoding.
	*/
	private static @Nullable String simpleFormat(double value) {
		if (Double.isInfinite(value)) {
			return value < 0 ? "-infinity" : "infinity";
		}

		if (value == 0) {
			return "0.0";
		}

		var magnitude = Math.abs(value);

		// From 10^-3 to 10^7, `Double.toString` writes plain notation with the shortest digits, which is canonical rule 11 there, so its text is the canonical text.
		return magnitude >= 1e-3 && magnitude < 1e7 ? Double.toString(value) : null;
	}

	/*
	The one-digit decimal that reads back as `magnitude`, given its closest two digits in the JDK's decimal text at 0.<digits> × 10^n, or 0 when there is none. When both neighbors read back, the closer one wins, and then the even one. Returns 10 for a round up past 9.
	*/
	private static int oneDigit(double magnitude, String javaText, int n) {
		var down = javaText.charAt(0) - '0';
		var up = down + 1;
		var downText = down + "e" + (n - 1);
		var upText = up + "e" + (n - 1);
		var isDownValid = Double.parseDouble(downText) == magnitude;
		var isUpValid = Double.parseDouble(upText) == magnitude;

		if (isDownValid && isUpValid) {
			// The closest two digits decide the closer neighbor unless they fall on its midpoint.
			var secondDigit = javaText.charAt(2) - '0';

			if (secondDigit != 5) {
				return secondDigit < 5 ? down : up;
			}

			var midpoint = BigDecimal.valueOf((down * 10) + 5, 2 - n);
			var comparison = new BigDecimal(magnitude).compareTo(midpoint);

			if (comparison == 0) {
				return down % 2 == 0 ? down : up;
			}

			return comparison < 0 ? down : up;
		}

		if (isDownValid) {
			return down;
		}

		return isUpValid ? up : 0;
	}

	/*
	Lays out k digits for the value 0.<digits> × 10^n, by canonical rule 11. The digits remain in the JDK's text, whose decimal point after its first digit is skipped.
	*/
	private static void layout(Writer.TextBuffer text, String digits, int k, int n, boolean isNegative) {
		if (isNegative) {
			text.append('-');
		}

		if (k <= n && n <= 21) {
			appendDigits(text, digits, 0, k);
			text.repeat('0', n - k).append(".0");
			return;
		}

		if (0 < n && n < k) {
			appendDigits(text, digits, 0, n);
			text.append('.');
			appendDigits(text, digits, n, k);
			return;
		}

		if (-6 < n && n <= 0) {
			text.append("0.").repeat('0', -n);
			appendDigits(text, digits, 0, k);
			return;
		}

		text.append(digits.charAt(0));

		if (k > 1) {
			text.append('.');
			appendDigits(text, digits, 1, k);
		}

		text.append('e').append(n - 1);
	}

	/*
	Note: Not using `String.replace()` to remove the JDK decimal point because it allocates another string. Copy the requested significant-digit range while skipping the point.
	*/
	private static void appendDigits(Writer.TextBuffer text, String digits, int start, int end) {
		if (start == 0) {
			text.append(digits.charAt(0));
			start++;
		}

		if (start < end) {
			text.append(digits, start + 1, end + 1);
		}
	}
}
