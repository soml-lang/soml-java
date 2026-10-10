package sh.soml.bench;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import sh.soml.Soml;
import sh.soml.SomlArray;
import sh.soml.SomlDocument;
import sh.soml.SomlException;
import sh.soml.SomlFloat;
import sh.soml.SomlInt;
import sh.soml.SomlObject;
import sh.soml.SomlValue;

/*
Allocation and throughput of value operations and output paths that the fixture benchmarks do not isolate. Return every result so JMH consumes it.
*/
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ValueBenchmark {
	@Param({"8", "128", "1024"})
	public int size;

	private SomlObject sorted;
	private SomlObject reversed;
	private Map<String, SomlValue> reversedMembers;
	private SomlObject sameOrder;
	private SomlValue last;
	private SomlArray integers;
	private SomlArray floats;
	private List<Instant> instants;
	private List<Duration> durations;
	private String blockString;
	private String dottedKey;
	private String inlineBlockDelimiter;
	private String whitespaceHeavyBlockString;
	private int[] javaArray;
	private double[] doubleArray;
	private float[] floatArray;
	private List<Integer> javaList;
	private Configuration configuration;
	private String integerDocument;
	private String invalidIntegerDocument;
	private String instantDocument;
	private String invalidNumberDocument;
	private String quotedKeyDocument;
	private SomlDocument document;
	private SomlDocument commentedDocument;
	private String commentedSource;

	public record Configuration(String name, List<Integer> ports, boolean enabled) {}

	@Setup
	public void setUp() {
		var members = new LinkedHashMap<String, SomlValue>();
		var numbers = new ArrayList<SomlValue>();
		instants = new ArrayList<>();
		durations = new ArrayList<>();
		var text = new StringBuilder("value:\n    '''\n");

		for (var index = 0; index < size; index++) {
			var number = new SomlInt(123_456_789L + index);
			members.put("key" + (10_000 + index), number);
			numbers.add(number);
			instants.add(Instant.ofEpochSecond(1_700_000_000L + index, 123_456_789));
			durations.add(Duration.ofSeconds(123_456 + index, 123_456_789));
			text.append("    line ").append(index).append('\n');
		}

		sorted = new SomlObject(members);
		sameOrder = new SomlObject(members);
		var entries = new ArrayList<>(members.entrySet());
		Collections.reverse(entries);
		members.clear();

		for (var entry : entries) {
			members.put(entry.getKey(), entry.getValue());
		}

		reversed = new SomlObject(members);
		reversedMembers = members;
		last = numbers.getLast();
		// Use parsed storage so the collection benchmarks exercise the parser's immutable list.
		integers = (SomlArray) Soml.parse(Soml.encode(numbers));
		blockString = text.append("    '''\n").toString();
		dottedKey = "key.".repeat(Math.min(size, 128)) + "last: 1";
		inlineBlockDelimiter = "# preceding line\n".repeat(size) + "value: '''\ncontent '''";
		whitespaceHeavyBlockString = "value: '''\nX\n" + " \t".repeat(size * 100) + "\nY\n'''";
		javaArray = new int[size];
		doubleArray = new double[size];
		floatArray = new float[size];
		javaList = new ArrayList<>(size);
		var floatValues = new ArrayList<SomlValue>(size);
		var quotedKeys = new StringBuilder();

		for (var index = 0; index < size; index++) {
			javaArray[index] = 123_456_789 + index;
			javaList.add(javaArray[index]);
			doubleArray[index] = switch (index % 4) {
				case 0 -> 1e20 + index;
				case 1 -> 1e-7 * (index + 1);
				case 2 -> Double.MIN_VALUE * (index + 1);
				default -> 0.1 * index;
			};
			floatArray[index] = 0.1f * index;
			floatValues.add(new SomlFloat(doubleArray[index]));
			quotedKeys.append("'key ").append(index).append("': ").append(index).append('\n');
		}

		floats = new SomlArray(floatValues);
		quotedKeyDocument = quotedKeys.toString();
		configuration = new Configuration("api", javaList, true);

		integerDocument = Soml.encode(javaArray);
		instantDocument = Soml.encode(instants);
		invalidNumberDocument = "[" + "1".repeat(Math.min(size, 990)) + "+1]";
		invalidIntegerDocument = "[" + "1, ".repeat(size) + "'invalid']";
		document = SomlDocument.parse(Soml.encode(sorted));
		var commented = new StringBuilder();

		for (var index = 0; index < size; index++) {
			commented.append("# preceding comment ").append(index).append("\nkey").append(index).append(": [ /* inner comment */ 1, 2 ] # trailing comment\n\n");
		}

		commentedSource = commented.toString();
		commentedDocument = SomlDocument.parse(commentedSource);
	}

	@Benchmark
	public String canonicalSorted() {
		return Soml.encodeCanonical(sorted);
	}

	@Benchmark
	public String canonicalReversed() {
		return Soml.encodeCanonical(reversed);
	}

	@Benchmark
	public String canonicalMap() {
		return Soml.encodeCanonical(reversedMembers);
	}

	@Benchmark
	public SomlValue parseBlockString() {
		return Soml.parse(blockString);
	}

	@Benchmark
	public String dottedKeyDiagnostic() {
		try {
			Soml.parse(dottedKey);
			throw new IllegalStateException("The benchmark input must fail parsing");
		} catch (SomlException error) {
			return error.reason();
		}
	}

	@Benchmark
	public String inlineBlockDelimiterDiagnostic() {
		try {
			Soml.parse(inlineBlockDelimiter);
			throw new IllegalStateException("The benchmark input must fail parsing");
		} catch (SomlException error) {
			return error.reason();
		}
	}

	@Benchmark
	public String arrayText() {
		return integers.toString();
	}

	@Benchmark
	public String integers() {
		return Soml.encode(integers);
	}

	@Benchmark
	public SomlValue parseInstants() {
		return Soml.parse(instantDocument);
	}

	@Benchmark
	public String numberDiagnostic() {
		try {
			Soml.parse(invalidNumberDocument);
			throw new IllegalStateException("The benchmark input must fail parsing");
		} catch (SomlException error) {
			return error.reason();
		}
	}

	@Benchmark
	public String instants() {
		return Soml.encode(instants);
	}

	@Benchmark
	public String durations() {
		return Soml.encode(durations);
	}

	@Benchmark
	public boolean equalObjects() {
		return sorted.equals(reversed);
	}

	@Benchmark
	public boolean equalOrderedObjects() {
		return sorted.equals(sameOrder);
	}

	@Benchmark
	public int hashItems() {
		return integers.items().hashCode();
	}

	@Benchmark
	public String floatTree() {
		return Soml.encode(floats);
	}

	@Benchmark
	public String doubleArray() {
		return Soml.encode(doubleArray);
	}

	@Benchmark
	public String floatArray() {
		return Soml.encode(floatArray);
	}

	@Benchmark
	public SomlValue doubleArrayToValue() {
		return Soml.toValue(doubleArray);
	}

	@Benchmark
	public String formatQuotedKeys() {
		return Soml.format(quotedKeyDocument);
	}

	@Benchmark
	public String decodeMismatch() {
		try {
			Soml.decode(invalidIntegerDocument, int[].class);
			throw new IllegalStateException("The benchmark input must fail decoding");
		} catch (SomlException error) {
			return error.getMessage();
		}
	}

	@Benchmark
	public boolean containsValue() {
		return sorted.members().containsValue(last);
	}

	@Benchmark
	public Object[] itemsToArray() {
		return integers.items().toArray();
	}

	@Benchmark
	public SomlValue[] typedItemsToArray() {
		return integers.items().toArray(SomlValue[]::new);
	}

	@Benchmark
	public int itemIndex() {
		return integers.items().indexOf(last);
	}

	@Benchmark
	public int lastItemIndex() {
		return integers.items().lastIndexOf(last);
	}

	@Benchmark
	public Iterator<SomlValue> itemsIterator() {
		return integers.items().iterator();
	}

	@Benchmark
	public int iterateItems() {
		var hash = 0;

		for (var item : integers.items()) {
			hash += item.hashCode();
		}

		return hash;
	}

	@Benchmark
	public SomlValue parseWhitespaceHeavyBlockString() {
		return Soml.parse(whitespaceHeavyBlockString);
	}

	@Benchmark
	public String formatBlockString() {
		return Soml.format(blockString);
	}

	@Benchmark
	public SomlValue arrayToValue() {
		return Soml.toValue(javaArray);
	}

	@Benchmark
	public SomlValue collectionToValue() {
		return Soml.toValue(javaList);
	}

	@Benchmark
	public SomlValue recordToValue() {
		return Soml.toValue(configuration);
	}

	@Benchmark
	public int[] decodeIntegers() {
		return Soml.decode(integerDocument, int[].class);
	}

	@Benchmark
	public SomlValue editCommentedDocument() {
		commentedDocument.set(List.of("key" + (size / 2), 1), 3);
		return commentedDocument.value();
	}

	@Benchmark
	public String formatCommentedDocument() {
		return Soml.format(commentedSource);
	}

	@Benchmark
	public SomlValue editDocument() {
		document.set(List.of("key10000"), 1);
		return document.value();
	}
}
