package sh.soml;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;

/**
An object: members with unique string keys.

<p>The members keep the order they were given in, which is the order of the document for a parsed object, and {@link Soml#encode(Object)} writes them in that order. Order is not part of the value, so two objects with the same members are equal in any order, as the spec says. A map such as {@code Map.of()} or a {@code HashMap} has no order, and its order can change from one run to the next, so give a {@code LinkedHashMap} for a fixed order, or write canonical form.

@param members The members. The map is an unmodifiable copy that keeps the order of {@code members}. Its keys must be different by {@code equals()}, as they are in every map except one that compares by identity, such as {@code IdentityHashMap}.
*/
public record SomlObject(Map<String, SomlValue> members) implements SomlValue {
	private static final Frozen EMPTY_MEMBERS = new Frozen(new String[0], new SomlValue[0]);
	private static final SomlObject EMPTY = new SomlObject(Map.of());

	/**
	Creates an object.

	@throws NullPointerException When {@code members}, one of its keys, or one of its values is null. Use {@link SomlNull#INSTANCE} for a null member.
	@throws IllegalArgumentException When a key contains a carriage return (U+000D) or a lone surrogate.
	*/
	public SomlObject {
		// Members that the library made are checked already, and nothing else holds them.
		if (members.isEmpty()) {
			members = EMPTY_MEMBERS;
		} else if (!(members instanceof Frozen)) {
			var keys = new String[members.size()];
			var values = new SomlValue[keys.length];
			var count = 0;

			for (var entry : members.entrySet()) {
				var key = Objects.requireNonNull(entry.getKey(), "key");
				SomlString.checkText(key, "A key");
				keys[count] = key;
				values[count] = Objects.requireNonNull(entry.getValue(), "value");
				count++;
			}

			members = new Frozen(keys, values);
		}
	}

	/*
	An object of members that are known to be valid and unique, without a copy. The arrays have exactly one element per member, and must not be changed or shared afterwards.
	*/
	static SomlObject ofTrusted(String[] keys, SomlValue[] values) {
		return keys.length == 0 ? EMPTY : new SomlObject(new Frozen(keys, values));
	}

	/*
	An object of the first `count` members in arrays that are known to be valid and unique. Full arrays are taken as they are; spare capacity from omitted members is trimmed.
	*/
	static SomlObject ofTrusted(String[] keys, SomlValue[] values, int count) {
		if (count == 0) {
			return EMPTY;
		}

		return count == keys.length ? ofTrusted(keys, values) : ofTrusted(Arrays.copyOf(keys, count), Arrays.copyOf(values, count));
	}

	/*
	A copy with a known unique member before the existing members, for a sealed subtype's tag.
	*/
	SomlObject withLeadingMember(String key, SomlValue value) {
		var members = (Frozen) this.members;
		var keys = new String[members.keys.length + 1];
		var values = new SomlValue[keys.length];
		keys[0] = key;
		values[0] = value;
		System.arraycopy(members.keys, 0, keys, 1, members.keys.length);
		System.arraycopy(members.values, 0, values, 1, members.values.length);
		return ofTrusted(keys, values);
	}

	/*
	A copy without the existing member at `index`, for decoding a sealed subtype without its tag.
	*/
	SomlObject withoutMember(int index) {
		var members = (Frozen) this.members;
		Objects.checkIndex(index, members.keys.length);

		if (members.keys.length == 1) {
			return EMPTY;
		}

		var keys = new String[members.keys.length - 1];
		var values = new SomlValue[keys.length];
		System.arraycopy(members.keys, 0, keys, 0, index);
		System.arraycopy(members.values, 0, values, 0, index);
		System.arraycopy(members.keys, index + 1, keys, index, keys.length - index);
		System.arraycopy(members.values, index + 1, values, index, values.length - index);
		return ofTrusted(keys, values);
	}

	/*
	The number of members, and each member by its index in member order, for the writer and the decoder, which read every member and would otherwise make an entry for each.
	*/
	int memberCount() {
		// The constructor makes every map a `Frozen`, so these casts hold.
		return ((Frozen) members).keys.length;
	}

	String keyAt(int index) {
		return ((Frozen) members).keys[index];
	}

	SomlValue valueAt(int index) {
		return ((Frozen) members).values[index];
	}

	Integer @Nullable [] canonicalOrder() {
		return ((Frozen) members).canonicalOrder();
	}

	int indexOfKey(String key) {
		return ((Frozen) members).indexOf(key);
	}

	/**
	The value of the member with {@code key}, or null when there is none.
	*/
	public @Nullable SomlValue get(String key) {
		return members.get(key);
	}

	/**
	The object in canonical form, over several lines, with members sorted by key, and with no line feed at the end.
	*/
	@Override
	public String toString() {
		return Writer.writeValue(this);
	}

