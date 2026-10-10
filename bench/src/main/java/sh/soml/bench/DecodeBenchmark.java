package sh.soml.bench;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import sh.soml.KeyNaming;
import sh.soml.SomlMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.toml.TomlMapper;

/*
Decoding a document into records.
*/
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DecodeBenchmark {
	@Param({"service", "earthquakes"})
	public String fixture;

	private SomlMapper somlMapper;
	private JsonMapper jackson;
	private TomlMapper jacksonToml;
	private Gson gson;
	private Class<?> type;
	private Class<?> textType;
	private String soml;
	private String json;
	private String toml;

	@Setup
	public void setUp() {
		var isService = fixture.equals("service");
		var naming = isService ? PropertyNamingStrategies.KEBAB_CASE : PropertyNamingStrategies.LOWER_CAMEL_CASE;
		somlMapper = SomlMapper.builder().keyNaming(isService ? KeyNaming.KEBAB_CASE : KeyNaming.JAVA_NAME).build();
		jackson = JsonMapper.builder().propertyNamingStrategy(naming).build();
		jacksonToml = TomlMapper.builder().propertyNamingStrategy(naming).build();
		gson = new GsonBuilder().setFieldNamingPolicy(isService ? FieldNamingPolicy.LOWER_CASE_WITH_DASHES : FieldNamingPolicy.IDENTITY).create();
		type = isService ? Fixtures.Service.class : Fixtures.Earthquakes.class;
		textType = isService ? Fixtures.TextService.class : Fixtures.TextEarthquakes.class;
		soml = Fixtures.read(fixture + ".soml");
		json = Fixtures.read(fixture + ".json");
		toml = Fixtures.read(fixture + ".toml");
	}

	@Benchmark
	public Object soml() {
		return somlMapper.decode(soml, type);
	}

	@Benchmark
	public Object jacksonJson() {
		return jackson.readValue(json, textType);
	}

	@Benchmark
	public Object gson() {
		return gson.fromJson(json, textType);
	}

	@Benchmark
	public Object jacksonToml() {
		return jacksonToml.readValue(toml, textType);
	}
}
