package sh.soml;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;

/**
A generic type to decode to, such as {@code List<Server>}, which a {@link Class} cannot express. Create a direct subclass with a concrete type argument:

<pre>{@code
List<Server> servers = Soml.decode(text, new TypeReference<List<Server>>() {});
}</pre>

@param <T> The type.
*/
public abstract class TypeReference<T> {
	private static final ClassValue<Type> TYPES = new ClassValue<>() {
		@Override
		protected Type computeValue(Class<?> type) {
			return capture(type);
		}
	};

	private final Type type;

	/**
	Captures the type argument of the subclass.

	@throws IllegalStateException When the subclass is indirect, does not give a type argument, or its type contains a type variable, such as the {@code T} of a generic method, which is not known at run time.
	*/
	protected TypeReference() {
		this.type = TYPES.get(getClass());
	}

	private static Type capture(Class<?> subclass) {
		if (!(subclass.getGenericSuperclass() instanceof ParameterizedType superclass) || superclass.getRawType() != TypeReference.class) {
			throw new IllegalStateException("Create a direct TypeReference subclass with a type argument, as in new TypeReference<List<String>>() {}");
		}

		var type = superclass.getActualTypeArguments()[0];
		checkType(type);
		return type;
	}

	/*
	Rejects unresolved variables anywhere in the captured type, before decoding can silently use their erasure.
	*/
	private static void checkType(Type type) {
		// Note: Not using `Arrays.stream().forEach()` because direct loops traverse arguments and bounds without stream pipelines during recursive validation.
		switch (type) {
			case TypeVariable<?> variable -> throw new IllegalStateException("The type argument " + variable.getName() + " of a TypeReference is a type variable, which is not known at run time. Pass the TypeReference from where the type is known");
			case ParameterizedType parameterized -> {
				for (var argument : parameterized.getActualTypeArguments()) {
					checkType(argument);
				}

				if (parameterized.getOwnerType() instanceof Type owner) {
					checkType(owner);
				}
			}
			case GenericArrayType array -> checkType(array.getGenericComponentType());
			case WildcardType wildcard -> {
				for (var bound : wildcard.getUpperBounds()) {
					checkType(bound);
				}

				for (var bound : wildcard.getLowerBounds()) {
					checkType(bound);
				}
			}
			default -> {
			}
		}
	}

	/**
	The captured type.
	*/
	public final Type type() {
		return type;
	}

	@Override
	public String toString() {
		return type.getTypeName();
	}
}
