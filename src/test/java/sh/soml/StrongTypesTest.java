package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class StrongTypesTest {
	record Reading<T>(@SomlName("record-key") T value, @Nullable String note) {}

	static class Parent<T> {
		@SomlName("parent-key")
		T inherited;
	}

	static class Settings extends Parent<List<Reading<Integer>>> {
		private int port = 443;
		private @Nullable String label = "default";
	}

	record Configuration(Settings settings, Map<String, Reading<Integer>> readings) {}

	@Test
	void mixedGenericRecordsAndInheritedFieldsKeepTheirOwnMetadata() {
		var text = "settings: {parent-key: [{record-key: 7}]}\nreadings: {first: {record-key: 8, note: 'ready'}}";
		var streamed = Soml.decode(text, Configuration.class);
		var fromTree = Soml.fromValue(Soml.parse(text), Configuration.class);

		for (var configuration : List.of(streamed, fromTree)) {
			assertEquals(443, configuration.settings().port);
			assertEquals("default", configuration.settings().label);
			assertEquals(List.of(new Reading<>(7, null)), configuration.settings().inherited);
			assertNull(configuration.settings().inherited.getFirst().note());
			assertEquals(Map.of("first", new Reading<>(8, "ready")), configuration.readings());
			assertEquals(Soml.toValue(streamed), Soml.parse(Soml.encodeCanonical(configuration)));
		}
	}

	static class Defaults {
		@SomlName("read-only")
		final String readOnly = "kept";
		private int mutable = 2;
	}

	@Test
	void onlyFieldsPresentInTheDocumentNeedToBeWritable() {
		for (var decoded : List.of(Soml.decode("mutable: 3", Defaults.class), Soml.fromValue(Soml.parse("mutable: 3"), Defaults.class))) {
			assertEquals(3, decoded.mutable);
			assertEquals("kept", decoded.readOnly);
			assertEquals(new SomlObject(Map.of("read-only", new SomlString("kept"), "mutable", new SomlInt(3))), Soml.toValue(decoded));
		}

		var text = "'read-only': 'changed'";
		var streamed = assertThrows(SomlException.class, () -> Soml.decode(text, Defaults.class));
		var fromTree = assertThrows(SomlException.class, () -> Soml.fromValue(Soml.parse(text), Defaults.class));
		assertEquals(List.of("read-only"), streamed.path());
		assertEquals(streamed.path(), fromTree.path());
		assertEquals(streamed.reason(), fromTree.reason());
		assertTrue(streamed.reason().contains("Cannot decode the final field readOnly"));
	}

	record FailingAccessor(@SomlName("record-key") String value) {
		@Override
		public String value() {
			throw new IllegalStateException("deliberate getter failure");
		}
	}

	@Test
	void recordAccessorFailuresKeepTheOriginalCauseAndNestedPath() {
		var value = Map.of("values", List.of(new FailingAccessor("stored")));
		var textError = assertThrows(SomlException.class, () -> Soml.encode(value));
		var treeError = assertThrows(SomlException.class, () -> Soml.toValue(value));

		for (var error : List.of(textError, treeError)) {
			assertEquals(List.of("values", 0, "record-key"), error.path());
			assertEquals("deliberate getter failure", assertInstanceOf(IllegalStateException.class, error.getCause()).getMessage());
		}

		assertEquals(textError.reason(), treeError.reason());
	}

	@Test
	void everyDurationUnitPairUsesItsActualSizeAndStrictDescendingOrder() {
		var units = List.of(Map.entry("h", Duration.ofHours(1)), Map.entry("m", Duration.ofMinutes(1)), Map.entry("s", Duration.ofSeconds(1)), Map.entry("ms", Duration.ofMillis(1)), Map.entry("us", Duration.ofNanos(1000)), Map.entry("ns", Duration.ofNanos(1)));

		for (var first : units) {
			assertEquals(SomlDuration.ofNanos(first.getValue().toNanos()), ((SomlArray) Soml.parse("[1" + first.getKey() + "]")).get(0));

			for (var second : units) {
				var text = "[1" + first.getKey() + "1" + second.getKey() + "]";

				if (first.getValue().compareTo(second.getValue()) > 0) {
					assertEquals(SomlDuration.ofNanos(first.getValue().plus(second.getValue()).toNanos()), ((SomlArray) Soml.parse(text)).get(0), text);
				} else {
					var error = assertThrows(SomlException.class, () -> Soml.parse(text), text);
					assertTrue(error.reason().contains("the units are in the order h, m, s, ms, us, ns"), text);
				}
			}
		}
	}
}
