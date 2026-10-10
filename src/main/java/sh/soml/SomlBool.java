package sh.soml;

/**
A bool: {@code true} or {@code false}. Compare bools with {@code equals()}, because the constructor makes a new one.

@param value The bool.
*/
public record SomlBool(boolean value) implements SomlValue {
	/**
	{@code true}.
	*/
	public static final SomlBool TRUE = new SomlBool(true);

	/**
	{@code false}.
	*/
	public static final SomlBool FALSE = new SomlBool(false);

	/**
	The bool for {@code value}, without making a new one.
	*/
	public static SomlBool of(boolean value) {
		return value ? TRUE : FALSE;
	}

	@Override
	public String toString() {
		return value ? "true" : "false";
	}
}
