package sh.soml.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
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
import org.tomlj.TomlParseResult;
import sh.soml.Soml;
import sh.soml.SomlValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.toml.TomlMapper;

/*
Writing each library's tree of generic values, as read from a fixture, back as a document for people: SOML in its usual layout, JSON pretty-printed, and TOML.
*/
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class WriteBenchmark {
	private static final ObjectWriter JACKSON = JsonMapper.builder().build().writerWithDefaultPrettyPrinter();
	private static final TomlMapper JACKSON_TOML = TomlMapper.builder().build();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	@Param({"service", "platform", "earthquakes"})
	public String fixture;

	private SomlValue soml;
	private JsonNode json;
	private JsonElement gsonTree;
	private JsonNode toml;
	private TomlParseResult tomlj;

	@Setup
	public void setUp() {
		soml = Soml.parse(Fixtures.read(fixture + ".soml"));
		json = JsonMapper.builder().build().readTree(Fixtures.read(fixture + ".json"));
		gsonTree = JsonParser.parseString(Fixtures.read(fixture + ".json"));
		toml = JACKSON_TOML.readTree(Fixtures.read(fixture + ".toml"));
		tomlj = Toml.parse(Fixtures.read(fixture + ".toml"));
	}

	@Benchmark
	public Object soml() {
		return Soml.encode(soml);
	}

	@Benchmark
	public Object jacksonJson() {
		return JACKSON.writeValueAsString(json);
	}

	@Benchmark
	public Object gson() {
		return GSON.toJson(gsonTree);
	}

	@Benchmark
	public Object jacksonToml() {
		return JACKSON_TOML.writeValueAsString(toml);
	}

	@Benchmark
	public Object tomlj() {
		return tomlj.toToml();
	}
}
