package sh.soml;

/**
A SOML value: one of the ten types of the data model.

<p>The type is sealed, so a {@code switch} over it is exhaustive:

<pre>{@code
String describe(SomlValue value) {
	return switch (value) {
		case SomlString(var string) -> "the string " + string;
		case SomlInt(var number) -> "the int " + number;
		case SomlFloat(var number) -> "the float " + number;
		case SomlBool(var bool) -> "the bool " + bool;
		case SomlNull _ -> "null";
		case SomlDate(var date) -> "the date " + date;
		case SomlInstant(var instant) -> "the instant " + instant;
		case SomlDuration(var duration) -> "the duration " + duration;
		case SomlArray(var items) -> items.size() + " items";
		case SomlObject(var members) -> members.size() + " members";
	};
}
}</pre>

<p>Every value is immutable. Equality follows the spec: an int and a float are never equal, so {@code 3} and {@code 3.0} differ, the order of an object's members does not matter, dates compare their year, month, and day, and two instants are equal when they are the same point in time.

<p>{@link #toString()} returns the value as it is written in a document in canonical form, such as {@code 'text'}, {@code 1.5}, or {@code 1h30m}. A container is written over several lines, with its members sorted by key, so equal values give equal text.
*/
public sealed interface SomlValue permits SomlString, SomlInt, SomlFloat, SomlBool, SomlNull, SomlDate, SomlInstant, SomlDuration, SomlArray, SomlObject {
	/**
	The value as it is written in a document in canonical form.

	<p>A scalar is written on one line, and an object or an array over several lines, with each level indented by one more tab and with no line feed at the end.
	*/
	@Override
	String toString();
}
