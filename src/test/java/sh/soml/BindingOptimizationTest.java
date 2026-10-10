package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BindingOptimizationTest {
	sealed interface Diamond permits Left1, Right1 {}
	sealed interface Left1 extends Diamond permits Left2, Right2 {}
	sealed interface Right1 extends Diamond permits Left2, Right2 {}
	sealed interface Left2 extends Left1, Right1 permits Left3, Right3 {}
	sealed interface Right2 extends Left1, Right1 permits Left3, Right3 {}
	sealed interface Left3 extends Left2, Right2 permits Left4, Right4 {}
	sealed interface Right3 extends Left2, Right2 permits Left4, Right4 {}
	sealed interface Left4 extends Left3, Right3 permits Left5, Right5 {}
	sealed interface Right4 extends Left3, Right3 permits Left5, Right5 {}
	sealed interface Left5 extends Left4, Right4 permits Left6, Right6 {}
	sealed interface Right5 extends Left4, Right4 permits Left6, Right6 {}
	sealed interface Left6 extends Left5, Right5 permits Left7, Right7 {}
	sealed interface Right6 extends Left5, Right5 permits Left7, Right7 {}
	sealed interface Left7 extends Left6, Right6 permits Left8, Right8 {}
	sealed interface Right7 extends Left6, Right6 permits Left8, Right8 {}
	sealed interface Left8 extends Left7, Right7 permits Left9, Right9 {}
	sealed interface Right8 extends Left7, Right7 permits Left9, Right9 {}
	sealed interface Left9 extends Left8, Right8 permits Left10, Right10 {}
	sealed interface Right9 extends Left8, Right8 permits Left10, Right10 {}
	sealed interface Left10 extends Left9, Right9 permits DiamondLeaf {}
	sealed interface Right10 extends Left9, Right9 permits DiamondLeaf {}
	record DiamondLeaf(int value) implements Left10, Right10 {}

	@Test
	void sharedSealedAncestorsKeepTheirSubtypeSelection() {
		assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
			var mapper = SomlMapper.builder().build();
			assertEquals(new DiamondLeaf(7), mapper.decode("type: 'DiamondLeaf'\nvalue: 7", Diamond.class));
		});
	}

	enum HiddenKey { HIDDEN }

	@Test
	void omittedMapMembersDoNotEncodeTheirKeys() {
		var mapper = SomlMapper.builder().adapter(HiddenKey.class, SomlAdapter.of(_ -> {
			throw new SomlException("This key must not be encoded");
		}, _ -> HiddenKey.HIDDEN)).build();
		var value = Map.of(HiddenKey.HIDDEN, Optional.empty());
		assertEquals("{}\n", mapper.encode(value));
		assertEquals("{}\n", mapper.encodeCanonical(value));
		assertEquals(SomlObject.ofTrusted(new String[0], new SomlValue[0]), mapper.toValue(value));
	}

	enum EscapedName {
		@SomlName("line\n'\t😀")
		VALUE
	}

	@Test
	void cachedEnumFormattingMatchesItsValueTreeAndAdaptersStillWin() {
		var value = Map.of("items", List.of(EscapedName.VALUE, EscapedName.VALUE));
		assertEquals(Soml.encode(Soml.toValue(value)), Soml.encode(value));
		assertEquals(Soml.encodeCanonical(Soml.toValue(value)), Soml.encodeCanonical(value));
		var mapper = SomlMapper.builder().adapter(EscapedName.class, SomlAdapter.of(_ -> new SomlString("replacement"), _ -> EscapedName.VALUE)).build();
		assertEquals("items: [\n\t'replacement'\n\t'replacement'\n]\n", mapper.encode(value));
	}

	record ErrorPair(int first, int second) {}

	@Test
	void deeplyGroupedErrorsKeepDocumentOrderAtTheGlobalLimit() {
		var type = new TypeReference<Map<String, List<List<ErrorPair>>>>() {};
		var text = "items: [[" + "{first: 'bad', second: 'bad'},".repeat(7) + "]]";
		var error = assertThrows(SomlException.class, () -> Soml.decode(text, type));
		assertEquals(10, error.errors().size());

		for (var index = 0; index < error.errors().size(); index++) {
			assertEquals(List.of("items", 0, index / 2, index % 2 == 0 ? "first" : "second"), error.errors().get(index).path());
		}

		var treeError = assertThrows(SomlException.class, () -> Soml.fromValue(Soml.parse(text), type));
		assertEquals(error.errors().stream().map(SomlException::path).toList(), treeError.errors().stream().map(SomlException::path).toList());
	}

	@Test
	void sizedFactoriesKeepConcreteContainersMutableAndInterfaceContainersUnmodifiable() {
		var array = Soml.parse("[1, 2, 3]");
		var list = Soml.fromValue(array, new TypeReference<ArrayList<Integer>>() {});
		list.add(4);
		assertEquals(List.of(1, 2, 3, 4), list);
		var set = Soml.fromValue(array, new TypeReference<LinkedHashSet<Integer>>() {});
		set.add(4);
		assertEquals(List.of(1, 2, 3, 4), new ArrayList<>(set));
		var object = Soml.parse("z: 1\na: 2");
		var map = Soml.fromValue(object, new TypeReference<LinkedHashMap<String, Integer>>() {});
		map.put("last", 3);
		assertEquals(List.of("z", "a", "last"), new ArrayList<>(map.keySet()));
		var unordered = Soml.fromValue(object, new TypeReference<HashMap<String, Integer>>() {});
		unordered.put("last", 3);
		assertEquals(3, unordered.size());
		var immutable = Soml.fromValue(array, new TypeReference<List<Integer>>() {});
		assertThrows(UnsupportedOperationException.class, () -> immutable.add(4));
	}

	record PresentNull(String text, Optional<Integer> optional, int number) {}

	@Test
	void presentNullArgumentsStayDistinctFromMissingAndInvalidArguments() {
		assertEquals(new PresentNull(null, Optional.empty(), 1), Soml.decode("text: null\nnumber: 1", PresentNull.class));
		var missing = assertThrows(SomlException.class, () -> Soml.decode("number: 1", PresentNull.class));
		assertEquals(1, missing.errors().size());
		var invalid = assertThrows(SomlException.class, () -> Soml.decode("text: null\nnumber: 'bad'", PresentNull.class));
		assertEquals(1, invalid.errors().size());
		assertEquals(List.of("number"), invalid.path());
	}

	@Test
	void primitiveArrayTreesMatchScalarConversionAcrossRandomBitPatterns() {
		var random = new Random(582099);
		var integers = new long[512];
		var doubles = new double[512];
		var floats = new float[512];

		for (var index = 0; index < integers.length; index++) {
			integers[index] = random.nextLong();
			doubles[index] = Double.longBitsToDouble(random.nextLong());
			floats[index] = Float.intBitsToFloat(random.nextInt());

			if (Double.isNaN(doubles[index])) {
				doubles[index] = Double.NEGATIVE_INFINITY;
			}

			if (Float.isNaN(floats[index])) {
				floats[index] = Float.POSITIVE_INFINITY;
			}
		}

		assertEquals(new SomlArray(Arrays.stream(integers).mapToObj(Soml::toValue).toList()), Soml.toValue(integers));
		assertEquals(new SomlArray(Arrays.stream(doubles).mapToObj(Soml::toValue).toList()), Soml.toValue(doubles));
		var floatItems = new SomlValue[floats.length];

		for (var index = 0; index < floats.length; index++) {
			floatItems[index] = Soml.toValue(floats[index]);
		}

		assertEquals(SomlArray.of(floatItems), Soml.toValue(floats));
		assertEquals(List.of("values", 1), assertThrows(SomlException.class, () -> Soml.toValue(Map.of("values", new double[] {1, Double.NaN}))).path());
	}
}
