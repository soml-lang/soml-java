package sh.soml.bench;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.toml.TomlMapper;

/*
Encoding the records as a document that people read: pretty-printed JSON, and SOML and TOML in their usual layout. A member that is left out is not written, which is the default of SOML and Gson, and which TOML needs, because it has no null.
*/
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class EncodeBenchmark {
	@Param({"service", "earthquakes"})
	public String fixture;

	private SomlMapper somlMapper;
	private ObjectWriter jackson;
	private TomlMapper jacksonToml;
	private Gson gson;
	private Object value;
	private Object textValue;

	@Setup
	public void setUp() {
		var isService = fixture.equals("service");
		var naming = isService ? PropertyNamingStrategies.KEBAB_CASE : PropertyNamingStrategies.LOWER_CAMEL_CASE;
		somlMapper = SomlMapper.builder().keyNaming(isService ? KeyNaming.KEBAB_CASE : KeyNaming.JAVA_NAME).build();
		var jsonMapper = JsonMapper.builder().propertyNamingStrategy(naming).changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL)).build();
		jackson = jsonMapper.writerWithDefaultPrettyPrinter();
		jacksonToml = TomlMapper.builder().propertyNamingStrategy(naming).changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL)).build();
		gson = new GsonBuilder().setFieldNamingPolicy(isService ? FieldNamingPolicy.LOWER_CASE_WITH_DASHES : FieldNamingPolicy.IDENTITY).setPrettyPrinting().create();
		Class<?> type = isService ? Fixtures.Service.class : Fixtures.Earthquakes.class;
		Class<?> textType = isService ? Fixtures.TextService.class : Fixtures.TextEarthquakes.class;
		value = somlMapper.decode(Fixtures.read(fixture + ".soml"), type);
		textValue = jsonMapper.readValue(Fixtures.read(fixture + ".json"), textType);
	}

	@Benchmark
	public Object soml() {
		return somlMapper.encode(value);
	}

	@Benchmark
	public Object jacksonJson() {
		return jackson.writeValueAsString(textValue);
	}

	@Benchmark
	public Object gson() {
		return gson.toJson(textValue);
	}

	@Benchmark
	public Object jacksonToml() {
		return jacksonToml.writeValueAsString(textValue);
	}
}
