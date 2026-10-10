package sh.soml;

/**
{@code null}: a member that is present and has no value, which is different from a member that is left out.
*/
public record SomlNull() implements SomlValue {
	/**
	The null value. Every {@code SomlNull} is equal to it, so compare with {@code equals()} or {@code instanceof}.
	*/
	public static final SomlNull INSTANCE = new SomlNull();

	@Override
	public String toString() {
		return "null";
	}
}
