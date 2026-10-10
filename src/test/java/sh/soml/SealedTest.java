package sh.soml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SealedTest {
	sealed interface Shape permits Circle, Polygon, Group {}

	record Circle(double radius) implements Shape {}

	sealed interface Polygon extends Shape permits Square, Triangle {}

	@SomlName("square")
	record Square(double side) implements Polygon {}

	static final class Triangle implements Polygon {
		double base;
		double height;

		@Override
		public boolean equals(Object other) {
			return other instanceof Triangle triangle && triangle.base == base && triangle.height == height;
		}

		@Override
		public int hashCode() {
			return Objects.hash(base, height);
		}
	}

	record Group(String name, List<Shape> shapes) implements Shape {}

	record Drawing(Shape main, List<Shape> shapes, Map<String, Shape> named, Optional<Shape> optional, Shape[] array) {}

	private static Triangle triangle(double base, double height) {
		var triangle = new Triangle();
		triangle.base = base;
		triangle.height = height;
		return triangle;
	}

	@Test
	void encodesAndDecodesSubtypes() {
		var drawing = new Drawing(
			new Group("g", List.of(new Circle(1), new Square(2))),
			List.of(triangle(3, 4)),
			Map.of("c", new Circle(5)),
			Optional.of(new Square(6)),
			new Shape[] {new Circle(7)}
		);

		var text = Soml.encode(drawing);
		assertEquals("""
			main: {
				type: 'Group'
				name: 'g'
				shapes: [
					{
						type: 'Circle'
						radius: 1.0
					}
					{
						type: 'square'
						side: 2.0
					}
				]
			}
			shapes: [
				{
					type: 'Triangle'
					base: 3.0
					height: 4.0
				}
			]
			named: {
				c: {
					type: 'Circle'
					radius: 5.0
				}
			}
			optional: {
				type: 'square'
				side: 6.0
			}
			array: [
				{
					type: 'Circle'
					radius: 7.0
				}
			]
			""", text);

		var decoded = Soml.decode(text, Drawing.class);
		assertEquals(drawing.main(), decoded.main());
		assertEquals(drawing.shapes(), decoded.shapes());
		assertEquals(drawing.named(), decoded.named());
		assertEquals(drawing.optional(), decoded.optional());
		assertArrayEquals(drawing.array(), decoded.array());

		// The encoder writes the same text as the value tree.
		assertEquals(Soml.encode(Soml.toValue(drawing)), text);
		assertEquals(Soml.encodeCanonical(Soml.toValue(drawing)), Soml.encodeCanonical(drawing));
		assertEquals(Soml.toValue(drawing), Soml.parse(text));
	}

	@Test
	void writesTheNameOfATopLevelSubtype() {
		assertEquals("type: 'Circle'\nradius: 1.0\n", Soml.encode(new Circle(1)));
		assertEquals("radius: 1.0\ntype: 'Circle'\n", Soml.encodeCanonical(new Circle(1)));
		assertEquals(new Circle(1), Soml.decode("type: 'Circle'\nradius: 1.0\n", Shape.class));
	}

	@Test
	void decodesTheNameAnywhereInTheObject() {
		assertEquals(new Square(2), Soml.decode("side: 2.0\ntype: 'square'\n", Shape.class));
		assertEquals(new Square(2), Soml.decode("side: 2.0\ntype: 'square'\n", Polygon.class));
		assertEquals(new Square(2), SomlMapper.builder().rejectUnknownKeys(true).build().decode("side: 2.0\ntype: 'square'\n", Shape.class));
	}

	@Test
	void decodesASubtypeWithOrWithoutItsName() {
		assertEquals(new Circle(1), Soml.decode("radius: 1.0\n", Circle.class));
		assertEquals(new Circle(1), SomlMapper.builder().rejectUnknownKeys(true).build().decode("type: 'Circle'\nradius: 1.0\n", Circle.class));

		var error = assertThrows(SomlException.class, () -> Soml.decode("type: 'square'\nradius: 1.0\n", Circle.class));
		assertEquals("Expected 'Circle' (Circle), but found the string 'square'", error.reason());
		assertEquals(List.of("type"), error.path());
		assertEquals(1, error.line());
		assertEquals(7, error.column());
	}

	@Test
	void reportsWrongNames() {
		var missing = assertThrows(SomlException.class, () -> Soml.decode("main: {radius: 1.0}\nshapes: []\nnamed: {}\narray: []\n", Drawing.class));
		assertEquals("Missing the member type, which names the kind of Shape: one of 'Circle', 'square', 'Triangle', 'Group'", missing.reason());
		assertEquals(List.of("main"), missing.path());

		var unknown = assertThrows(SomlException.class, () -> Soml.decode("shapes: [{type: 'Hexagon'}]\n", new TypeReference<Map<String, List<Shape>>>() {}));
		assertEquals("Expected one of 'Circle', 'square', 'Triangle', 'Group' (Shape), but found the string 'Hexagon'", unknown.reason());
		assertEquals(List.of("shapes", 0, "type"), unknown.path());
		assertEquals(17, unknown.column());

		assertEquals("Expected one of 'Circle', 'square', 'Triangle', 'Group' (Shape), but found the string 'circle'. The names are case-sensitive, so write 'Circle'", assertThrows(SomlException.class, () -> Soml.decode("type: 'circle'\n", Shape.class)).reason());
		assertEquals("Cannot decode Animal, because its subtype Kind is not a record or a final class, so it is not known what to create. Make Kind a record or a final class", assertThrows(SomlException.class, () -> Soml.decode("a: ['DOG']\n", new TypeReference<Map<String, List<Animal>>>() {})).reason());

		var notString = assertThrows(SomlException.class, () -> Soml.decode("type: 1\n", Shape.class));
		assertEquals("Expected one of 'Circle', 'square', 'Triangle', 'Group' (Shape), but found the int 1", notString.reason());
		assertEquals("Expected an object, but found an array", assertThrows(SomlException.class, () -> Soml.decode("a: [[]]\n", new TypeReference<Map<String, List<Shape>>>() {})).reason());
	}

	sealed interface Open permits Closed, Ajar {}

	record Closed() implements Open {}

	non-sealed interface Ajar extends Open {}

	sealed interface Animal permits Cat, Kind {}

	record Cat() implements Animal {}

	enum Kind implements Animal {
		DOG
	}

	sealed interface Named permits First, Second {}

	@SomlName("same")
	record First() implements Named {}

	@SomlName("same")
	record Second() implements Named {}

	sealed interface Result<T> permits Ok {}

	sealed interface Container permits Dictionary {}

	static final class Dictionary extends java.util.LinkedHashMap<String, Integer> implements Container {
		private static final long serialVersionUID = 1L;
	}

	@Test
	void refusesContainerSubtypesOfSealedTypes() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("type: 'Dictionary'\nx: 1", Container.class));
		assertEquals("Cannot decode Container, because its subtype Dictionary is a map or collection. Wrap it in a record, or register a SomlAdapter for Container", error.reason());
	}

	record Ok<T>(T value) implements Result<T> {}

	record Typed(String type) implements Named2 {}

	sealed interface Named2 permits Typed {}

	@Test
	void refusesSealedTypesItCannotDecode() {
		assertEquals("Cannot decode Open, because its subtype Ajar is not a record or a final class, so it is not known what to create. Make Ajar a record or a final class", assertThrows(SomlException.class, () -> Soml.decode("type: 'Closed'\n", Open.class)).reason());
		assertEquals("Cannot decode Animal, because its subtype Kind is not a record or a final class, so it is not known what to create. Make Kind a record or a final class", assertThrows(SomlException.class, () -> Soml.decode("type: 'Cat'\n", Animal.class)).reason());
		assertEquals("Cannot decode Named, because two of its subtypes are both named 'same'. Give one of them another name with @SomlName", assertThrows(SomlException.class, () -> Soml.decode("type: 'same'\n", Named.class)).reason());
		assertEquals("Cannot decode Result, because a generic sealed type is not supported. Decode one of its subtypes, or register a SomlAdapter", assertThrows(SomlException.class, () -> Soml.decode("type: 'Ok'\nvalue: 1\n", Result.class)).reason());
		assertEquals(new Ok<>("a"), Soml.decode("type: 'Ok'\nvalue: 'a'\n", new TypeReference<Ok<String>>() {}));
		assertEquals("Cannot use Typed, because it is a subtype of a sealed type, whose member type names the subtype, so Typed cannot have a member with that key. Give it another key with @SomlName", assertThrows(SomlException.class, () -> Soml.encode(new Typed("x"))).reason());
	}

	sealed interface Top permits Middle, Bottom {}

	sealed interface Middle extends Top permits Bottom {}

	record Bottom(int number) implements Top, Middle {}

	@Test
	void decodesASubtypeThatIsReachedTwice() {
		assertEquals(new Bottom(1), Soml.decode("type: 'Bottom'\nnumber: 1\n", Top.class));
	}

	abstract static sealed class Base permits Leaf {}

	static final class Leaf extends Base {
		int size;
	}

	static sealed class Concrete permits Special {
		int size;
	}

	static final class Special extends Concrete {}

	record HttpCheck(int timeoutSeconds) implements Named3 {}

	sealed interface Named3 permits HttpCheck {}

	record Pets(List<Animal> pets) {}

	@Test
	void handlesAbstractAndConcreteSealedClasses() {
		var leaf = new Leaf();
		leaf.size = 2;
		assertEquals("type: 'Leaf'\nsize: 2\n", Soml.encode(leaf));
		assertEquals(2, ((Leaf) Soml.decode("size: 2\ntype: 'Leaf'\n", Base.class)).size);

		// A sealed class that is not abstract is decoded as itself.
		assertEquals(Concrete.class, Soml.decode("type: 'Special'\nsize: 3\n", Concrete.class).getClass());
	}

	@Test
	void keepsNamesWithKeyNaming() {
		var mapper = SomlMapper.builder().keyNaming(KeyNaming.KEBAB_CASE).build();
		assertEquals("type: 'HttpCheck'\ntimeout-seconds: 5\n", mapper.encode(new HttpCheck(5)));
		assertEquals(new HttpCheck(5), mapper.decode("type: 'HttpCheck'\ntimeout-seconds: 5\n", Named3.class));
	}

	@Test
	void encodesAnEnumOfASealedInterface() {
		assertEquals("pets: [\n\t'DOG'\n\t{\n\t\ttype: 'Cat'\n\t}\n]\n", Soml.encode(new Pets(List.of(Kind.DOG, new Cat()))));
	}

	sealed interface Badly permits BadName, GoodName {}

	@SomlName("a\rb")
	record BadName(int x) implements Badly {}

	record GoodName(int y) implements Badly {}

	@Test
	void refusesNamesThatAreNotValidStrings() {
		var message = "A string cannot contain a carriage return (U+000D), which is not representable";
		assertEquals(message, assertThrows(SomlException.class, () -> Soml.encode(new BadName(1))).reason());
		assertEquals(message, assertThrows(SomlException.class, () -> Soml.decode("type: 'GoodName'\ny: 1\n", Badly.class)).reason());
	}
}
