package sh.soml;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
Leaves a field of a class out of decoding and encoding, as {@code transient} does. A record component cannot be left out, because the record's constructor needs it.
*/
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SomlIgnore {}
