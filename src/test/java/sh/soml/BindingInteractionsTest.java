package sh.soml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.SequencedMap;
import java.util.SequencedSet;
import org.junit.jupiter.api.Test;

class BindingInteractionsTest {
	enum Mode {
		@SomlName("automatic")
		AUTO {
			@Override
			public String toString() {
				return "not-the-name";
			}
		},
		MANUAL
	}

	@Test
	void enumBodiesUseTheirDeclaredNamesInEveryEncodingPath() {
		var values = List.of(Mode.AUTO, Mode.MANUAL);
		assertEquals("[\n\t'automatic'\n\t'MANUAL'\n]\n", Soml.encode(values));
		assertEquals(Soml.parse(Soml.encode(values)), Soml.toValue(values));
		assertEquals(values, Soml.decode(Soml.encode(values), new TypeReference<List<Mode>>() {}));
	}

	record Options(Optional<String> omitted, String nullable, OptionalDouble ratio, int count) {}

	@Test
	void trustedRecordTreesPreserveOmissionAndNull() {
		var options = new Options(Optional.empty(), null, OptionalDouble.of(0.25), 3);
		var tree = (SomlObject) Soml.toValue(options);
		assertEquals(List.of("nullable", "ratio", "count"), tree.members().keySet().stream().toList());
		assertEquals(SomlNull.INSTANCE, tree.get("nullable"));
		assertEquals(options, Soml.fromValue(tree, Options.class));
		assertEquals(Soml.parse(Soml.encode(options)), tree);
		assertEquals(Soml.encodeCanonical(options), Soml.encodeCanonical(tree));
	}

	@Test
	void optionalDoubleRejectsNaNThroughTreeEncoding() {
		var error = assertThrows(SomlException.class, () -> Soml.toValue(List.of(OptionalDouble.of(Double.NaN))));
		assertEquals(List.of(0), error.path());
		assertTrue(error.reason().contains("NaN"));
		assertEquals(SomlArray.of(new SomlFloat(0.0), SomlNull.INSTANCE), Soml.toValue(List.of(OptionalDouble.of(-0.0), OptionalDouble.empty())));
	}

	@Test
	void linkedCollectionsPreserveNullItemsAndOrderDuringTreeEncoding() {
		var values = new java.util.LinkedList<>(java.util.Arrays.asList(1, null, Optional.empty(), "x"));
		assertEquals(SomlArray.of(new SomlInt(1), SomlNull.INSTANCE, SomlNull.INSTANCE, new SomlString("x")), Soml.toValue(values));
		assertEquals(Soml.parse(Soml.encode(values)), Soml.toValue(values));
	}

	record Box<T>(T[] values) {}

	static class Parent<T> {
		T[] values;
	}

	static final class Child extends Parent<String> {}

	@Test
	void genericArraysResolveInRecordsAndInheritedFields() {
		var box = Soml.decode("values: ['a', 'b']", new TypeReference<Box<String>>() {});
		assertEquals(String[].class, box.values().getClass());
		assertArrayEquals(new String[] {"a", "b"}, box.values());
		var child = Soml.decode("values: ['a', 'b']", Child.class);
		assertEquals(String[].class, child.values.getClass());
		assertArrayEquals(box.values(), child.values);
		assertEquals(Soml.encode(box), Soml.encode(child));
	}

	record Names(String deployedAt) {}

	@Test
	void builderChangesDoNotModifyExistingMappers() {
		var builder = SomlMapper.builder();
		var defaults = builder.build();
		var strict = builder.keyNaming(KeyNaming.KEBAB_CASE).rejectUnknownKeys(true).maxDocumentLength(40).build();
		builder.keyNaming(KeyNaming.SNAKE_CASE).rejectUnknownKeys(false).maxDocumentLength(3);
		var value = new Names("today");
		assertEquals("deployedAt: 'today'\n", defaults.encode(value));
		assertEquals("deployed-at: 'today'\n", strict.encode(value));
		assertEquals(value, defaults.decode("deployedAt: 'today'\nextra: 1", Names.class));
		assertEquals(value, strict.decode("deployed-at: 'today'", Names.class));
		var unknown = assertThrows(SomlException.class, () -> strict.decode("deployed-at: 'today'\nextra: 1", Names.class));
		assertEquals(List.of("extra"), unknown.path());
		assertThrows(SomlException.class, () -> strict.decode("deployed-at: '" + "x".repeat(50) + "'", Names.class));
	}

