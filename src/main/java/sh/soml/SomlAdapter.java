package sh.soml;

import java.util.Objects;
import java.util.function.Function;

/**
Converts a type that the mapper does not support, or one you want written differently, to and from a {@link SomlValue}.

<p>A type can declare its own adapter as a static field of type {@code SomlAdapter<T>}, whatever its name, which {@link Soml} and every mapper use:

<pre>{@code
record Color(int red, int green, int blue) {
	static final SomlAdapter<Color> SOML = SomlAdapter.of(
		color -> new SomlString(color.toHex()),
		value -> {
			if (!(value instanceof SomlString(var hex))) {
				throw new SomlException("Expected a color, such as '#FF8800'");
			}

			return Color.fromHex(hex);
		}
	);
}
}</pre>

<p>For a type that you cannot change, register the adapter with {@link SomlMapper.Builder#adapter(Class, SomlAdapter)}, which wins over the type's own.

<p>An adapter is used for its class, and an adapter for an interface or an abstract class also for the classes that implement it, where the nearest one wins. A primitive and its wrapper share one registration, so the last adapter registered for {@code int.class} or {@code Integer.class} is used for both. It is not called for {@code null}, which is always {@link SomlNull}.

@param <T> The type it converts.
*/
public interface SomlAdapter<T> {
	/**
	Converts a value to SOML.

	@throws SomlException When the value cannot be written. The encoder adds the path.
	*/
	SomlValue encode(T value);

	/**
	Converts a SOML value, which is never {@link SomlNull}. It returns a value, not {@code null}, because {@code null} is what {@link SomlNull} decodes to.

	@throws SomlException When the value does not fit. The decoder adds the path and the position.
	*/
	T decode(SomlValue value);

	/**
	An adapter of two functions, as in {@code SomlAdapter.of(color -> new SomlString(color.toHex()), value -> Color.fromHex(...))}.
	*/
	static <T> SomlAdapter<T> of(Function<? super T, ? extends SomlValue> encode, Function<? super SomlValue, ? extends T> decode) {
		Objects.requireNonNull(encode, "encode");
		Objects.requireNonNull(decode, "decode");

		return new SomlAdapter<>() {
			@Override
			public SomlValue encode(T value) {
				return encode.apply(value);
			}

			@Override
			public T decode(SomlValue value) {
				return decode.apply(value);
			}
		};
	}
}