	/*
	Note: Not using `LinkedHashMap` because separate arrays of keys and values take less memory while preserving order. A lookup scans the keys of a small object, which is faster than hashing for a few keys, and uses a hash table for a larger one, made on first use. It is its own class, so that the constructor can tell that a map is one, which no other code can make or change.
	*/
	private static final class Frozen extends AbstractMap<String, SomlValue> {
		private static final int MAX_SCANNED = 8;
		private static final Integer[] IN_ORDER = {};

		final String[] keys;
		final SomlValue[] values;
		// Made on first use, so an object that is only iterated never pays for it. Threads that race may each make one, and every one is the same table.
		private volatile @Nullable KeyTable table;
		private volatile @Nullable Set<String> keySet;
		private volatile @Nullable Collection<SomlValue> valuesView;
		private volatile @Nullable Set<Map.Entry<String, SomlValue>> entrySet;
		private volatile Integer @Nullable [] canonicalOrder;
		// A zero hash may be recomputed. All values are immutable, so racing calculations agree.
		private int hash;

		Frozen(String[] keys, SomlValue[] values) {
			this.keys = keys;
			this.values = values;
		}

		Integer @Nullable [] canonicalOrder() {
			var order = canonicalOrder;

			if (order == null) {
				order = Writer.canonicalOrder(keys, keys.length);
				order = order == null ? IN_ORDER : order;
				canonicalOrder = order;
			}

			return order.length == 0 ? null : order;
		}

		private int indexOf(@Nullable Object key) {
			if (!(key instanceof String string)) {
				return -1;
			}

			if (keys.length <= MAX_SCANNED) {
				for (var index = 0; index < keys.length; index++) {
					if (keys[index].equals(string)) {
						return index;
					}
				}

				return -1;
			}

			return table().find(keys, string);
		}

		private KeyTable table() {
			var table = this.table;

			if (table == null) {
				table = new KeyTable(keys, keys.length, keys.length);
				this.table = table;
			}

			return table;
		}

		@Override
		public int size() {
			return keys.length;
		}

		@Override
		public boolean isEmpty() {
			return keys.length == 0;
		}

		@Override
		public boolean containsKey(@Nullable Object key) {
			return indexOf(key) != -1;
		}

		@Override
		public boolean containsValue(@Nullable Object value) {
			if (value == null) {
				return false;
			}

			for (var each : values) {
				if (value.equals(each)) {
					return true;
				}
			}

			return false;
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (object == this) {
				return true;
			}

			if (!(object instanceof Map<?, ?> map) || map.size() != keys.length) {
				return false;
			}

			if (map instanceof Frozen frozen && Arrays.equals(keys, frozen.keys)) {
				return Arrays.equals(values, frozen.values);
			}

			try {
				for (var index = 0; index < keys.length; index++) {
					if (!values[index].equals(map.get(keys[index]))) {
						return false;
					}
				}
			} catch (ClassCastException | NullPointerException _) {
				// A map can refuse keys of another type, as a TreeMap with integer keys does.
				return false;
			}

			return true;
		}

		@Override
		public @Nullable SomlValue get(@Nullable Object key) {
			var index = indexOf(key);
			return index == -1 ? null : values[index];
		}

		@Override
		public @Nullable SomlValue getOrDefault(@Nullable Object key, @Nullable SomlValue defaultValue) {
			var index = indexOf(key);
			return index == -1 ? defaultValue : values[index];
		}

		@Override
		public void forEach(BiConsumer<? super String, ? super SomlValue> action) {
			Objects.requireNonNull(action, "action");

			for (var index = 0; index < keys.length; index++) {
				action.accept(keys[index], values[index]);
			}
		}

		@Override
		public Set<String> keySet() {
			var view = keySet;

			if (view != null) {
				return view;
			}

			view = new AbstractSet<>() {
				@Override
				public Object[] toArray() {
					return Arrays.copyOf(keys, keys.length, Object[].class);
				}

				@Override
				public <T> T[] toArray(T[] destination) {
					return copyArray(keys, destination);
				}

				@Override
				public <T> T[] toArray(IntFunction<T[]> generator) {
					return copyArray(keys, generator.apply(keys.length));
				}

				@Override
				public void forEach(Consumer<? super String> action) {
					Objects.requireNonNull(action, "action");

					for (var key : keys) {
						action.accept(key);
					}
				}

				@Override
				public int hashCode() {
					var hash = 0;

					for (var key : keys) {
						hash += key.hashCode();
					}

					return hash;
				}

				@Override
				public Iterator<String> iterator() {
					return new ArrayIterator<>(keys);
				}

				@Override
				public int size() {
					return keys.length;
				}

				@Override
				public boolean contains(Object key) {
					return indexOf(key) != -1;
				}

				@Override
				public Spliterator<String> spliterator() {
					return Spliterators.spliterator(keys, Spliterator.ORDERED | Spliterator.IMMUTABLE | Spliterator.NONNULL | Spliterator.DISTINCT);
				}
			};
			keySet = view;
			return view;
		}

