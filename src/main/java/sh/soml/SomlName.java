package sh.soml;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
The key of a record component or a field, the string of an enum constant, or the name of a subtype of a sealed type, in place of its Java name.

<pre>{@code
record Server(@SomlName("max-connections") int maxConnections) {}

enum Level {
	@SomlName("debug") DEBUG,
	@SomlName("info") INFO
}

sealed interface Shape {}
@SomlName("circle") record Circle(double radius) implements Shape {}
}</pre>

<p>It takes precedence over the {@link KeyNaming} of the mapper.
*/
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.TYPE})
public @interface SomlName {
	/**
	The key, the string, or the name.
	*/
	String value();
}
