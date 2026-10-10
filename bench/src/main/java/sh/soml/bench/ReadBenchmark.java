package sh.soml.bench;

import com.google.gson.JsonParser;
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
import org.tomlj.Toml;
import sh.soml.Soml;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.toml.TomlMapper;

/*
Parsing a document into each library's tree of generic values.
*/
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ReadBenchmark {
	private static final JsonMapper JACKSON = JsonMapper.builder().build();
	private static final TomlMapper JACKSON_TOML = TomlMapper.builder().build();

	@Param({"service", "platform", "earthquakes"})
	public String fixture;

	private String soml;
	private String json;
	private String toml;

	@Setup
	public void setUp() {
		soml = Fixtures.read(fixture + ".soml");
		json = Fixtures.read(fixture + ".json");
		toml = Fixtures.read(fixture + ".toml");
	}

	@Benchmark
	public Object soml() {
		return Soml.parse(soml);
	}

	@Benchmark
	public Object jacksonJson() {
		return JACKSON.readTree(json);
	}

	@Benchmark
	public Object gson() {
		return JsonParser.parseString(json);
	}

	@Benchmark
	public Object jacksonToml() {
		return JACKSON_TOML.readTree(toml);
	}

	@Benchmark
	public Object tomlj() {
		var result = Toml.parse(toml);

		if (result.hasErrors()) {
			throw new IllegalStateException(result.errors().toString());
		}

		return result;
	}
}
