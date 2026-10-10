package sh.soml.bench;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.openjdk.jmh.annotations.Param;

/*
Thread CPU time per operation for the JMH benchmark methods, which load on the machine changes much less than wall time, for the table in the readme. The libraries alternate in each round, and the median of the rounds is reported.

	java -cp bench/target/benchmarks.jar sh.soml.bench.CpuTime [Benchmark...]
*/
public class CpuTime {
	// Make every result observable so the compiler cannot discard benchmark work.
	private static volatile Object result;

	public static void main(String[] arguments) throws Throwable {
		var threads = ManagementFactory.getThreadMXBean();

		if (!threads.isCurrentThreadCpuTimeSupported()) {
			throw new IllegalStateException("This JVM does not support current-thread CPU time");
		}

		if (!threads.isThreadCpuTimeEnabled()) {
			threads.setThreadCpuTimeEnabled(true);
		}

		var benchmarks = arguments.length > 0 ? List.of(arguments) : List.of("ReadBenchmark", "WriteBenchmark", "DecodeBenchmark", "EncodeBenchmark");
		var libraries = List.of("soml", "jacksonJson", "gson", "jacksonToml", "tomlj");

		for (var benchmarkName : benchmarks) {
			var type = Class.forName("sh.soml.bench." + benchmarkName);

			for (var fixture : type.getField("fixture").getAnnotation(Param.class).value()) {
				var benchmark = type.getConstructor().newInstance();
				type.getField("fixture").set(benchmark, fixture);
				type.getMethod("setUp").invoke(benchmark);
				var results = new LinkedHashMap<String, List<Double>>();
				var methods = new LinkedHashMap<String, MethodHandle>();

				for (var library : libraries) {
					try {
						methods.put(library, MethodHandles.lookup().unreflect(type.getMethod(library)).bindTo(benchmark).asType(MethodType.methodType(Object.class)));
					} catch (NoSuchMethodException _) {
						// Not every benchmark has every library.
					}
				}

				var iterationsByLibrary = new LinkedHashMap<String, Integer>();

				for (var entry : methods.entrySet()) {
					var end = System.nanoTime() + 2_000_000_000L;

					var count = 0L;
					var warmupStart = threads.getCurrentThreadCpuTime();

					while (System.nanoTime() < end) {
						result = (Object) entry.getValue().invokeExact();
						count++;
					}

					// A batch of about 20 ms of this library, well above the resolution of the CPU clock.
					var each = (threads.getCurrentThreadCpuTime() - warmupStart) / count;
					iterationsByLibrary.put(entry.getKey(), (int) Math.max(1, 20_000_000L / Math.max(1, each)));
					results.put(entry.getKey(), new ArrayList<>());
				}

				var entries = List.copyOf(methods.entrySet());

				for (var round = 0; round < 15; round++) {
					for (var position = 0; position < entries.size(); position++) {
						var entry = entries.get((round + position) % entries.size());
						var start = threads.getCurrentThreadCpuTime();

						var iterations = iterationsByLibrary.get(entry.getKey());

						for (var iteration = 0; iteration < iterations; iteration++) {
							result = (Object) entry.getValue().invokeExact();
						}

						results.get(entry.getKey()).add((threads.getCurrentThreadCpuTime() - start) / 1000.0 / iterations);
					}
				}

				var line = new StringBuilder(benchmarkName.replace("Benchmark", "") + " " + fixture + ":");

				for (var entry : results.entrySet()) {
					var values = entry.getValue();
					values.sort(Double::compare);
					line.append("  ").append(entry.getKey()).append(' ').append(String.format(Locale.ROOT, "%.1f", values.get(values.size() / 2))).append(" µs");
				}

				System.out.println(line);
			}
		}
	}
}
