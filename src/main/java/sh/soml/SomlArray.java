package sh.soml;

import java.util.List;

/**
An array: an ordered sequence of values, which may have different types.

@param items The items, in order. The list is an unmodifiable copy.
*/
public record SomlArray(List<SomlValue> items) implements SomlValue {
	private static final SomlArray EMPTY = new SomlArray(List.of());

	/**
	Creates an array.

	@throws NullPointerException When {@code items} or one of its items is null. Use {@link SomlNull#INSTANCE} for a null item.
	*/
	public SomlArray {
		// Items that the library made are checked already, and nothing else holds them.
		if (!(items instanceof SomlObject.FrozenList<SomlValue>)) {
			items = List.copyOf(items);
		}
	}

	/*
	An array of items that are known to be valid, without a copy. The array has exactly one element per item, and must not be changed or shared afterwards.
	*/
	static SomlArray ofTrusted(SomlValue[] items) {
		return items.length == 0 ? EMPTY : new SomlArray(new SomlObject.FrozenList<>(items));
	}

	/**
	Creates an array of {@code items}.
	*/
	public static SomlArray of(SomlValue... items) {
		return items.length == 0 ? EMPTY : new SomlArray(List.of(items));
	}

	/**
	The item at {@code index}.

	@throws IndexOutOfBoundsException When {@code index} is not below the number of items.
	*/
	public SomlValue get(int index) {
		return items.get(index);
	}

	/**
	The array in canonical form, over several lines, with no line feed at the end.
	*/
	@Override
	public String toString() {
		return Writer.writeValue(this);
	}
}