		@Override
		public Collection<SomlValue> values() {
			var view = valuesView;

			if (view == null) {
				view = new FrozenList<>(values);
				valuesView = view;
			}

			return view;
		}

		@Override
		public Set<Map.Entry<String, SomlValue>> entrySet() {
			var view = entrySet;

			if (view != null) {
				return view;
			}

			view = new AbstractSet<>() {
				@Override
				public int hashCode() {
					return Frozen.this.hashCode();
				}

				@Override
				public void forEach(Consumer<? super Map.Entry<String, SomlValue>> action) {
					Objects.requireNonNull(action, "action");

					for (var index = 0; index < keys.length; index++) {
						action.accept(Map.entry(keys[index], values[index]));
					}
				}

				@Override
				public Iterator<Map.Entry<String, SomlValue>> iterator() {
					return new Iterator<>() {
						private int index;

						@Override
						public boolean hasNext() {
							return index < keys.length;
						}

						@Override
						public Map.Entry<String, SomlValue> next() {
							if (index >= keys.length) {
								throw new NoSuchElementException();
							}

							var entry = Map.entry(keys[index], values[index]);
							index++;
							return entry;
						}
					};
				}

				@Override
				public int size() {
					return keys.length;
				}

				@Override
				public boolean contains(@Nullable Object object) {
					if (!(object instanceof Map.Entry<?, ?> entry)) {
						return false;
					}

					var index = indexOf(entry.getKey());
					return index != -1 && values[index].equals(entry.getValue());
				}

				@Override
				public Spliterator<Map.Entry<String, SomlValue>> spliterator() {
					// Note: Not using `Spliterators.spliteratorUnknownSize()` because the known member count preserves exact size and subdivision characteristics.
					return Spliterators.spliterator(iterator(), keys.length, Spliterator.ORDERED | Spliterator.IMMUTABLE | Spliterator.NONNULL | Spliterator.DISTINCT);
				}
			};
			entrySet = view;
			return view;
		}

		// Note: Not using `AbstractMap.hashCode()` because it iterates over entries rather than the key/value arrays. Sum the entry hash codes as `Map` defines them without constructing entries.
		@Override
		public int hashCode() {
			var hash = this.hash;

			if (hash != 0) {
				return hash;
			}

			for (var index = 0; index < keys.length; index++) {
				hash += keys[index].hashCode() ^ values[index].hashCode();
			}

			this.hash = hash;
			return hash;
		}
	}

	/*
	Note: Not using `HashMap` unconditionally because a half-full open-addressing table with linear probing on `String.hashCode()` costs less. The table stores key indexes for objects with more than a few members. Keys can be made to have one hash code, as `Aa` and `BB` have, and then every probe would scan all of them, so when the probes add up to more than a few per key, the table moves the keys to a `HashMap`, which keeps keys with one hash code in a sorted tree. Keys are added in order, so the key at index n is the n-th key added. After it is made, a table that is only read is safe to share between threads.
	*/
	static final class KeyTable {
		private static final int MAX_PROBES_PER_KEY = 8;
		// The slots of a table whose keys moved to `map`.
		private static final int[] NO_SLOTS = {};

		// Each slot holds a key's index plus one, or 0 when it is empty.
		private int[] slots;
		private @Nullable HashMap<String, Integer> map;
		private int size;
		private int probes;

		/*
		A table of the first `count` keys, with room for `capacity` keys.
		*/
		KeyTable(String[] keys, int count, int capacity) {
			// The smallest power of two that is at least twice `capacity`, so the table is at most half full and a mask finds the slot.
			slots = new int[Integer.highestOneBit(capacity * 2 - 1) << 1];

			while (size < count) {
				add(keys);
			}
		}

		/*
		Whether one more key keeps the table at most half full.
		*/
		boolean hasRoom() {
			return map != null || (size + 1) * 2 <= slots.length;
		}

		/*
		The index of `key` in `keys`, or -1 when it is not there.
		*/
		int find(String[] keys, String key) {
			if (map != null) {
				var index = map.get(key);
				return index == null ? -1 : index;
			}

			var mask = slots.length - 1;

			for (var slot = spread(key.hashCode()) & mask; slots[slot] != 0; slot = (slot + 1) & mask) {
				if (keys[slots[slot] - 1].equals(key)) {
					return slots[slot] - 1;
				}
			}

			return -1;
		}