	record Server(String host, int port) {}

	@Test
	void ignoredContainersDoNotShiftReorderedRecordMembers() {
		var text = "ignored: {items: [{x: [null, true]}]}\nport: 443\nhost: 'api'";
		assertEquals(new Server("api", 443), Soml.decode(text, Server.class));
		assertEquals(new Server("api", 443), Soml.fromValue(Soml.parse(text), Server.class));
	}

	@Test
	void nestedErrorsLocateReorderedKeysAndUnknownKeyPositions() {
		var type = new TypeReference<Map<String, List<Server>>>() {};
		var text = "region: [{port: 'bad', host: 'a'}, {host: 2, port: 443}]";
		var error = assertThrows(SomlException.class, () -> Soml.decode(text, type));
		assertEquals(List.of(List.of("region", 0, "port"), List.of("region", 1, "host")), error.errors().stream().map(SomlException::path).toList());
		assertEquals(text.indexOf("'bad'") + 1, error.column());
		assertEquals(text.indexOf("2,") + 1, error.errors().get(1).column());
		var strict = SomlMapper.builder().rejectUnknownKeys(true).build();
		var unknown = assertThrows(SomlException.class, () -> strict.decode("host: 'a'\nextra: 1\nport: 443", Server.class));
		assertEquals(List.of("extra"), unknown.path());
		assertEquals(2, unknown.line());
		assertEquals(1, unknown.column());
		assertTrue(unknown.reason().endsWith("its members are host, port"));
	}

	static final class ManyMembers {
		int key20, key19, key18, key17, key16, key15, key14, key13, key12, key11, key10, key09, key08, key07, key06, key05, key04, key03, key02, key01, key00;
	}

	@Test
	void unknownKeySuggestionsAreSortedAndLimitedToTwenty() {
		var mapper = SomlMapper.builder().rejectUnknownKeys(true).build();
		var error = assertThrows(SomlException.class, () -> mapper.decode("unknown: 1", ManyMembers.class));
		var expected = new java.util.ArrayList<String>();

		for (var index = 0; index < 20; index++) {
			expected.add("key" + (index < 10 ? "0" : "") + index);
		}

		assertEquals("Unknown key unknown. ManyMembers has no member for it, and its members are " + String.join(", ", expected) + ", …", error.reason());
	}

	@Test
	void canonicalMapSortingAgreesWithTreeSortingAfterOptionalOmission() {
		for (var keys : List.of(List.of("a", "z", "\uE000", "😀"), List.of("😀", "\uE000", "z", "a"))) {
			var map = new LinkedHashMap<String, Object>();

			for (var key : keys) {
				map.put(key, key);
			}

			map.put("omitted", Optional.empty());
			var canonical = Soml.encodeCanonical(map);
			assertEquals(List.of("a", "z", "\uE000", "😀"), ((SomlObject) Soml.parse(canonical)).members().keySet().stream().toList());
			assertEquals(Soml.encodeCanonical(Soml.toValue(map)), canonical);
			assertEquals(keys, map.keySet().stream().filter(key -> !key.equals("omitted")).toList());
		}
	}

	@Test
	void sequencedInterfacesExposeOrderedImmutableReverseViews() {
		var map = Soml.decode("z: 1\na: 2", new TypeReference<SequencedMap<String, Integer>>() {});
		assertEquals(List.of("a", "z"), map.reversed().sequencedKeySet().stream().toList());
		assertThrows(UnsupportedOperationException.class, () -> map.reversed().putFirst("x", 3));
		var set = Soml.decode("[3, 1, 2]", new TypeReference<SequencedSet<Integer>>() {});
		assertEquals(List.of(2, 1, 3), set.reversed().stream().toList());
		assertThrows(UnsupportedOperationException.class, () -> set.reversed().removeFirst());
		assertFalse(set.isEmpty());
	}
}
