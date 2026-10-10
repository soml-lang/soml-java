package sh.soml;

/**
An int: a signed 64-bit integer. An int is never equal to a float, so {@code 3} and {@code 3.0} are different values.

@param value The integer.
*/
public record SomlInt(long value) implements SomlValue {
	/**
	The int as canonical form writes it: decimal digits, with {@code -} when it is negative.
	*/
	@Override
	public String toString() {
		return Long.toString(value);
	}
}