		/*
		Adds the next key of `keys`, which is not in the table yet. The probes of a lookup are those of an add, so only an add counts them, and a lookup changes nothing.
		*/
		void add(String[] keys) {
			var index = size++;

			if (map != null) {
				map.put(keys[index], index);
				return;
			}

			var mask = slots.length - 1;
			var slot = spread(keys[index].hashCode()) & mask;

			while (slots[slot] != 0) {
				slot = (slot + 1) & mask;
				probes++;
			}

			slots[slot] = index + 1;

			// The 64 extra probes keep a small table, whose few keys can collide by chance, from moving to a `HashMap`.
			if (probes > MAX_PROBES_PER_KEY * size + 64) {
				map = HashMap.newHashMap(size);

				for (var each = 0; each < size; each++) {
					map.put(keys[each], each);
				}

				slots = NO_SLOTS;
			}
		}

		private static int spread(int hash) {
			return hash ^ (hash >>> 16);
		}
	}

	/*
	An iterator over an array, which cannot remove.
	*/
	// Note: Not using `Arrays.asList(array).iterator()` because its extra wrapper escaped in traversal benchmarks, adding 32 bytes per traversal.
	private static final class ArrayIterator<Element> implements Iterator<Element> {
		private final Element[] elements;
		private int index;

		ArrayIterator(Element[] elements) {
			this.elements = elements;
		}

		@Override
		public void forEachRemaining(Consumer<? super Element> action) {
			Objects.requireNonNull(action, "action");

			while (index < elements.length) {
				action.accept(elements[index++]);
			}
		}

		@Override
		public boolean hasNext() {
			return index < elements.length;
		}

		@Override
		public Element next() {
			if (index >= elements.length) {
				throw new NoSuchElementException();
			}

			return elements[index++];
		}
	}

	/*
	An unmodifiable list over an array that nothing else changes, without a copy. `SomlArray` uses it for the items that the parser and the encoder make, which are checked already.
	*/
	static final class FrozenList<Element> extends AbstractList<Element> implements RandomAccess {
		private final Element[] elements;
		// A zero hash may be recomputed, without needing another field to mark it as cached.
		private int hash;

		FrozenList(Element[] elements) {
			this.elements = elements;
		}

		@Override
		public void forEach(Consumer<? super Element> action) {
			Objects.requireNonNull(action, "action");

			for (var element : elements) {
				action.accept(element);
			}
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (object == this) {
				return true;
			}

			if (object instanceof FrozenList<?> list) {
				// Note: Not using `Arrays.mismatch()` because `Arrays.equals()` rejects unequal lengths before scanning the common prefix.
				return Arrays.equals(elements, list.elements);
			}

			if (object instanceof List<?> list && list instanceof RandomAccess) {
				if (elements.length != list.size()) {
					return false;
				}

				for (var index = 0; index < elements.length; index++) {
					if (!elements[index].equals(list.get(index))) {
						return false;
					}
				}

				return true;
			}

			return super.equals(object);
		}

		@Override
		public int hashCode() {
			var hash = this.hash;

			if (hash == 0) {
				hash = Arrays.hashCode(elements);
				this.hash = hash;
			}

			return hash;
		}

		@Override
		public Element get(int index) {
			return elements[index];
		}

		@Override
		public int size() {
			return elements.length;
		}

		@Override
		public boolean contains(@Nullable Object element) {
			return indexOf(element) != -1;
		}

		@Override
		public int indexOf(@Nullable Object element) {
			if (element == null) {
				return -1;
			}

			for (var index = 0; index < elements.length; index++) {
				if (element.equals(elements[index])) {
					return index;
				}
			}

			return -1;
		}

		@Override
		public int lastIndexOf(@Nullable Object element) {
			if (element == null) {
				return -1;
			}

			for (var index = elements.length - 1; index >= 0; index--) {
				if (element.equals(elements[index])) {
					return index;
				}
			}

			return -1;
		}

		@Override
		public Iterator<Element> iterator() {
			return new ArrayIterator<>(elements);
		}

		@Override
		public Object[] toArray() {
			return Arrays.copyOf(elements, elements.length, Object[].class);
		}

		@Override
		public <T> T[] toArray(T[] destination) {
			return copyArray(elements, destination);
		}

		@Override
		public <T> T[] toArray(IntFunction<T[]> generator) {
			return copyArray(elements, generator.apply(elements.length));
		}

		@Override
		public Spliterator<Element> spliterator() {
			return Spliterators.spliterator(elements, Spliterator.ORDERED | Spliterator.IMMUTABLE | Spliterator.NONNULL);
		}
	}

	// The Collection contract requires a null terminator in an oversized destination, even when its component type is non-null.
	@SuppressWarnings({"unchecked", "NullAway"})
	private static <T> T[] copyArray(Object[] elements, T[] destination) {
		if (destination.length < elements.length) {
			return (T[]) Arrays.copyOf(elements, elements.length, destination.getClass());
		}

		System.arraycopy(elements, 0, destination, 0, elements.length);

		if (destination.length > elements.length) {
			destination[elements.length] = null;
		}

		return destination;
	}
}
