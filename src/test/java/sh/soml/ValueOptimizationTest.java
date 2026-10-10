package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ValueOptimizationTest {
	@Test
	void zeroHashesRemainValidAcrossViewsAndRepeatedUse() {
		var object = (SomlObject) Soml.parse("a: 97");
		var array = (SomlArray) Soml.parse("[4294967265]");

		for (var attempt = 0; attempt < 3; attempt++) {
			assertEquals(0, object.members().hashCode());
			assertEquals(0, object.members().entrySet().hashCode());
			assertEquals(0, array.items().hashCode());
			assertEquals(new HashSet<>(object.members().keySet()).hashCode(), object.members().keySet().hashCode());
		}
	}

	@Test
	void concurrentValueOperationsPreserveOrderEqualityAndHashes() throws Exception {
		var source = new LinkedHashMap<String, SomlValue>();

		for (var index = 300; index >= 0; index--) {
			source.put("key" + index, SomlArray.of(new SomlInt(index), SomlNull.INSTANCE));
		}

		var object = new SomlObject(source);
		var expected = Soml.encodeCanonical(source);
		var expectedHash = source.hashCode();
		var expectedKeys = new ArrayList<>(source.keySet());
		var expectedEntries = new ArrayList<>(source.entrySet());

		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var tasks = new ArrayList<Callable<Void>>();

			for (var index = 0; index < 24; index++) {
				tasks.add(() -> {
					for (var attempt = 0; attempt < 20; attempt++) {
						assertEquals(expectedHash, object.members().hashCode());
						assertEquals(expectedHash, object.members().entrySet().hashCode());
						assertEquals(expected, Soml.encodeCanonical(object));
						var keys = new ArrayList<String>();
						object.members().keySet().forEach(keys::add);
						assertEquals(expectedKeys, keys);
						var entries = new ArrayList<Map.Entry<String, SomlValue>>();
						object.members().entrySet().forEach(entries::add);
						assertEquals(expectedEntries, entries);
					}

					return null;
				});
			}

			for (var future : executor.invokeAll(tasks)) {
				future.get();
			}
		}
	}

	@Test
	void keyAndEntryCallbacksStopAtTheThrowingElement() {
		var members = ((SomlObject) Soml.parse("z: 1\na: 2\nm: 3")).members();
		var keys = new ArrayList<String>();
		assertThrows(IllegalStateException.class, () -> members.keySet().forEach(key -> {
			keys.add(key);

			if (key.equals("a")) {
				throw new IllegalStateException();
			}
		}));
		assertEquals(List.of("z", "a"), keys);
		var entries = new ArrayList<String>();
		assertThrows(IllegalStateException.class, () -> members.entrySet().forEach(entry -> {
			entries.add(entry.getKey());
			assertThrows(UnsupportedOperationException.class, () -> entry.setValue(SomlNull.INSTANCE));

			if (entry.getKey().equals("a")) {
				throw new IllegalStateException();
			}
		}));
		assertEquals(keys, entries);
		assertThrows(NullPointerException.class, () -> new SomlObject(Map.of()).members().keySet().forEach(null));
		assertThrows(NullPointerException.class, () -> new SomlObject(Map.of()).members().entrySet().forEach(null));
	}

	@Test
	void wordSeparationPreservesUnicodeContextAndIgnoresTheDefaultLocale() {
		var previousLocale = Locale.getDefault();

		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			assertEquals("http-url", KeyNaming.KEBAB_CASE.apply("HTTPUrl"));
			assertEquals("i\u0307d-url", KeyNaming.KEBAB_CASE.apply("İdURL"));
			assertEquals("οσ-value", KeyNaming.KEBAB_CASE.apply("ΟΣValue"));
			assertEquals("𐐨-name", KeyNaming.KEBAB_CASE.apply("𐐀Name"));
			assertEquals("already_lower", KeyNaming.SNAKE_CASE.apply("already_lower"));
			assertEquals("", KeyNaming.KEBAB_CASE.apply(""));
		} finally {
			Locale.setDefault(previousLocale);
		}
	}

	@Test
	void fixedWidthNumbersMatchAnIndependentDecimalOracle() {
		var random = new Random(872);

		for (var width = 1; width <= 9; width++) {
			for (var attempt = 0; attempt < 100; attempt++) {
				var number = attempt == 0 ? 0 : random.nextInt(Integer.MAX_VALUE);
				var digits = Integer.toString(number);
				var expected = "0".repeat(Math.max(0, width - digits.length())) + digits;
				var buffer = new Writer.TextBuffer(0);
				buffer.append('[').appendPadded(number, width).append(']');
				assertEquals("[" + expected + "]", buffer.toString());
			}
		}
	}

	@Test
	void canonicalOutputAndIterationKeepTheirIndependentOrders() {
		for (var text : List.of("{}", "a: 1", "a: 1\nb: 2", "b: 2\na: 1", "'😀': 1\n'\uE000': 2")) {
			var object = (SomlObject) Soml.parse(text);
			var keys = List.copyOf(object.members().keySet());
			var canonical = Soml.encodeCanonical(object);

			for (var attempt = 0; attempt < 3; attempt++) {
				assertEquals(canonical, Soml.encodeCanonical(object));
				assertEquals(keys, List.copyOf(object.members().keySet()));
				assertEquals(object, Soml.parse(canonical));
				assertTrue(object.members().equals(Soml.decode(canonical, new TypeReference<Map<String, SomlValue>>() {})));
			}
		}
	}

	@Test
	void oversizedMachineIntegerKeysHaveBoundedErrorsWithoutLimitingBigIntegerKeys() {
		var key = "9".repeat(100_000);
		var value = new SomlObject(Map.of(key, new SomlInt(1)));
		var error = assertThrows(SomlException.class, () -> Soml.fromValue(value, new TypeReference<Map<Long, Integer>>() {}));
		assertTrue(error.reason().length() < 150);
		assertEquals(List.of(key), error.path());
		assertNull(error.getCause());
		var arbitraryKey = "1234567890".repeat(4);
		assertEquals(Map.of(new BigInteger(arbitraryKey), 1), Soml.decode("'" + arbitraryKey + "': 1", new TypeReference<Map<BigInteger, Integer>>() {}));
	}

	@Test
	void sanitizingMixedUnicodePreservesSafeRunsOnBothSidesOfControls() {
		var controls = new StringBuilder();

		for (var code = 0; code <= 0x9F; code++) {
			if (code != '\t' && (code < 0x20 || code >= 0x7F)) {
				controls.append((char) code);
			}
		}

		controls.append("\u061C\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\uD800x\uDC00");
		var safe = "\t😀éא日".repeat(100);
		var reason = safe + controls + safe;
		var error = new SomlException(reason);
		var expectedMiddle = "�".repeat(controls.length() - 3) + "�x�";
		assertEquals(safe + expectedMiddle + safe, error.reason());
		assertEquals(error.reason(), error.getMessage());
	}

	private static TypeReference<List<Map<String, List<Integer[]>>>> nestedType() {
		return new TypeReference<>() {};
	}

	@Test
	void repeatedTypeTokensCaptureNestedTypesDuringConcurrentUse() throws Exception {
		var expected = List.of(Map.of("items", List.of(new Integer[] {1, 2}, new Integer[] {3})));
		var text = Soml.encode(expected);

		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var tasks = new ArrayList<Callable<Void>>();

			for (var index = 0; index < 24; index++) {
				tasks.add(() -> {
					for (var attempt = 0; attempt < 20; attempt++) {
						var decoded = Soml.decode(text, nestedType());
						assertEquals(Soml.toValue(expected), Soml.toValue(decoded));
					}

					return null;
				});
			}

			for (var future : executor.invokeAll(tasks)) {
				future.get();
			}
		}
	}

	@Test
	void generatedCollectionArraysRespectDestinationTypesAndUnusedSlots() {
		var keys = ((SomlObject) Soml.parse("z: 1\na: 2")).members().keySet();
		var items = ((SomlArray) Soml.parse("[1, null]")).items();
		assertEquals(List.of("z", "a"), List.of(keys.toArray(String[]::new)));
		assertEquals(items, List.of(items.toArray(SomlValue[]::new)));
		assertThrows(ArrayStoreException.class, () -> items.toArray(String[]::new));

		for (var collection : List.of(keys, items)) {
			var destination = new Object[] {"unused", "unused", "unused", "kept"};
			assertSame(destination, collection.toArray(_ -> destination));
			assertEquals(new ArrayList<>(collection), java.util.Arrays.asList(destination).subList(0, collection.size()));
			assertNull(destination[collection.size()]);
			assertEquals("kept", destination[3]);
			assertThrows(NullPointerException.class, () -> collection.toArray((java.util.function.IntFunction<Object[]>) null));
		}

		assertEquals(0, ((SomlObject) Soml.parse("{}")).members().keySet().toArray(String[]::new).length);
	}

	@Test
	void frozenListEqualityAgreesWithRandomAccessAndSequentialLists() {
		var items = ((SomlArray) Soml.parse("[1, null, 'x']")).items();
		var expected = List.of(new SomlInt(1), SomlNull.INSTANCE, new SomlString("x"));

		for (var other : List.of(expected, new ArrayList<>(expected), new LinkedList<>(expected))) {
			assertTrue(items.equals(other));
			assertTrue(other.equals(items));
			assertEquals(other.hashCode(), items.hashCode());
		}

		var changed = new ArrayList<SomlValue>(expected);
		changed.set(1, null);
		assertFalse(items.equals(changed));
		assertFalse(items.equals(List.of()));
	}

	@Test
	void boxedScalarFastPathsKeepTreeTextAndAdaptersConsistent() {
		var values = List.of((short) -32768, (byte) -128, Double.MIN_VALUE, -0.0, 0.1f, Float.MAX_VALUE, 'é');
		var expected = SomlArray.of(new SomlInt(-32768), new SomlInt(-128), new SomlFloat(Double.MIN_VALUE), new SomlFloat(0), new SomlFloat(0.1), new SomlFloat(Double.parseDouble(Float.toString(Float.MAX_VALUE))), new SomlString("é"));
		assertEquals(expected, Soml.toValue(values));
		assertEquals(expected, Soml.parse(Soml.encode(values)));

		var mapper = SomlMapper.builder().adapter(Number.class, SomlAdapter.of(_ -> new SomlString("number"), _ -> 7)).build();
		var numbers = values.subList(0, values.size() - 1);
		var adapted = new SomlArray(java.util.Collections.nCopies(numbers.size(), new SomlString("number")));
		assertEquals(adapted, mapper.toValue(numbers));
		assertEquals(adapted, Soml.parse(mapper.encode(numbers)));

		for (var invalid : List.of((Object) Double.NaN, Float.NaN, '\r', '\uD800')) {
			var treeError = assertThrows(SomlException.class, () -> Soml.toValue(List.of("ok", invalid)));
			var textError = assertThrows(SomlException.class, () -> Soml.encode(List.of("ok", invalid)));
			assertEquals(List.of(1), treeError.path());
			assertEquals(treeError.path(), textError.path());
			assertEquals(treeError.reason(), textError.reason());
		}
	}
}
