package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EncodeTest {
	enum Level {
		DEBUG,
		@SomlName("info")
		INFO
	}

	record Failing(int a) {
		@Override
		public int a() {
			throw new IllegalStateException("No value");
		}
	}

	@Test
	void reportsTheMemberWhoseAccessorFails() {
		assertEquals(List.of("a"), assertThrows(SomlException.class, () -> Soml.encode(new Failing(1))).path());
		assertEquals(List.of("a"), assertThrows(SomlException.class, () -> Soml.toValue(new Failing(1))).path());
	}

	@Test
	void refusesMapKeysThatAreWrittenTheSame() {
		for (var keys : List.<List<Object>>of(List.of(1, "1"), List.of("1", 1), List.of(1, "2", "1"))) {
			var map = new LinkedHashMap<Object, Object>();

			for (var key : keys) {
				map.put(key, "x");
			}

			for (var encode : List.<java.util.function.Function<Object, ?>>of(Soml::encode, Soml::encodeCanonical, Soml::toValue)) {
				var error = assertThrows(SomlException.class, () -> encode.apply(map));
				assertEquals("Two keys of the map are both written as 1", error.reason(), keys.toString());
				assertEquals(List.of("1"), error.path(), keys.toString());
			}
		}
	}

	@Test
	void refusesEqualKeysInIdentityMaps() {
		var map = new java.util.IdentityHashMap<String, Integer>();
		map.put(new String("a"), 1);
		map.put(new String("a"), 2);
		assertThrows(SomlException.class, () -> Soml.encode(map));
		assertThrows(SomlException.class, () -> Soml.encodeCanonical(map));
		assertThrows(SomlException.class, () -> Soml.toValue(map));
	}

	@Test
	void writesMapKeysOfEveryKeyType() {
		assertEquals("404: 'not found'\n", Soml.encode(Map.of(404, "not found")));
		assertEquals("-1: 'x'\n", Soml.encode(Map.of(-1L, "x")));
		assertEquals("info: 1\n", Soml.encode(Map.of(Level.INFO, 1)));
		assertEquals("123e4567-e89b-12d3-a456-426614174000: true\n", Soml.encode(Map.of(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), true)));
		assertEquals("18446744073709551616: 1\n", Soml.encode(Map.of(BigInteger.TWO.pow(64), 1)));
		assertEquals("'a b': 1\n", Soml.encode(Map.of("a b", 1)));

		var nullKey = new HashMap<String, Integer>();
		nullKey.put(null, 1);
		assertEquals("Cannot encode a map with a null key", assertThrows(SomlException.class, () -> Soml.encode(nullKey)).reason());
		assertEquals("Cannot encode a map key of type Double. A map key can be a String, a char, an enum, an integer type, or a UUID", assertThrows(SomlException.class, () -> Soml.encode(Map.of(1.5, 1))).reason());
	}

	@Test
	void writesSetsInIterationOrder() {
		assertEquals("[\n\t'b'\n\t'a'\n]\n", Soml.encode(new LinkedHashSet<>(List.of("b", "a"))));
	}

	@Test
	void writesNumbers() {
		assertEquals("a: 0.0\nb: 0.1\nc: 3.0\nd: -infinity\n", Soml.encode(ordered("a", -0.0, "b", 0.1f, "c", 3.0f, "d", Float.NEGATIVE_INFINITY)));
		assertEquals("a: 127\nb: -32768\n", Soml.encode(ordered("a", (byte) 127, "b", Short.MIN_VALUE)));
		assertEquals("Cannot encode the integer 9223372036854775808, because it is outside the 64-bit range of an int", assertThrows(SomlException.class, () -> Soml.encode(Map.of("a", BigInteger.TWO.pow(63)))).reason());
		assertEquals("Cannot encode NaN, which is not representable. Use null for a missing value", assertThrows(SomlException.class, () -> Soml.encode(Map.of("a", Float.NaN))).reason());
	}

	@Test
	void writesTimes() {
		assertEquals("a: 2026-09-19T14:00:00.000000001Z\nb: 2026-09-19T12:00:00Z\nc: -1h0.5s\n", Soml.encode(ordered(
			"a", Instant.parse("2026-09-19T14:00:00.000000001Z"),
			"b", ZonedDateTime.of(2026, 9, 19, 14, 0, 0, 0, ZoneId.of("Europe/Oslo")),
			"c", Duration.ofMillis(-3_600_500)
		)));
		assertEquals("Cannot encode the duration PT2562047H47M16.854775808S, because it is outside the 64-bit range of nanoseconds, about 292 years either way", assertThrows(SomlException.class, () -> Soml.encode(Map.of("a", SomlDuration.MAX.plusNanos(1)))).reason());
		assertEquals(List.of("a"), assertThrows(SomlException.class, () -> Soml.encode(Map.of("a", Instant.EPOCH.minusSeconds(100_000_000_000L)))).path());
	}

	@Test
	void writesStrings() {
		assertEquals("a: 'x'\nb: \"it's\"\n", Soml.encode(ordered("a", 'x', "b", "it's")));
		var error = assertThrows(SomlException.class, () -> Soml.encode(Map.of("a", List.of("ok", "line\r\n"))));
		assertEquals("Cannot encode the string \"line\\r\\n\": A string cannot contain a carriage return (U+000D), which is not representable", error.reason());
		assertEquals(List.of("a", 1), error.path());
		assertEquals("A key cannot contain a carriage return (U+000D), which is not representable", assertThrows(SomlException.class, () -> Soml.encode(Map.of("a\r", 1))).reason());
	}

	@Test
	void writesCanonicalFormOfMaps() {
		var map = new TreeMap<String, Object>(java.util.Comparator.reverseOrder());
		map.put("a", 1);
		map.put("b", ordered("d", 1, "c", 2));
		assertEquals("b: {\n\td: 1\n\tc: 2\n}\na: 1\n", Soml.encode(map));
		assertEquals("a: 1\nb: {\n\tc: 2\n\td: 1\n}\n", Soml.encodeCanonical(map));
	}

	@Test
	void refusesScalarsAndUnsupportedTypes() {
		assertEquals("The top-level value must be an object or an array, because a document is a collection. Got an int", assertThrows(SomlException.class, () -> Soml.encode(5)).reason());
		assertEquals("The top-level value must be an object or an array, because a document is a collection. Got null", assertThrows(SomlException.class, () -> Soml.encode(null)).reason());
		assertEquals("Cannot encode Thread, because it is a JDK class that SOML has no type for. Register a SomlAdapter for it", assertThrows(SomlException.class, () -> Soml.encode(Map.of("a", Thread.currentThread()))).reason());
	}

	private static Map<String, Object> ordered(Object... keysAndValues) {
		var map = new LinkedHashMap<String, Object>();

		for (var index = 0; index < keysAndValues.length; index += 2) {
			map.put((String) keysAndValues[index], keysAndValues[index + 1]);
		}

		return map;
	}
}
