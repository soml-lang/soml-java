package sh.soml;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.SequencedCollection;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import org.jspecify.annotations.Nullable;

/*
Converts between Java objects and `SomlValue` trees, with one codec per type, made on first use and cached.

Decoding follows the declared type, such as the type of a record component, because it decides what to make. Encoding follows the runtime class of each value, because it is what there is to write.

An error is a `BindingError`, which collects the path as it unwinds, so the common path pays nothing for it, and which `SomlMapper` turns into a `SomlException` with a position. The decode of a value tree, which reports the errors, decodes every member and item of a container, also after one that does not fit, and reports their errors together, up to a limit.
*/
final class Binding {
	// What a codec returns for a missing member that it has no value for.
	private static final Object REQUIRED = new Object();

	private final KeyNaming keyNaming;
	private final boolean rejectsUnknownKeys;
	private final Map<Class<?>, SomlAdapter<?>> adapters;
	// Whether any adapter is registered, which may be for a type that the encoder otherwise writes directly, such as `String`.
	private final boolean hasAdapters;
	private final ConcurrentHashMap<Type, Codec> codecs = new ConcurrentHashMap<>();

	Binding(KeyNaming keyNaming, boolean rejectsUnknownKeys, Map<Class<?>, SomlAdapter<?>> adapters) {
		this.keyNaming = keyNaming;
		this.rejectsUnknownKeys = rejectsUnknownKeys;
		this.adapters = Map.copyOf(adapters);
		this.hasAdapters = !adapters.isEmpty();
	}

	@Nullable Object decode(SomlValue value, Type type) {
		return decodeValue(codecFor(type), value);
	}

	SomlValue encode(@Nullable Object value) {
		return encodeValue(value, 1);
	}

	/*
	An error while decoding or encoding, with the keys and indexes from the top to the value it is about, which each level adds as the error passes. `isAtKey` is for an error about a key rather than its value, such as an unknown key. `isSetup` is for an error about the Java types rather than the document, such as a class without a constructor to create it with, which is a mistake in the program, so it is reported alone. It has no stack trace, because it is turned into a `SomlException`, which has one.
	*/
	static final class BindingError extends RuntimeException {
		private static final long serialVersionUID = 1L;
		// The most errors that one decode reports. Decoding stops at the next one, so that a document with an error in every item takes no longer than one with a few, and each is located in the text, which takes time in proportion to its length.
		static final int MAX_ERRORS = 10;

		final transient Deque<Object> path = new ArrayDeque<>(1);
		// The message, as `getMessage()` returns it, which is never null.
		final String reason;
		final boolean isAtKey;
		final boolean isSetup;
		// The errors after this one in other members and items of the same document, and whether decoding stopped with more of them left.
		transient @Nullable ArrayList<BindingError> others;
		boolean isStopped;

		private BindingError(String reason, boolean isAtKey, @Nullable Throwable cause, boolean isSetup) {
			super(reason, cause, false, false);
			this.reason = reason;
			this.isAtKey = isAtKey;
			this.isSetup = isSetup;
		}

		BindingError(String reason, boolean isAtKey, @Nullable Throwable cause) {
			this(reason, isAtKey, cause, false);
		}

		BindingError(String reason) {
			this(reason, false, null);
		}

		static BindingError setup(String reason, boolean isAtKey, @Nullable Throwable cause) {
			return new BindingError(reason, isAtKey, cause, true);
		}

		static BindingError setup(String reason) {
			return setup(reason, false, null);
		}

		BindingError at(Object component) {
			path.addFirst(component);

			if (others != null) {
				for (var other : others) {
					other.path.addFirst(component);
				}
			}

			return this;
		}

		/*
		Adds `error`, and the errors it holds, after `errors`, for a container that decodes all its members or items before it reports what does not fit. Throws a setup error alone, and the errors when there are more than the limit.
		*/
		static BindingError add(@Nullable BindingError errors, BindingError error) {
			if (error.isSetup) {
				throw error;
			}

			if (errors == null) {
				errors = error;
			} else {
				if (errors.others == null) {
					errors.others = new ArrayList<>();
				}

				errors.append(error);

				if (error.others != null) {
					for (var other : error.others) {
						errors.append(other);
					}
				}

				errors.isStopped |= error.isStopped;
			}

			if (errors.isStopped) {
				throw errors;
			}

			return errors;
		}

		/*
		This error and the ones after it are collected directly, without flattening them into an intermediate list.
		*/
		private void append(BindingError error) {
			var others = Objects.requireNonNull(this.others);

			if (others.size() + 1 < MAX_ERRORS) {
				others.add(error);
			} else {
				isStopped = true;
			}
		}

	}

	private interface Codec {
		/*
		Decodes a value that is not null.
		*/
		Object decode(SomlValue value);

		default @Nullable Object decodeNull() {
			return null;
		}

		/*
		The value of a member that is left out, or `REQUIRED`.
		*/
		default Object missing() {
			return REQUIRED;
		}

		/*
		Encodes a value that is not null, as a container at `depth` if it is one.
		*/
		SomlValue encode(Object value, int depth);

		/*
		Writes a value that is not null as text, as a container at `depth` with its lines after the first indented by `indent` tabs. A scalar is written through its value, and a container overrides this to write its members or items without making a value tree.
		*/
		default void write(Object value, TextEncoder encoder, int depth, int indent) {
			encoder.tree(encode(value, depth), depth, indent);
		}

		/*
		The frame that decodes an object, or an array when `isObject` is false, from events. A codec that cannot decode a container this way collects it as a value tree, and decodes the tree, which also reports a container that does not fit.
		*/
		default Frame frame(boolean isObject) {
			return new TreeFrame(this, isObject);
		}
	}

	/*
	A codec of a type that is written as an object, which can write its members alone, for a document whose top-level object has no braces.
	*/
	private interface ObjectCodec extends Codec {
		/*
		Writes each member on its own line, indented by `indent` tabs, with values at `depth`, and returns how many it wrote.
		*/
		int writeMembers(Object value, TextEncoder encoder, int depth, int indent);

		@Override
		default void write(Object value, TextEncoder encoder, int depth, int indent) {
			checkDepth(depth);
			var output = encoder.output;
			var start = output.length();
			output.append("{\n");

			// Empty, or only with members that are left out, which is known only after reading them.
			if (writeMembers(value, encoder, depth + 1, indent + 1) == 0) {
				output.setLength(start);
				output.append("{}");
				return;
			}

			output.repeat('\t', indent).append('}');
		}
	}

	/*
	Writes Java objects as text directly, without a value tree, as `Writer` writes a tree. `encode()` and `encodeCanonical()` use it, and `toValue()` makes the tree.
	*/
	final class TextEncoder {
		final Writer.TextBuffer output;
		final boolean isCanonical;

		TextEncoder(Writer.TextBuffer output, boolean isCanonical) {
			this.output = output;
			this.isCanonical = isCanonical;
		}

		/*
		A whole document, ending with one line feed. A value that is not written as an object or an array goes through its tree, which reports what it is.
		*/
		void document(@Nullable Object value) {
			if (value == null) {
				Writer.writeDocumentInto(output, SomlNull.INSTANCE, isCanonical);
				return;
			}

			if (!hasAdapters && value instanceof SomlValue tree) {
				Writer.writeDocumentInto(output, tree, isCanonical);
				return;
			}

			var codec = codecOf(value);

			switch (codec) {
				case ObjectCodec objectCodec -> {
					// A brace-less object needs at least one entry, so an empty one keeps its braces.
					if (objectCodec.writeMembers(value, this, 2, 0) == 0) {
						output.setLength(0);
						output.append("{}\n");
					}
				}
				case ArrayCodec _, CollectionCodec _ -> {
					codec.write(value, this, 1, 0);
					output.append('\n');
				}
				default -> Writer.writeDocumentInto(output, encode(value), isCanonical);
			}
		}

		/*
		Writes a value tree, as a scalar that a codec encodes, or a `SomlValue` among Java objects.
		*/
		void tree(SomlValue tree, int depth, int indent) {
			Writer.writeValueInto(output, tree, depth, indent, isCanonical);
		}

		void string(String string) {
			try {
				output.appendString(string, "A string");
			} catch (IllegalArgumentException error) {
				throw invalidString(string, error);
			}
		}

		void character(char character) {
			if (character >= 0x20 && character != '\'' && character != 0x7F && !Character.isSurrogate(character)) {
				output.append('\'').append(character).append('\'');
			} else {
				string(Character.toString(character));
			}
		}

		/*
		Writes a value at `depth`, with the lines of a container after its first indented by `indent` tabs. The common scalars are written here directly, unless an adapter may be registered for them.
		*/
		void value(@Nullable Object value, int depth, int indent) {
			if (hasAdapters && value != null) {
				codecOf(value).write(value, this, depth, indent);
				return;
			}

			switch (value) {
				case null -> output.append("null");
				case String string -> string(string);
				case Integer number -> output.append(number.intValue());
				case Long number -> output.append(number.longValue());
				case Short number -> output.append(number.shortValue());
				case Byte number -> output.append(number.byteValue());
				case Double number -> SomlFloat.appendTo(output, FloatCodec.number(number.doubleValue()));
				case Float number -> SomlFloat.appendTo(output, FloatCodec.number(number.floatValue()));
				case Boolean bool -> output.append(bool.booleanValue());
				case SomlValue tree -> tree(tree, depth, indent);
				case Optional<?> optional -> value(optional.orElse(null), depth, indent);
				default -> codecOf(value).write(value, this, depth, indent);
			}
		}

		/*
		Writes an array item without reflective boxing for primitive arrays. Registered adapters still receive the boxed value, as for every other Java value.
		*/
		void arrayItem(Object array, int index, int depth, int indent) {
			if (hasAdapters) {
				value(Array.get(array, index), depth, indent);
				return;
			}

			switch (array) {
				case int[] items -> output.append(items[index]);
				case long[] items -> output.append(items[index]);
				case short[] items -> output.append(items[index]);
				case byte[] items -> output.append(items[index]);
				case boolean[] items -> output.append(items[index]);
				case char[] items -> character(items[index]);
				case double[] items -> SomlFloat.appendTo(output, FloatCodec.number(items[index]));
				case float[] items -> SomlFloat.appendTo(output, FloatCodec.number(items[index]));
				case Object[] items -> value(items[index], depth, indent);
				default -> throw new IllegalStateException("Unexpected array type: " + array.getClass());
			}
		}
	}

	/*
	A container being decoded from the parser's events: an object, which gets a key before each value, or an array. `next()` is the codec of the next value, and `accept()` takes it, decoded.
	*/
	private abstract static class Frame {
		@Nullable Frame parent;

		void key(String key) {
			throw new IllegalStateException("A key outside an object");
		}

		abstract Codec next();

		abstract void accept(@Nullable Object value);

		abstract @Nullable Object finish();
	}

	/*
	The codec of a value that is not decoded, as one of a key that no member has, and of a value that a tree frame collects as it is.
	*/
	private static final Codec SKIP = new MarkerCodec();
	private static final Codec TREE = new MarkerCodec();

	private static final class MarkerCodec implements Codec {
		@Override
		public Object decode(SomlValue value) {
			throw new IllegalStateException("A marker codec decodes nothing");
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			throw new IllegalStateException("A marker codec encodes nothing");
		}
	}

	/*
	A container that is not decoded.
	*/
	private static final class SkipFrame extends Frame {
		@Override
		void key(String key) {
			// Nothing is kept.
		}

		@Override
		Codec next() {
			return SKIP;
		}

		@Override
		void accept(@Nullable Object value) {
			// Nothing is kept.
		}

		@Override
		@Nullable Object finish() {
			return null;
		}
	}

	/*
	A container collected as a value tree, which `codec` decodes when it closes, or which is kept as it is when `codec` is null, inside another tree. The parser has checked its keys, so they are unique.
	*/
	private static final class TreeFrame extends Frame {
		private final @Nullable Codec codec;
		private final boolean isObject;
		private final List<String> keys;
		private final ArrayList<SomlValue> values = new ArrayList<>();

		TreeFrame(@Nullable Codec codec, boolean isObject) {
			this.codec = codec;
			this.isObject = isObject;
			this.keys = isObject ? new ArrayList<>() : List.of();
		}

		@Override
		void key(String key) {
			keys.add(key);
		}

		@Override
		Codec next() {
			return TREE;
		}

		@Override
		void accept(@Nullable Object value) {
			values.add((SomlValue) value);
		}

		@Override
		@Nullable Object finish() {
			var tree = isObject ? SomlObject.ofTrusted(keys.toArray(String[]::new), values.toArray(SomlValue[]::new)) : SomlArray.ofTrusted(values.toArray(SomlValue[]::new));
			return codec == null ? tree : decodeValue(codec, tree);
		}
	}

	/*
	Decodes a document from the parser's events into the type of `root`, without a value tree for the containers that the codecs decode themselves.
	*/
	private static final class StreamDecoder extends Frame implements Parser.Sink {
		private final Codec root;
		private Frame frame = this;
		private @Nullable Object result;

		StreamDecoder(Codec root) {
			this.root = root;
		}

		@Override
		Codec next() {
			return root;
		}

		@Override
		void accept(@Nullable Object value) {
			result = value;
		}

		@Override
		@Nullable Object finish() {
			return result;
		}

		private void start(boolean isObject) {
			var codec = frame.next();
			Frame child;

			if (codec == SKIP) {
				child = new SkipFrame();
			} else if (codec == TREE) {
				child = new TreeFrame(null, isObject);
			} else {
				child = codec.frame(isObject);
			}

			child.parent = frame;
			frame = child;
		}

		private void end() {
			var value = frame.finish();
			// The parser closes only the containers it opened, so the frame that ends is never the root, which has no parent.
			frame = Objects.requireNonNull(frame.parent);
			frame.accept(value);
		}

		@Override
		public void startObject() {
			start(true);
		}

		@Override
		public void key(String key) {
			frame.key(key);
		}

		@Override
		public void endObject() {
			end();
		}

		@Override
		public void startArray() {
			start(false);
		}

		@Override
		public void endArray() {
			end();
		}

		@Override
		public void scalar(SomlValue value) {
			var codec = frame.next();
			frame.accept(codec == SKIP || codec == TREE ? value : decodeValue(codec, value));
		}
	}

	/*
	Decodes a document while it is parsed, without a value tree.

	@throws BindingError When the document does not fit. Its path and position are not those of the error, because the document is not checked as a whole first, so the caller decodes again from a tree to report the error.
	@throws SomlException When the document is not valid, as `Parser.parse()`.
	*/
	@Nullable Object decodeWhileParsing(String text, Type type) {
		var decoder = new StreamDecoder(codecFor(type));
		Parser.parse(text, decoder);
		return decoder.result;
	}

	String encodeDocument(@Nullable Object value, boolean isCanonical) {
		try (var output = Writer.TextBuffer.reused()) {
			new TextEncoder(output, isCanonical).document(value);
			return output.toString();
		}
	}

	void encodeDocument(@Nullable Object value, java.io.Writer writer) throws IOException {
		try (var output = Writer.TextBuffer.reused()) {
			new TextEncoder(output, false).document(value);
			output.writeTo(writer);
		}
	}

	/*
	The codec of a value's class, for encoding. A constant with a body is an instance of its own anonymous class, so an enum uses its declaring class.
	*/
	private Codec codecOf(Object value) {
		return value instanceof Enum<?> constant ? codecFor(constant.getDeclaringClass()) : codecFor(value.getClass());
	}

	private static @Nullable Object decodeValue(Codec codec, SomlValue value) {
		return value instanceof SomlNull ? codec.decodeNull() : codec.decode(value);
	}

	private SomlValue encodeValue(@Nullable Object value, int depth) {
		if (hasAdapters && value != null) {
			return codecOf(value).encode(value, depth);
		}

		// `codecOf` also handles an enum constant with its own class body.
		return switch (value) {
			case null -> SomlNull.INSTANCE;
			case String string -> encodeString(string);
			case Integer number -> new SomlInt(number.intValue());
			case Long number -> new SomlInt(number.longValue());
			case Short number -> new SomlInt(number.shortValue());
			case Byte number -> new SomlInt(number.byteValue());
			case Double number -> new SomlFloat(FloatCodec.number(number.doubleValue()));
			case Float number -> new SomlFloat(FloatCodec.number(number.floatValue()));
			case Boolean bool -> SomlBool.of(bool.booleanValue());
			case SomlValue tree -> tree;
			case Optional<?> optional -> encodeValue(optional.orElse(null), depth);
			default -> codecOf(value).encode(value, depth);
		};
	}

	/*
	An empty `Optional`, which is left out as a member.
	*/
	private static boolean isAbsent(@Nullable Object value) {
		return switch (value) {
			case Optional<?> optional -> optional.isEmpty();
			case OptionalInt optional -> optional.isEmpty();
			case OptionalLong optional -> optional.isEmpty();
			case OptionalDouble optional -> optional.isEmpty();
			case null, default -> false;
		};
	}

	private static void checkDepth(int depth) {
		if (depth > Writer.MAX_DEPTH) {
			throw new BindingError("Cannot encode a value nested more than " + Writer.MAX_DEPTH + " levels deep. A value that contains itself is nested without end");
		}
	}

	private static BindingError mismatch(String expected, SomlValue found) {
		return mismatch(expected, found, "");
	}

	private static BindingError mismatch(String expected, SomlValue found, String hint) {
		return new BindingError("Expected " + expected + ", but found " + describeValue(found) + hint);
	}

	static String describeValue(SomlValue value) {
		return switch (value) {
			case SomlString(var string) -> "the string " + Writer.formatString(Parser.abbreviate(string, 40));
			case SomlInt(var number) -> "the int " + number;
			case SomlFloat float_ -> "the float " + float_;
			case SomlBool(var bool) -> String.valueOf(bool);
			case SomlNull _ -> "null";
			case SomlDate date -> "the date " + date;
			case SomlInstant instant -> "the instant " + instant;
			case SomlDuration duration -> "the duration " + duration;
			case SomlArray _ -> "an array";
			case SomlObject _ -> "an object";
		};
	}

	/*
	`one of` and the names as a document writes them, up to the first 10.
	*/
	private static String oneOf(Collection<String> names) {
		var formatted = new StringJoiner(", ", "one of ", names.size() > 10 ? ", …" : "");
		var count = 0;

		for (var name : names) {
			if (count++ == 10) {
				break;
			}

			formatted.add(Writer.formatString(name));
		}

		return formatted.toString();
	}

	/*
	The hint for a string that is one of `names` in another case, as `'debug'` for `'DEBUG'`, or an empty string.
	*/
	private static String caseHint(Collection<String> names, SomlValue value) {
		if (value instanceof SomlString(var string)) {
			for (var name : names) {
				if (name.equalsIgnoreCase(string)) {
					return ". The names are case-sensitive, so write " + Writer.formatString(name);
				}
			}
		}

		return "";
	}

	private static String typeName(Type type) {
		return type instanceof Class<?> type_ ? type_.getSimpleName() : type.getTypeName();
	}

	Codec codecFor(Type type) {
		// JDK scalars have no declared adapters, and their immutable codecs are shared across mappers.
		if (!hasAdapters && type instanceof Class<?> raw && isJdkType(raw)) {
			var scalar = scalarCodec(raw);

			if (scalar != null) {
				return scalar;
			}
		}

		var codec = codecs.get(type);

		// Note: Not using `ConcurrentHashMap.computeIfAbsent()` because creating a codec recursively creates codecs for its parts, and the mapping function must not modify the same map. Threads that race may each make a codec, and all of them use the one stored first.
		if (codec == null) {
			codec = create(type);
			codec = Objects.requireNonNullElse(codecs.putIfAbsent(type, codec), codec);
		}

		return codec;
	}

	private Codec create(Type type) {
		return switch (type) {
			case Class<?> type_ -> createForClass(type_, type_, Map.of());
			case ParameterizedType parameterized -> createForClass((Class<?>) parameterized.getRawType(), parameterized, typeBindings(parameterized));
			case GenericArrayType array -> {
				var componentType = array.getGenericComponentType();
				yield new ArrayCodec(erasure(componentType), codecFor(componentType));
			}
			// A `? super T` takes a `T`, so the lower bound is what to make when there is one, and otherwise the upper bound of `? extends T`.
			case WildcardType wildcard -> codecFor(wildcardBound(wildcard));
			// The erasure, not the bound itself, which can refer to the variable, as in `T extends List<T>`.
			case TypeVariable<?> variable -> codecFor(erasure(variable));
			default -> new UnsupportedCodec(type, "it is not a class or a parameterized type");
		};
	}

	/*
	The type argument at `index` that `type` gives `target`, found through its superclasses and interfaces, so that `class Servers extends ArrayList<Server>` has `Server` items. A type variable that nothing binds is left as it is, and its erasure is used.
	*/
	private static Type typeArgument(Type type, Class<?> target, int index) {
		var arguments = typeArguments(type, target);
		return arguments == null ? Object.class : arguments[index];
	}

	private static Type @Nullable [] typeArguments(Type type, Class<?> target) {
		var raw = erasure(type);

		if (raw == target) {
			if (type instanceof ParameterizedType parameterized) {
				return parameterized.getActualTypeArguments();
			}

			var arguments = new Type[target.getTypeParameters().length];
			Arrays.fill(arguments, Object.class);
			return arguments;
		}

		var bindings = type instanceof ParameterizedType parameterized ? typeBindings(parameterized) : Map.<TypeVariable<?>, Type>of();
		var superclass = raw.getGenericSuperclass();

		if (superclass != null && target.isAssignableFrom(erasure(superclass))) {
			return typeArguments(resolve(superclass, bindings), target);
		}

		for (var supertype : raw.getGenericInterfaces()) {
			if (target.isAssignableFrom(erasure(supertype))) {
				return typeArguments(resolve(supertype, bindings), target);
			}
		}

		return null;
	}

	/*
	A primitive's wrapper, or the reference type itself.
	*/
	static Class<?> boxedType(Class<?> type) {
		return type.isPrimitive() ? MethodType.methodType(type).wrap().returnType() : type;
	}

	private static boolean isJdkType(Class<?> type) {
		var name = type.getModule().getName();
		return name != null && (name.startsWith("java.") || name.startsWith("jdk."));
	}

	private Codec createForClass(Class<?> type, Type genericType, Map<TypeVariable<?>, Type> bindings) {
		var adapter = adapterCodec(type);

		if (adapter != null) {
			return adapter;
		}

		if (SomlValue.class.isAssignableFrom(type)) {
			return new ValueCodec(type);
		}

		var scalar = scalarCodec(type);

		if (scalar != null) {
			return scalar;
		}

		if (type.isEnum() || (type.getSuperclass() != null && type.getSuperclass().isEnum())) {
			return new EnumCodec(type.isEnum() ? type : type.getSuperclass());
		}

		if (type.isArray()) {
			return new ArrayCodec(type.getComponentType(), codecFor(type.getComponentType()));
		}

		if (type == Optional.class) {
			return new OptionalCodec(codecFor(typeArgument(genericType, Optional.class, 0)));
		}

		if (Map.class.isAssignableFrom(type)) {
			var arguments = Objects.requireNonNull(typeArguments(genericType, Map.class));
			return new MapCodec(type, keyDecoder(arguments[0]), arguments[1], mapFactory(type, arguments[0]));
		}

		if (Collection.class.isAssignableFrom(type) || type == Iterable.class) {
			var isSet = Set.class.isAssignableFrom(type);
			var elementType = typeArgument(genericType, Iterable.class, 0);
			return new CollectionCodec(type, elementType, collectionFactory(type, elementType), isSet);
		}

		if (type == Object.class) {
			return new UntypedCodec();
		}

		if (type.isRecord()) {
			return tagged(type, new RecordCodec(type, bindings));
		}

		if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
			if (type.isSealed()) {
				return new SealedCodec(type);
			}

			return new UnsupportedCodec(type, "it is " + (type.isInterface() ? "an interface" : "abstract") + ", so it is not known what to create. Decode to a concrete type, make it sealed, or register a SomlAdapter");
		}

		// The classes of the JDK keep their state private, and most cannot be decoded or encoded field by field.
		if (isJdkType(type)) {
			return new UnsupportedCodec(type, "it is a JDK class that SOML has no type for. Register a SomlAdapter for it");
		}

		return tagged(type, new ClassCodec(type, bindings));
	}

	/*
	The codec of the adapter for a class, which is the mapper's or the one that a type declares, or null when there is none. An adapter for an interface or an abstract class is for the classes that implement it too, because encoding follows the class of the value, such as `UnixPath` for a `Path`. An adapter of the mapper wins over the types' own, and otherwise the nearest type wins.
	*/
	private @Nullable Codec adapterCodec(Class<?> type) {
		// JDK types cannot declare their own adapters, so without registered adapters there is no hierarchy to search.
		if (!hasAdapters && isJdkType(type)) {
			return null;
		}

		// Registered adapters take precedence even when an overridden declared adapter is invalid.
		var registered = hasAdapters ? findAdapter(type, adapters::get) : null;
		return registered != null ? registered : findAdapter(type, Binding::ownAdapter);
	}

	/*
	The nearest adapter in the type hierarchy, visiting each ancestor only once even through diamond-shaped interfaces.
	*/
	private @Nullable Codec findAdapter(Class<?> type, Function<Class<?>, @Nullable SomlAdapter<?>> find) {
		// A primitive has no supertypes, so it takes the adapters of its wrapper's, as `Number` for `int`, which its values are encoded as.
		var start = boxedType(type);
		var exact = find.apply(start);

		if (exact != null) {
			return new AdapterCodec(start, exact, type);
		}

		var pending = new ArrayDeque<Class<?>>();
		var visited = new HashSet<Class<?>>();
		pending.add(start);
		visited.add(start);

		while (!pending.isEmpty()) {
			var current = pending.removeFirst();

			if (current != start && (current.isInterface() || Modifier.isAbstract(current.getModifiers()))) {
				var adapter = find.apply(current);

				if (adapter != null) {
					return new AdapterCodec(current, adapter, type);
				}
			}

			var superclass = current.getSuperclass();

			if (superclass != null && visited.add(superclass)) {
				pending.add(superclass);
			}

			// Note: Not using `Collections.addAll()` because each ancestor must pass the visited-set check before being queued.
			for (var supertype : current.getInterfaces()) {
				if (visited.add(supertype)) {
					pending.add(supertype);
				}
			}
		}

		return null;
	}

	/*
	The adapter that a type declares for itself, as a static field of type `SomlAdapter<T>`, or null when it has none. The field is found by its type, not its name, so a misspelled name cannot make the type fall back to its built-in conversion without a word.
	*/
	private static @Nullable SomlAdapter<?> ownAdapter(Class<?> type) {
		// A class of the JDK has no such field, and is not read, so a native image needs no entry for it.
		if (isJdkType(type)) {
			return null;
		}

		Field found = null;

		for (var field : type.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers()) || field.getType() != SomlAdapter.class || erasure(typeArgument(field.getGenericType(), SomlAdapter.class, 0)) != type) {
				continue;
			}

			if (found != null) {
				throw BindingError.setup(type.getSimpleName() + " has two SomlAdapter fields, " + found.getName() + " and " + field.getName() + ". Keep one");
			}

			found = field;
		}

		if (found == null) {
			return null;
		}

		Object adapter;

		try {
			found.trySetAccessible();
			adapter = found.get(null);
		} catch (IllegalAccessException error) {
			throw inaccessible(type, error);
		}

		// A static initializer that reaches the type's codec before it sets the field.
		if (adapter == null) {
			throw BindingError.setup("The SomlAdapter field " + found.getName() + " of " + type.getSimpleName() + " is null");
		}

		return (SomlAdapter<?>) adapter;
	}

	/*
	The codec of a record or a class, with the member that names it when it is a subtype of a sealed type.
	*/
	private Codec tagged(Class<?> type, ObjectCodec codec) {
		var name = subtypeName(type);
		return name == null ? codec : new TaggedCodec(type, nameString(name), codec, rejectsUnknownKeys);
	}

	/*
	The codecs for the types that are one SOML scalar, or null for other types.
	*/
	private static final Map<Class<?>, Codec> SCALAR_CODECS = scalarCodecs();

	private static Map<Class<?>, Codec> scalarCodecs() {
		var codecs = new HashMap<Class<?>, Codec>();
		var string = new StringCodec();
		codecs.put(String.class, string);
		codecs.put(CharSequence.class, string);
		addScalar(codecs, boolean.class, new BoolCodec(), "a bool");
		addScalar(codecs, long.class, new IntegerCodec("long", Long.MIN_VALUE, Long.MAX_VALUE, number -> number), "an int");
		addScalar(codecs, int.class, new IntegerCodec("int", Integer.MIN_VALUE, Integer.MAX_VALUE, number -> (int) number), "an int");
		addScalar(codecs, short.class, new IntegerCodec("short", Short.MIN_VALUE, Short.MAX_VALUE, number -> (short) number), "an int");
		addScalar(codecs, byte.class, new IntegerCodec("byte", Byte.MIN_VALUE, Byte.MAX_VALUE, number -> (byte) number), "an int");
		addScalar(codecs, double.class, new FloatCodec(false), "a float");
		addScalar(codecs, float.class, new FloatCodec(true), "a float");
		addScalar(codecs, char.class, new CharacterCodec(), "a string of one character");
		codecs.put(BigInteger.class, new BigIntegerCodec());
		codecs.put(LocalDate.class, new DateCodec());
		codecs.put(Instant.class, new InstantCodec(Instant.class));
		codecs.put(OffsetDateTime.class, new InstantCodec(OffsetDateTime.class));
		codecs.put(ZonedDateTime.class, new InstantCodec(ZonedDateTime.class));
		codecs.put(Duration.class, new DurationCodec());
		codecs.put(UUID.class, new TextCodec("a UUID", UUID::fromString));
		codecs.put(URI.class, new TextCodec("a URI", URI::new));
		codecs.put(Path.class, new TextCodec("a path", Path::of));
		codecs.put(ZoneOffset.class, new TextCodec("an offset, such as '+02:00'", ZoneOffset::of));
		codecs.put(ZoneId.class, new TextCodec("a time zone, such as 'Europe/Oslo'", ZoneId::of));
		// Note: Not using `Locale.forLanguageTag()` or `Locale.toString()` because the former tolerates malformed tags and the latter writes `en_US` instead of the required `en-US`. The builder rejects malformed tags.
		codecs.put(Locale.class, new TextCodec("a language tag, such as 'en-US'", text -> new Locale.Builder().setLanguageTag(text).build(), locale -> ((Locale) locale).toLanguageTag()));
		codecs.put(OptionalInt.class, new PrimitiveOptionalCodec(Objects.requireNonNull(codecs.get(Integer.class)), OptionalInt.empty(), value -> OptionalInt.of((int) value)));
		codecs.put(OptionalLong.class, new PrimitiveOptionalCodec(Objects.requireNonNull(codecs.get(Long.class)), OptionalLong.empty(), value -> OptionalLong.of((long) value)));
		codecs.put(OptionalDouble.class, new PrimitiveOptionalCodec(Objects.requireNonNull(codecs.get(Double.class)), OptionalDouble.empty(), value -> OptionalDouble.of((double) value)));
		return Map.copyOf(codecs);
	}

	private static void addScalar(Map<Class<?>, Codec> codecs, Class<?> primitive, Codec scalar, String description) {
		codecs.put(primitive, new PrimitiveCodec(scalar, description));
		codecs.put(boxedType(primitive), scalar);
	}

	private record PrimitiveCodec(Codec scalar, String description) implements Codec {
		@Override
		public Object decode(SomlValue value) {
			return scalar.decode(value);
		}

		@Override
		public Object decodeNull() {
			throw mismatch(description, SomlNull.INSTANCE);
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return scalar.encode(value, depth);
		}

		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			scalar.write(value, encoder, depth, indent);
		}
	}

	private static @Nullable Codec scalarCodec(Class<?> type) {
		// A path and a time zone are an interface and an abstract class, whose values are instances of classes of the JDK, such as `UnixPath` or `ZoneRegion`. `ZoneOffset` is the one public subclass of `ZoneId`, which a time zone that is not an offset does not fit.
		if (Path.class.isAssignableFrom(type)) {
			return SCALAR_CODECS.get(Path.class);
		}

		if (type != ZoneOffset.class && ZoneId.class.isAssignableFrom(type)) {
			return SCALAR_CODECS.get(ZoneId.class);
		}

		return SCALAR_CODECS.get(type);
	}

	private static class StringCodec implements Codec {
		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			encoder.string(value.toString());
		}

		@Override
		public Object decode(SomlValue value) {
			if (value instanceof SomlString(var string)) {
				return string;
			}

			throw mismatch("a string", value);
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return encodeString(value.toString());
		}
	}

	private static SomlValue encodeString(String string) {
		try {
			return new SomlString(string);
		} catch (IllegalArgumentException error) {
			throw invalidString(string, error);
		}
	}

	private static BindingError invalidString(String string, IllegalArgumentException error) {
		return new BindingError("Cannot encode the string " + Parser.quoteJson(Parser.abbreviate(string, 40)) + ": " + error.getMessage(), false, error);
	}

	private static final class BoolCodec implements Codec {
		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			encoder.output.append((Boolean) value);
		}

		@Override
		public Object decode(SomlValue value) {
			if (value instanceof SomlBool(var bool)) {
				return bool;
			}

			throw mismatch("a bool", value);
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return SomlBool.of((Boolean) value);
		}
	}

	private record IntegerCodec(String javaType, long minimum, long maximum, LongFunction<Object> box) implements Codec {
		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			encoder.output.append(((Number) value).longValue());
		}

		@Override
		public Object decode(SomlValue value) {
			if (!(value instanceof SomlInt(var number))) {
				throw mismatch("an int", value);
			}

			if (number < minimum || number > maximum) {
				throw new BindingError("The int " + number + " does not fit the Java type " + javaType + ", which holds " + minimum + " to " + maximum);
			}

			return box.apply(number);
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return new SomlInt(((Number) value).longValue());
		}
	}

	/*
	A float type, which also takes an int that it holds exactly, as Java widens a long to a double.
	*/
	private record FloatCodec(boolean isSinglePrecision) implements Codec {
		@Override
		public Object decode(SomlValue value) {
			var javaType = isSinglePrecision ? "float" : "double";

			return switch (value) {
				case SomlFloat(var number) -> {
					if (!isSinglePrecision) {
						yield number;
					}

					var narrowed = (float) number;

					// Rounding to the nearest float is accepted, as a cast does. Only a finite value that becomes infinite and a nonzero value that becomes zero are errors.
					if ((Float.isInfinite(narrowed) && !Double.isInfinite(number)) || (narrowed == 0 && number != 0)) {
						throw new BindingError("The float " + value + " does not fit the Java type float");
					}

					yield narrowed;
				}
				case SomlInt(var number) -> {
					// A long that converts to a float or a double and back unchanged is exact. 2^63 converts back to Long.MAX_VALUE, so it is checked apart.
					var isExact = isSinglePrecision ? (long) (float) number == number && (float) number != 0x1p63f : (long) (double) number == number && (double) number != 0x1p63;

					if (!isExact) {
						throw new BindingError("The int " + number + " cannot be represented exactly by the Java type " + javaType + ". Write it as a float, such as " + number + ".0, if a rounded value is fine");
					}

					yield isSinglePrecision ? (Object) (float) number : (Object) (double) number;
				}
				default -> throw mismatch("a float", value);
			};
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return new SomlFloat(number(value));
		}

		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			SomlFloat.appendTo(encoder.output, number(value));
		}

		private static double number(Object value) {
			return value instanceof Float single ? number(single.floatValue()) : number(((Number) value).doubleValue());
		}

		private static double number(double number) {
			if (Double.isNaN(number)) {
				throw new BindingError("Cannot encode NaN, which is not representable. Use null for a missing value");
			}

			return number;
		}

		private static double number(float single) {
			var number = number((double) single);

			// Every integral float through 2^24 has the same exact decimal value as its widened double.
			if (single >= -0x1p24f && single <= 0x1p24f && single == (int) single) {
				return number;
			}

			// Note: Not using `Double.toString(single)` directly because it writes 0.1f as 0.10000000149011612. Use the float's shortest digits unless they read back as another float through a double.
			if (Float.isFinite(single)) {
				var shortest = Double.parseDouble(Float.toString(single));

				if ((float) shortest == single) {
					number = shortest;
				}
			}

			return number;
		}
	}

	private static final class CharacterCodec extends StringCodec {
		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			encoder.character((Character) value);
		}

		@Override
		public Object decode(SomlValue value) {
			// One UTF-16 code unit, because a `char` cannot hold a character above U+FFFF.
			if (value instanceof SomlString(var string) && string.length() == 1) {
				return string.charAt(0);
			}

			throw mismatch("a string of one character", value);
		}
	}

	private static final class BigIntegerCodec implements Codec {
		@Override
		public Object decode(SomlValue value) {
			if (value instanceof SomlInt(var number)) {
				return BigInteger.valueOf(number);
			}

			throw mismatch("an int", value);
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return new SomlInt(number((BigInteger) value));
		}

		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			encoder.output.append(number((BigInteger) value));
		}

		private static long number(BigInteger value) {
			if (value.bitLength() <= 63) {
				return value.longValue();
			}

			// A huge integer's decimal conversion can cost far more than rejecting the value.
			var description = value.bitLength() <= 128 ? value.toString() : "(more than 128 bits)";
			throw new BindingError("Cannot encode the integer " + description + ", because it is outside the 64-bit range of an int");
		}
	}

	private static final class DateCodec implements Codec {
		@Override
		public Object decode(SomlValue value) {
			if (value instanceof SomlDate(var date)) {
				return date;
			}

			throw mismatch("a date", value);
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return new SomlDate(date(value));
		}

		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			encoder.output.append(date(value).toString());
		}

		private static LocalDate date(Object value) {
			var date = (LocalDate) value;

			try {
				SomlDate.checkValue(date);
				return date;
			} catch (IllegalArgumentException error) {
				throw new BindingError("Cannot encode the date " + date + ", because it is outside the years 0001 to 9999", false, error);
			}
		}
	}

	private record InstantCodec(Class<?> type) implements Codec {
		@Override
		public Object decode(SomlValue value) {
			if (!(value instanceof SomlInstant(var instant))) {
				throw mismatch("an instant", value);
			}

			if (type == OffsetDateTime.class) {
				return instant.atOffset(ZoneOffset.UTC);
			}

			return type == ZonedDateTime.class ? instant.atZone(ZoneOffset.UTC) : instant;
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return new SomlInstant(instant(value));
		}

		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			SomlInstant.appendTo(encoder.output, instant(value));
		}

		private static Instant instant(Object value) {
			var instant = switch (value) {
				case OffsetDateTime dateTime -> dateTime.toInstant();
				case ZonedDateTime dateTime -> dateTime.toInstant();
				default -> (Instant) value;
			};

			try {
				SomlInstant.checkValue(instant);
				return instant;
			} catch (IllegalArgumentException error) {
				throw new BindingError("Cannot encode the instant " + instant + ", because it is outside the years 0001 to 9999", false, error);
			}
		}
	}

	private static final class DurationCodec implements Codec {
		@Override
		public Object decode(SomlValue value) {
			if (value instanceof SomlDuration(var duration)) {
				return duration;
			}

			// A number without a unit, as JSON and TOML write a duration.
			var hint = value instanceof SomlInt(var number) && number >= 0 ? ". A duration has a unit, as in " + number + "s or " + number + "ms" : "";
			throw mismatch("a duration", value, hint);
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return new SomlDuration(duration(value));
		}

		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			SomlDuration.appendTo(encoder.output, duration(value));
		}

		private static Duration duration(Object value) {
			var duration = (Duration) value;

			try {
				SomlDuration.checkValue(duration);
				return duration;
			} catch (IllegalArgumentException error) {
				throw new BindingError("Cannot encode the duration " + value + ", because it is outside the 64-bit range of nanoseconds, about 292 years either way", false, error);
			}
		}
	}

	/*
	A type that is written as a string and parsed from one, such as a UUID.
	*/
	private record TextCodec(String description, TextParser parser, Function<Object, String> formatter) implements Codec {
		@FunctionalInterface
		interface TextParser {
			Object parse(String text) throws Exception;
		}

		TextCodec(String description, TextParser parser) {
			this(description, parser, Object::toString);
		}

		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			encoder.string(formatter.apply(value));
		}

		@Override
		public Object decode(SomlValue value) {
			if (!(value instanceof SomlString(var string))) {
				throw mismatch("a string with " + description, value);
			}

			try {
				return parser.parse(string);
			} catch (Exception _) {
				// JDK parsers repeat raw input in their causes, which can contain secrets or terminal controls.
				throw new BindingError("Expected a string with " + description + ", but found " + describeValue(value));
			}
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return encodeString(formatter.apply(value));
		}
	}

	private record ValueCodec(Class<?> type) implements Codec {
		@Override
		public Object decode(SomlValue value) {
			if (type.isInstance(value)) {
				return value;
			}

			throw mismatch(describeValueType(type), value);
		}

		@Override
		public @Nullable Object decodeNull() {
			// A `SomlValue` or a `SomlNull` gets the null value itself, and any other value type, such as `SomlString`, gets Java null.
			return type.isInstance(SomlNull.INSTANCE) ? SomlNull.INSTANCE : null;
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return (SomlValue) value;
		}

		private static final Map<Class<?>, String> NAMES = Map.of(
			SomlString.class, "a string",
			SomlInt.class, "an int",
			SomlFloat.class, "a float",
			SomlBool.class, "a bool",
			SomlNull.class, "null",
			SomlDate.class, "a date",
			SomlInstant.class, "an instant",
			SomlDuration.class, "a duration",
			SomlArray.class, "an array",
			SomlObject.class, "an object"
		);

		private static String describeValueType(Class<?> type) {
			return NAMES.getOrDefault(type, "a value");
		}
	}

	/*
	An adapter that fails with another exception than a `SomlException` gets a path and a position too.
	*/
	private record AdapterCodec(Class<?> type, SomlAdapter<?> adapter, Class<?> target, Class<?> boxedTarget) implements Codec {
		AdapterCodec(Class<?> type, SomlAdapter<?> adapter, Class<?> target) {
			this(type, adapter, target, boxedType(target));
		}

		@Override
		public Object decode(SomlValue value) {
			Object decoded;

			try {
				decoded = adapter.decode(value);
			} catch (SomlException error) {
				// Only the reason is kept, because the path and the position are added as the error unwinds.
				throw new BindingError(error.reason(), false, error);
			} catch (RuntimeException error) {
				throw new BindingError("The SomlAdapter for " + type.getSimpleName() + " failed: " + describeCause(error), false, error);
			}

			// Null is what `SomlNull` decodes to, which an adapter is not given.
			if (decoded == null) {
				throw new BindingError("The SomlAdapter for " + type.getSimpleName() + " returned null. Return a value, or throw a SomlException");
			}

			// An adapter for an interface can return another class that implements it than the one asked for. A primitive is checked as its wrapper, which the value is.
			if (target != type && !boxedTarget.isInstance(decoded)) {
				throw new BindingError("Expected " + target.getSimpleName() + ", but the SomlAdapter for " + type.getSimpleName() + " returned " + decoded.getClass().getSimpleName());
			}

			return decoded;
		}

		@Override
		public @Nullable Object decodeNull() {
			// The adapter is not given `SomlNull`, and a primitive cannot hold the null that it decodes to.
			if (target.isPrimitive()) {
				throw mismatch(target.getName(), SomlNull.INSTANCE);
			}

			return null;
		}

		@Override
		@SuppressWarnings("unchecked")
		public SomlValue encode(Object value, int depth) {
			SomlValue encoded;

			try {
				encoded = ((SomlAdapter<Object>) adapter).encode(value);
			} catch (SomlException error) {
				throw new BindingError(error.reason(), false, error);
			} catch (RuntimeException error) {
				throw new BindingError("The SomlAdapter for " + type.getSimpleName() + " failed: " + describeCause(error), false, error);
			}

			if (encoded == null) {
				throw new BindingError("The SomlAdapter for " + type.getSimpleName() + " returned null. Return SomlNull.INSTANCE for null");
			}

			return encoded;
		}
	}

	/*
	An exception as a message shows it, also when it has no message of its own.
	*/
	private static String describeCause(Throwable error) {
		return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
	}

	private static final class UntypedCodec implements Codec {
		// A codec is not given a null, and only a null inside an array or an object decodes to null.
		@Override
		public Object decode(SomlValue value) {
			return Objects.requireNonNull(untyped(value));
		}

		private static @Nullable Object untyped(SomlValue value) {
			return switch (value) {
				case SomlString(var string) -> string;
				case SomlInt(var number) -> number;
				case SomlFloat(var number) -> number;
				case SomlBool(var bool) -> bool;
				case SomlNull _ -> null;
				case SomlDate(var date) -> date;
				case SomlInstant(var instant) -> instant;
				case SomlDuration(var duration) -> duration;
				case SomlArray(var items) -> {
					if (items.isEmpty()) {
						yield List.of();
					}

					var list = new ArrayList<>(items.size());

					for (var item : items) {
						list.add(untyped(item));
					}

					yield Collections.unmodifiableList(list);
				}
				case SomlObject object -> {
					var count = object.memberCount();

					if (count == 0) {
						yield Map.of();
					}

					var map = LinkedHashMap.newLinkedHashMap(count);

					for (var index = 0; index < count; index++) {
						map.put(object.keyAt(index), untyped(object.valueAt(index)));
					}

					yield Collections.unmodifiableMap(map);
				}
			};
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			throw new BindingError("Cannot encode an instance of Object, which has no members");
		}

		@Override
		public Frame frame(boolean isObject) {
			var codec = this;

			if (isObject) {
				var map = new LinkedHashMap<String, Object>();

				return new Frame() {
					private @Nullable String key;

					@Override
					void key(String memberKey) {
						key = memberKey;
					}

					@Override
					Codec next() {
						return codec;
					}

					@Override
					void accept(@Nullable Object value) {
						map.put(key, value);
					}

					@Override
					Object finish() {
						return map.isEmpty() ? Map.of() : Collections.unmodifiableMap(map);
					}
				};
			}

			var list = new ArrayList<Object>();

			return new Frame() {
				@Override
				Codec next() {
					return codec;
				}

				@Override
				void accept(@Nullable Object value) {
					list.add(value);
				}

				@Override
				Object finish() {
					return list.isEmpty() ? List.of() : Collections.unmodifiableList(list);
				}
			};
		}
	}

	private record UnsupportedCodec(Type type, String reason) implements Codec {
		@Override
		public Object decode(SomlValue value) {
			throw BindingError.setup("Cannot decode to " + typeName(type) + ", because " + reason);
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			throw new BindingError("Cannot encode " + typeName(type) + ", because " + reason);
		}
	}

	private static final class EnumCodec implements Codec {
		private final Map<String, Object> constantsByName;
		private final SomlString[] namesByConstant;
		private final String[] formattedNames;
		private final String expected;

		EnumCodec(Class<?> type) {
			var constants = type.getEnumConstants();
			constantsByName = LinkedHashMap.newLinkedHashMap(constants.length);
			namesByConstant = new SomlString[constants.length];
			formattedNames = new String[constants.length];

			for (var constant : constants) {
				var javaName = ((Enum<?>) constant).name();
				var name = javaName;

				try {
					var annotation = type.getField(javaName).getAnnotation(SomlName.class);

					if (annotation != null) {
						name = annotation.value();
					}
				} catch (NoSuchFieldException error) {
					throw new IllegalStateException(error);
				}

				if (constantsByName.put(name, constant) != null) {
					throw BindingError.setup("Cannot use " + type.getSimpleName() + ", because two of its constants are both named " + Writer.formatString(name) + ". Give one of them another name with @SomlName");
				}

				var ordinal = ((Enum<?>) constant).ordinal();
				namesByConstant[ordinal] = nameString(name);
				formattedNames[ordinal] = Writer.formatString(name);
			}

			expected = oneOf(constantsByName.keySet()) + " (" + type.getSimpleName() + ")";
		}

		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			encoder.output.append(formattedNames[((Enum<?>) value).ordinal()]);
		}

		@Override
		public Object decode(SomlValue value) {
			var constant = value instanceof SomlString(var string) ? constantsByName.get(string) : null;

			if (constant != null) {
				return constant;
			}

			throw mismatch(expected, value, caseHint(constantsByName.keySet(), value));
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			// The value is a constant of `type`, and every constant has a name.
			return namesByConstant[((Enum<?>) value).ordinal()];
		}
	}

	/*
	The key of the member that names the subtype of a sealed type.
	*/
	private static final String TYPE_KEY = "type";

	/*
	The name of a type whose direct supertype is sealed, which its `type` member has: its simple name, or the name that `@SomlName` gives it. Null for any other type.
	*/
	private static @Nullable String subtypeName(Class<?> type) {
		var superclass = type.getSuperclass();
		var isSubtype = superclass != null && superclass.isSealed();

		if (!isSubtype) {
			for (var supertype : type.getInterfaces()) {
				if (supertype.isSealed()) {
					isSubtype = true;
					break;
				}
			}
		}

		if (!isSubtype) {
			return null;
		}

		var annotation = type.getAnnotation(SomlName.class);
		return annotation != null ? annotation.value() : type.getSimpleName();
	}

	/*
	A record or a class that is a subtype of a sealed type. It is written with its `type` member first, so that the sealed type knows which subtype to make. Decoded as itself, it takes a `type` member that names it, and refuses one that names another subtype.
	*/
	private record TaggedCodec(Class<?> type, SomlString tag, ObjectCodec codec, boolean rejectsUnknownKeys) implements ObjectCodec {
		@Override
		public Object decode(SomlValue value) {
			if (!(value instanceof SomlObject object) || !(object.get(TYPE_KEY) instanceof SomlValue found)) {
				return codec.decode(value);
			}

			if (!tag.equals(found)) {
				throw mismatch(Writer.formatString(tag.value()) + " (" + type.getSimpleName() + ")", found).at(TYPE_KEY);
			}

			return codec.decode(rejectsUnknownKeys ? object.withoutMember(object.indexOfKey(TYPE_KEY)) : object);
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return ((SomlObject) codec.encode(value, depth)).withLeadingMember(TYPE_KEY, tag);
		}

		@Override
		public int writeMembers(Object value, TextEncoder encoder, int depth, int indent) {
			if (encoder.isCanonical) {
				var object = (SomlObject) encode(value, depth - 1);
				Writer.writeMembersInto(encoder.output, object, depth, indent);
				return object.memberCount();
			}

			encoder.output.repeat('\t', indent).append(TYPE_KEY).append(": ");
			encoder.tree(tag, depth, indent);
			encoder.output.append('\n');
			return 1 + codec.writeMembers(value, encoder, depth, indent);
		}

		@Override
		public Frame frame(boolean isObject) {
			if (!isObject) {
				return new TreeFrame(this, false);
			}

			var inner = codec.frame(true);

			return new Frame() {
				private boolean isTag;

				@Override
				void key(String key) {
					isTag = key.equals(TYPE_KEY);

					if (!isTag) {
						inner.key(key);
					}
				}

				@Override
				Codec next() {
					return isTag ? TREE : inner.next();
				}

				@Override
				void accept(@Nullable Object value) {
					if (!isTag) {
						inner.accept(value);
					} else if (!tag.equals(value)) {
						throw new BindingError("The type member does not name " + type.getSimpleName());
					}
				}

				@Override
				@Nullable Object finish() {
					return inner.finish();
				}
			};
		}
	}

	/*
	A sealed interface or abstract class, decoded as the subtype that its `type` member names. Its subtypes are records and final classes, found through the sealed types between them and it, and are found on first use, so that a subtype can contain the sealed type. Only these subtypes can be made, so a document cannot choose any other class.
	*/
	private final class SealedCodec implements Codec {
		private final Class<?> type;
		private volatile @Nullable Map<String, Codec> subtypes;

		SealedCodec(Class<?> type) {
			this.type = type;
		}

		private Map<String, Codec> subtypes() {
			var subtypes = this.subtypes;

			if (subtypes == null) {
				if (type.getTypeParameters().length > 0) {
					throw BindingError.setup("Cannot decode " + type.getSimpleName() + ", because a generic sealed type is not supported. Decode one of its subtypes, or register a SomlAdapter");
				}

				subtypes = new LinkedHashMap<>();
				collectSubtypes(type, subtypes, new HashSet<>());
				this.subtypes = subtypes;
			}

			return subtypes;
		}

		private void collectSubtypes(Class<?> sealed, Map<String, Codec> subtypes, Set<Class<?>> visited) {
			if (!visited.add(sealed)) {
				return;
			}

			for (var subtype : sealed.getPermittedSubclasses()) {
				var isAbstract = subtype.isInterface() || Modifier.isAbstract(subtype.getModifiers());

				if (subtype.isSealed() && isAbstract) {
					collectSubtypes(subtype, subtypes, visited);
					continue;
				}

				// A record is final, and an abstract type is not. An enum without constant bodies is final too, so it has its own check.
				if (subtype.isEnum() || !Modifier.isFinal(subtype.getModifiers())) {
					throw BindingError.setup("Cannot decode " + type.getSimpleName() + ", because its subtype " + subtype.getSimpleName() + " is not a record or a final class, so it is not known what to create. Make " + subtype.getSimpleName() + " a record or a final class");
				}

				if (Map.class.isAssignableFrom(subtype) || Collection.class.isAssignableFrom(subtype)) {
					throw BindingError.setup("Cannot decode " + type.getSimpleName() + ", because its subtype " + subtype.getSimpleName() + " is a map or collection. Wrap it in a record, or register a SomlAdapter for " + type.getSimpleName());
				}

				// A permitted subclass of a sealed type is a subtype, so it has a name.
				var name = Objects.requireNonNull(subtypeName(subtype));

				var codec = codecFor(subtype);
				var existing = subtypes.put(name, codec);

				// A subtype can be reached through more than one sealed type, and its codec is cached, so it is the same codec then.
				if (existing != null && existing != codec) {
					throw BindingError.setup("Cannot decode " + type.getSimpleName() + ", because two of its subtypes are both named " + Writer.formatString(name) + ". Give one of them another name with @SomlName");
				}
			}
		}

		@Override
		public Object decode(SomlValue value) {
			// A sealed type that cannot be decoded says so first, also for a value that is not an object.
			var subtypes = subtypes();

			if (!(value instanceof SomlObject object)) {
				throw mismatch("an object", value);
			}

			var found = object.get(TYPE_KEY);

			if (found == null) {
				throw new BindingError("Missing the member " + Parser.describeKey(TYPE_KEY) + ", which names the kind of " + type.getSimpleName() + ": " + oneOf(subtypes.keySet()));
			}

			var codec = found instanceof SomlString(var name) ? subtypes.get(name) : null;

			if (codec == null) {
				throw mismatch(oneOf(subtypes.keySet()) + " (" + type.getSimpleName() + ")", found, caseHint(subtypes.keySet(), found)).at(TYPE_KEY);
			}

			return codec.decode(object);
		}

		// The encoders use the codec of a value's own class, which writes its `type` member, so this is only for completeness.
		@Override
		public SomlValue encode(Object value, int depth) {
			return codecOf(value).encode(value, depth);
		}
	}

	private final class OptionalCodec implements Codec {
		private final Codec element;

		OptionalCodec(Codec element) {
			this.element = element;
		}

		@Override
		public Object decode(SomlValue value) {
			return Optional.of(element.decode(value));
		}

		@Override
		public Object decodeNull() {
			return Optional.empty();
		}

		@Override
		public Object missing() {
			return decodeNull();
		}

		/*
		An empty `Optional` member is left out before this, so here it is an item, which is written as null.
		*/
		@Override
		public SomlValue encode(Object value, int depth) {
			return encodeValue(((Optional<?>) value).orElse(null), depth);
		}

		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			encoder.value(((Optional<?>) value).orElse(null), depth, indent);
		}

		@Override
		public Frame frame(boolean isObject) {
			var inner = element.frame(isObject);

			return new Frame() {
				@Override
				void key(String key) {
					inner.key(key);
				}

				@Override
				Codec next() {
					return inner.next();
				}

				@Override
				void accept(@Nullable Object value) {
					inner.accept(value);
				}

				@Override
				Object finish() {
					return Optional.ofNullable(inner.finish());
				}
			};
		}
	}

	private record PrimitiveOptionalCodec(Codec element, Object empty, Function<Object, Object> wrap) implements Codec {
		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			switch (value) {
				case OptionalInt optional when optional.isPresent() -> encoder.output.append(optional.getAsInt());
				case OptionalLong optional when optional.isPresent() -> encoder.output.append(optional.getAsLong());
				case OptionalDouble optional when optional.isPresent() -> SomlFloat.appendTo(encoder.output, FloatCodec.number(optional.getAsDouble()));
				case OptionalInt _, OptionalLong _, OptionalDouble _ -> encoder.output.append("null");
				default -> throw new IllegalStateException("Unexpected value: " + value);
			}
		}

		@Override
		public Object decode(SomlValue value) {
			return wrap.apply(element.decode(value));
		}

		@Override
		public Object decodeNull() {
			return empty;
		}

		@Override
		public Object missing() {
			return decodeNull();
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return switch (value) {
				case OptionalInt optional -> optional.isPresent() ? new SomlInt(optional.getAsInt()) : SomlNull.INSTANCE;
				case OptionalLong optional -> optional.isPresent() ? new SomlInt(optional.getAsLong()) : SomlNull.INSTANCE;
				case OptionalDouble optional -> optional.isPresent() ? new SomlFloat(FloatCodec.number(optional.getAsDouble())) : SomlNull.INSTANCE;
				default -> throw new IllegalStateException("Unexpected value: " + value);
			};
		}
	}

	private final class ArrayCodec implements Codec {
		private final Class<?> componentType;
		private final Codec component;

		ArrayCodec(Class<?> componentType, Codec component) {
			this.componentType = componentType;
			this.component = component;
		}

		@Override
		public Object decode(SomlValue value) {
			if (!(value instanceof SomlArray(var items))) {
				throw mismatch("an array", value);
			}

			var array = Array.newInstance(componentType, items.size());
			BindingError errors = null;

			for (var index = 0; index < items.size(); index++) {
				try {
					setArrayItem(array, index, decodeValue(component, items.get(index)));
				} catch (BindingError error) {
					errors = BindingError.add(errors, error.at(index));
				}
			}

			if (errors != null) {
				throw errors;
			}

			return array;
		}

		@Override
		public Frame frame(boolean isObject) {
			if (isObject) {
				return new TreeFrame(this, true);
			}

			return new Frame() {
				private Object array = Array.newInstance(componentType, 0);
				private int size;

				@Override
				Codec next() {
					return component;
				}

				@Override
				void accept(@Nullable Object item) {
					if (size == Array.getLength(array)) {
						array = copyArray(array, Math.max(10, size * 2));
					}

					setArrayItem(array, size++, item);
				}

				@Override
				Object finish() {
					if (size == Array.getLength(array)) {
						return array;
					}

					return copyArray(array, size);
				}
			};
		}

		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			checkDepth(depth);
			var length = Array.getLength(value);
			var output = encoder.output;

			if (length == 0) {
				output.append("[]");
				return;
			}

			output.append("[\n");

			for (var index = 0; index < length; index++) {
				output.repeat('\t', indent + 1);

				try {
					encoder.arrayItem(value, index, depth + 1, indent + 1);
				} catch (BindingError error) {
					throw error.at(index);
				}

				output.append('\n');
			}

			output.repeat('\t', indent).append(']');
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			checkDepth(depth);
			var length = Array.getLength(value);
			var items = new SomlValue[length];
			for (var index = 0; index < length; index++) {
				try {
					items[index] = arrayItem(value, index, depth + 1);
				} catch (BindingError error) {
					throw error.at(index);
				}
			}

			return SomlArray.ofTrusted(items);
		}

		/*
		Copies a streaming buffer with its exact runtime array type, for growth and final trimming.
		*/
		private static Object copyArray(Object array, int length) {
			return switch (array) {
				case @Nullable Object[] items -> Arrays.copyOf(items, length);
				case int[] items -> Arrays.copyOf(items, length);
				case long[] items -> Arrays.copyOf(items, length);
				case short[] items -> Arrays.copyOf(items, length);
				case byte[] items -> Arrays.copyOf(items, length);
				case boolean[] items -> Arrays.copyOf(items, length);
				case char[] items -> Arrays.copyOf(items, length);
				case double[] items -> Arrays.copyOf(items, length);
				case float[] items -> Arrays.copyOf(items, length);
				default -> throw new IllegalStateException("Unexpected array type: " + array.getClass());
			};
		}

		/*
		Stores a decoded item without reflective assignment. Primitive codecs have already rejected null and returned the exact wrapper type.
		*/
		private static void setArrayItem(Object array, int index, @Nullable Object value) {
			switch (array) {
				case int[] items -> items[index] = (int) Objects.requireNonNull(value);
				case long[] items -> items[index] = (long) Objects.requireNonNull(value);
				case short[] items -> items[index] = (short) Objects.requireNonNull(value);
				case byte[] items -> items[index] = (byte) Objects.requireNonNull(value);
				case boolean[] items -> items[index] = (boolean) Objects.requireNonNull(value);
				case char[] items -> items[index] = (char) Objects.requireNonNull(value);
				case double[] items -> items[index] = (double) Objects.requireNonNull(value);
				case float[] items -> items[index] = (float) Objects.requireNonNull(value);
				case @Nullable Object[] items -> items[index] = value;
				default -> throw new IllegalStateException("Unexpected array type: " + array.getClass());
			}
		}

		/*
		Reads an array item directly, avoiding reflection and boxing for primitive arrays unless an adapter needs the boxed value.
		*/
		private SomlValue arrayItem(Object array, int index, int depth) {
			if (hasAdapters) {
				return encodeValue(Array.get(array, index), depth);
			}

			return switch (array) {
				case int[] items -> new SomlInt(items[index]);
				case long[] items -> new SomlInt(items[index]);
				case short[] items -> new SomlInt(items[index]);
				case byte[] items -> new SomlInt(items[index]);
				case boolean[] items -> SomlBool.of(items[index]);
				case char[] items -> encodeString(Character.toString(items[index]));
				case double[] items -> new SomlFloat(FloatCodec.number(items[index]));
				case float[] items -> new SomlFloat(FloatCodec.number(items[index]));
				case Object[] items -> encodeValue(items[index], depth);
				default -> throw new IllegalStateException("Unexpected array type: " + array.getClass());
			};
		}
	}

	private final class CollectionCodec implements Codec {
		private final Class<?> type;
		// Resolved on use, after this codec is cached, so a collection can contain its own type.
		private final Type elementType;
		private volatile @Nullable Codec elementCodec;
		private final @Nullable IntFunction<Collection<Object>> factory;
		private final boolean isSet;

		CollectionCodec(Class<?> type, Type elementType, @Nullable IntFunction<Collection<Object>> factory, boolean isSet) {
			this.type = type;
			this.elementType = elementType;
			this.factory = factory;
			this.isSet = isSet;
		}

		private void addItem(Collection<Object> collection, @Nullable Object item, @Nullable SomlValue original) {
			boolean isAdded;

			// A collection can refuse an item, as a `TreeSet` refuses null and an item that is not `Comparable`.
			try {
				isAdded = collection.add(item);
			} catch (RuntimeException error) {
				// The errors of a frame are never shown, so their messages are short: `SomlMapper` drops an error of the decode while parsing, and decodes again from a tree, which reports it.
				var description = original == null ? "this item" : describeValue(original);
				throw new BindingError("The " + type.getSimpleName() + " cannot hold " + description + ": " + describeCause(error), false, error);
			}

			// Note: Not using `Collections.frequency()` because it compares by equality and misses duplicates defined by a sorted set's comparator.
			if (!isAdded && isSet) {
				throw new BindingError("The array has this item twice, which a set cannot hold");
			}
		}

		private Codec elementCodec() {
			var element = elementCodec;

			if (element == null) {
				element = codecFor(elementType);
				elementCodec = element;
			}

			return element;
		}

		@Override
		public Object decode(SomlValue value) {
			if (!(value instanceof SomlArray(var items))) {
				throw mismatch("an array", value);
			}

			if (factory == null) {
				throw cannotCreate(type);
			}

			var collection = factory.apply(items.size());
			var element = elementCodec();
			BindingError errors = null;

			// Note: Not using `Collection.addAll()` because insertion failures and duplicates must be attributed to their individual array indexes.
			for (var index = 0; index < items.size(); index++) {
				try {
					var original = items.get(index);
					addItem(collection, decodeValue(element, original), original);
				} catch (BindingError error) {
					errors = BindingError.add(errors, error.at(index));
				}
			}

			if (errors != null) {
				throw errors;
			}

			return type.isInterface() ? makeUnmodifiable(collection) : collection;
		}

		@Override
		public Frame frame(boolean isObject) {
			if (isObject || factory == null) {
				return new TreeFrame(this, isObject);
			}

			// A streaming container has no known size; start with the usual small capacity.
			var collection = factory.apply(10);
			var element = elementCodec();

			return new Frame() {
				@Override
				Codec next() {
					return element;
				}

				@Override
				void accept(@Nullable Object item) {
					addItem(collection, item, null);
				}

				@Override
				Object finish() {
					return type.isInterface() ? makeUnmodifiable(collection) : collection;
				}
			};
		}

		@Override
		public void write(Object value, TextEncoder encoder, int depth, int indent) {
			checkDepth(depth);
			var iterator = ((Iterable<?>) value).iterator();
			var output = encoder.output;

			if (!iterator.hasNext()) {
				output.append("[]");
				return;
			}

			output.append("[\n");

			for (var index = 0; iterator.hasNext(); index++) {
				output.repeat('\t', indent + 1);

				try {
					encoder.value(iterator.next(), depth + 1, indent + 1);
				} catch (BindingError error) {
					throw error.at(index);
				}

				output.append('\n');
			}

			output.repeat('\t', indent).append(']');
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			checkDepth(depth);
			var collection = (Collection<?>) value;
			var items = new SomlValue[collection.size()];
			var index = 0;

			for (var item : collection) {
				try {
					items[index] = encodeValue(item, depth + 1);
				} catch (BindingError error) {
					throw error.at(index);
				}

				index++;
			}

			return SomlArray.ofTrusted(items);
		}
	}

	private static BindingError cannotCreate(Class<?> type) {
		if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
			return BindingError.setup("Cannot decode to " + type.getSimpleName() + ", because it is not known which class to create for it. Use a class, such as " + (Map.class.isAssignableFrom(type) ? "HashMap" : "ArrayList") + ", or an interface such as List, Set, or Map");
		}

		return BindingError.setup("Cannot decode to " + type.getSimpleName() + ", because it has no constructor without parameters");
	}

	private static Collection<Object> makeUnmodifiable(Collection<Object> collection) {
		// Note: Not using `Collections.unmodifiableSortedSet()` because a requested `NavigableSet` must retain its navigation methods.
		return switch (collection) {
			case NavigableSet<Object> set -> Collections.unmodifiableNavigableSet(set);
			case SequencedSet<Object> set -> Collections.unmodifiableSequencedSet(set);
			case List<Object> list -> Collections.unmodifiableList(list);
			default -> collection;
		};
	}

	/*
	What to create for a collection type: an ordered implementation for an interface, made unmodifiable after it is filled, and otherwise the class itself, through its constructor without parameters, or `EnumSet.noneOf()` for an `EnumSet`. Null when there is none.
	*/
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static @Nullable IntFunction<Collection<Object>> collectionFactory(Class<?> type, Type elementType) {
		if (type == List.class || type == Collection.class || type == Iterable.class || type == SequencedCollection.class || type == ArrayList.class) {
			return ArrayList::new;
		}

		if (type == Set.class || type == SequencedSet.class || type == LinkedHashSet.class) {
			return LinkedHashSet::newLinkedHashSet;
		}

		if (type == HashSet.class) {
			return HashSet::newHashSet;
		}

		if (type == SortedSet.class || type == NavigableSet.class || type == TreeSet.class) {
			return _ -> new TreeSet<>();
		}

		if (type == Queue.class || type == Deque.class || type == ArrayDeque.class) {
			return ArrayDeque::new;
		}

		if (type == EnumSet.class) {
			var enumType = erasure(elementType);
			return enumType.isEnum() ? _ -> EnumSet.noneOf((Class<Enum>) enumType) : null;
		}

		var constructor = findNoArgumentConstructor(type);

		if (constructor == null) {
			return null;
		}

		return _ -> {
			@SuppressWarnings("unchecked")
			var collection = (Collection<Object>) newInstance(constructor);
			return collection;
		};
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static @Nullable IntFunction<Map<Object, Object>> mapFactory(Class<?> type, Type keyType) {
		if (type == Map.class || type == SequencedMap.class || type == LinkedHashMap.class) {
			return LinkedHashMap::newLinkedHashMap;
		}

		if (type == HashMap.class) {
			return HashMap::newHashMap;
		}

		if (type == ConcurrentHashMap.class) {
			return ConcurrentHashMap::new;
		}

		if (type == SortedMap.class || type == NavigableMap.class || type == TreeMap.class) {
			return _ -> new TreeMap<>();
		}

		if (type == EnumMap.class) {
			var enumType = erasure(keyType);
			return enumType.isEnum() ? _ -> new EnumMap((Class<Enum>) enumType) : null;
		}

		var constructor = findNoArgumentConstructor(type);

		if (constructor == null) {
			return null;
		}

		return _ -> {
			@SuppressWarnings("unchecked")
			var map = (Map<Object, Object>) newInstance(constructor);
			return map;
		};
	}

	/*
	How a map key is decoded from the string it is in a document, by the declared key type.
	*/
	private Function<String, Object> keyDecoder(Type keyType) {
		var type = erasure(resolve(keyType, Map.of()));

		if (type == String.class || type == Object.class || type == CharSequence.class) {
			return key -> key;
		}

		// The codec of an enum is its adapter when it has one, as when an enum key is encoded.
		if (type.isEnum()) {
			var codec = codecFor(type);
			return key -> {
				try {
					return codec.decode(new SomlString(key));
				} catch (BindingError error) {
					throw new BindingError(error.reason, true, error.getCause(), error.isSetup);
				}
			};
		}

		if (type == long.class || type == Long.class) {
			return key -> parseIntegerKey(key, Long.MIN_VALUE, Long.MAX_VALUE, "long");
		}

		if (type == Integer.class) {
			return key -> (int) parseIntegerKey(key, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
		}

		if (type == Short.class) {
			return key -> (short) parseIntegerKey(key, Short.MIN_VALUE, Short.MAX_VALUE, "short");
		}

		if (type == Byte.class) {
			return key -> (byte) parseIntegerKey(key, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
		}

		if (type == BigInteger.class) {
			return key -> {
				checkIntegerKey(key);
				return new BigInteger(key);
			};
		}

		if (type == Character.class) {
			return key -> {
				if (key.length() != 1) {
					throw new BindingError("Expected a key of one character, but found " + Parser.describeKey(key), true, null);
				}

				return key.charAt(0);
			};
		}

		if (type == UUID.class) {
			return key -> {
				try {
					return UUID.fromString(key);
				} catch (IllegalArgumentException _) {
					throw new BindingError("Expected a key with a UUID, but found " + Parser.describeKey(key), true, null);
				}
			};
		}

		return key -> {
			throw BindingError.setup("Cannot decode a map key to " + typeName(keyType) + ". A map key can be a String, a char, an enum, an integer type, or a UUID", true, null);
		};
	}

	/*
	A key such as `404` as an integer. Only the spelling of `Long.toString` is accepted, so each integer has one key.
	*/
	private static long parseIntegerKey(String key, long minimum, long maximum, String javaType) {
		// Even Long.MIN_VALUE has only 20 characters. Reject longer keys before the JDK constructs an exception containing the whole input.
		if (key.length() > 20) {
			throw invalidIntegerKey(key);
		}

		checkIntegerKey(key);
		long number;

		try {
			number = Long.parseLong(key);
		} catch (NumberFormatException _) {
			// The reason says it all, and the cause would repeat the whole key.
			throw invalidIntegerKey(key);
		}

		if (number < minimum || number > maximum) {
			throw new BindingError("The key " + key + " does not fit the Java type " + javaType + ", which holds " + minimum + " to " + maximum, true, null);
		}

		return number;
	}

	/*
	Checks the canonical ASCII decimal spelling before parsing a key, so invalid large keys never need arbitrary-precision arithmetic or a decimal round trip.
	*/
	private static void checkIntegerKey(String key) {
		if (key.equals("0")) {
			return;
		}

		var start = key.startsWith("-") ? 1 : 0;

		if (start < key.length() && key.charAt(start) >= '1' && key.charAt(start) <= '9') {
			var index = start + 1;

			while (index < key.length() && key.charAt(index) >= '0' && key.charAt(index) <= '9') {
				index++;
			}

			if (index == key.length()) {
				return;
			}
		}

		// Report the abbreviated key below, without retaining it in a cause.
		throw invalidIntegerKey(key);
	}

	private static BindingError invalidIntegerKey(String key) {
		return new BindingError("Expected a key with a decimal integer, such as 404, but found " + Parser.describeKey(key), true, null);
	}

	private String encodeKey(Object key) {
		return switch (key) {
			case String string -> string;
			case Enum<?> constant -> {
				if (!(codecFor(constant.getDeclaringClass()).encode(constant, 0) instanceof SomlString(var string))) {
					throw new BindingError("Cannot encode the map key " + constant + ", because its SomlAdapter does not write it as a string");
				}

				yield string;
			}
			case Long _, Integer _, Short _, Byte _, BigInteger _, UUID _, Character _ -> key.toString();
			case null -> throw new BindingError("Cannot encode a map with a null key");
			default -> throw new BindingError("Cannot encode a map key of type " + key.getClass().getSimpleName() + ". A map key can be a String, a char, an enum, an integer type, or a UUID");
		};
	}

	private final class MapCodec implements ObjectCodec {
		private final Class<?> type;
		private final Function<String, Object> keyDecoder;
		// Resolved on use, after this codec is cached, so a map can contain its own type.
		private final Type valueType;
		private volatile @Nullable Codec valueCodec;
		private final @Nullable IntFunction<Map<Object, Object>> factory;

		MapCodec(Class<?> type, Function<String, Object> keyDecoder, Type valueType, @Nullable IntFunction<Map<Object, Object>> factory) {
			this.type = type;
			this.keyDecoder = keyDecoder;
			this.valueType = valueType;
			this.factory = factory;
		}

		private Codec valueCodec() {
			var value = valueCodec;

			if (value == null) {
				value = codecFor(valueType);
				valueCodec = value;
			}

			return value;
		}

		private void putMember(Map<Object, Object> map, @Nullable Object key, @Nullable Object value, @Nullable SomlValue original) {
			// A map can refuse a member, as a `ConcurrentHashMap` refuses null.
			try {
				map.put(key, value);
			} catch (RuntimeException error) {
				var description = original == null ? "this member" : describeValue(original);
				throw new BindingError("The " + type.getSimpleName() + " cannot hold " + description + ": " + describeCause(error), false, error);
			}
		}

		private Object decodeKey(Map<Object, Object> map, String memberKey) {
			var key = keyDecoder.apply(memberKey);

			if (map.containsKey(key)) {
				throw new BindingError("Two keys decode to the same map key", true, null);
			}

			return key;
		}

		@Override
		public Object decode(SomlValue somlValue) {
			if (!(somlValue instanceof SomlObject object)) {
				throw mismatch("an object", somlValue);
			}

			if (factory == null) {
				throw cannotCreate(type);
			}

			var map = factory.apply(object.memberCount());
			var value = valueCodec();
			BindingError errors = null;

			// Note: Not using `Map.putAll()` because decoded-key collisions and insertion failures need the specific document member's path.
			for (var index = 0; index < object.memberCount(); index++) {
				var memberKey = object.keyAt(index);
				var memberValue = object.valueAt(index);

				try {
					var key = decodeKey(map, memberKey);

					var decoded = decodeValue(value, memberValue);

					putMember(map, key, decoded, memberValue);
				} catch (BindingError error) {
					errors = BindingError.add(errors, error.at(memberKey));
				}
			}

			if (errors != null) {
				throw errors;
			}

			return finishMap(map);
		}

		private Object finishMap(Map<Object, Object> map) {
			if (!type.isInterface()) {
				return map;
			}

			// For an interface, `mapFactory()` makes a `LinkedHashMap` or a `TreeMap`, and any other interface fails before this, so the cast holds.
			// Note: Not using `Collections.unmodifiableSortedMap()` because a requested `NavigableMap` must retain its navigation methods.
			return map instanceof NavigableMap<Object, Object> navigable ? Collections.unmodifiableNavigableMap(navigable) : Collections.unmodifiableSequencedMap((SequencedMap<Object, Object>) map);
		}

		@Override
		public Frame frame(boolean isObject) {
			if (!isObject || factory == null) {
				return new TreeFrame(this, isObject);
			}

			// A streaming container has no known size; start with the usual small capacity.
			var map = factory.apply(10);
			var value = valueCodec();

			return new Frame() {
				private @Nullable Object key;

				@Override
				void key(String memberKey) {
					key = decodeKey(map, memberKey);
				}

				@Override
				Codec next() {
					return value;
				}

				@Override
				void accept(@Nullable Object decoded) {
					putMember(map, key, decoded, null);
				}

				@Override
				Object finish() {
					return finishMap(map);
				}
			};
		}

		@Override
		public SomlValue encode(Object object, int depth) {
			checkDepth(depth);
			var map = (Map<?, ?>) object;
			var keys = new String[map.size()];
			var values = new SomlValue[keys.length];
			var written = HashSet.<String>newHashSet(keys.length);
			var count = 0;

			for (var entry : map.entrySet()) {
				var memberValue = entry.getValue();

				if (isAbsent(memberValue)) {
					continue;
				}

				var key = encodeKey(entry.getKey());

				try {
					keys[count] = checkKey(key, false);

					if (!written.add(key)) {
						throw new BindingError("Two keys of the map are both written as " + Parser.describeKey(key), true, null);
					}

					values[count] = encodeValue(memberValue, depth + 1);
					count++;
				} catch (BindingError error) {
					throw error.at(key);
				}
			}

			return SomlObject.ofTrusted(keys, values, count);
		}

		@Override
		public int writeMembers(Object object, TextEncoder encoder, int depth, int indent) {
			var map = (Map<?, ?>) object;

			if (map.isEmpty()) {
				return 0;
			}

			// Ordinary output can write entries immediately; only canonical output needs sorting storage.
			var keys = new String[encoder.isCanonical ? map.size() : 0];
			var values = new Object[keys.length];
			// Distinct keys can have the same spelling, as `1` and "1", or equal strings in an identity map.
			var written = HashSet.<String>newHashSet(map.size());
			var count = 0;

			for (var entry : map.entrySet()) {
				var memberValue = entry.getValue();

				if (isAbsent(memberValue)) {
					continue;
				}

				var key = encodeKey(entry.getKey());

				if (!written.add(key)) {
					throw new BindingError("Two keys of the map are both written as " + Parser.describeKey(key), true, null).at(key);
				}

				if (encoder.isCanonical) {
					keys[count] = key;
					values[count] = memberValue;
				} else {
					writeMember(key, memberValue, encoder, depth, indent);
				}

				count++;
			}

			if (!encoder.isCanonical) {
				return count;
			}

			var order = Writer.canonicalOrder(keys, count);

			for (var position = 0; position < count; position++) {
				var index = order == null ? position : order[position];
				writeMember(keys[index], values[index], encoder, depth, indent);
			}

			return count;
		}

		/*
		Writes one map member, keeping key validation and error paths identical in ordinary and canonical output.
		*/
		private void writeMember(String key, @Nullable Object value, TextEncoder encoder, int depth, int indent) {
			var output = encoder.output;
			output.repeat('\t', indent);

			try {
				output.appendCheckedKey(key);
			} catch (IllegalArgumentException error) {
				throw new BindingError(describeCause(error), true, error).at(key);
			}

			output.append(": ");

			try {
				encoder.value(value, depth, indent);
			} catch (BindingError error) {
				throw error.at(key);
			}

			output.append('\n');
		}
	}

	/*
	The string of an enum constant or the name of a subtype, which `@SomlName` can set to text that a string cannot hold. The name comes from the Java types, not the document.
	*/
	private static SomlString nameString(String name) {
		try {
			return new SomlString(name);
		} catch (IllegalArgumentException error) {
			throw BindingError.setup(describeCause(error), false, error);
		}
	}

	/*
	A key of a member is about the Java types, and a key of a map is about the value.
	*/
	private static String checkKey(String key, boolean isMember) {
		try {
			SomlString.checkText(key, "A key");
			return key;
		} catch (IllegalArgumentException error) {
			throw isMember ? BindingError.setup(describeCause(error), true, error) : new BindingError(describeCause(error), true, error);
		}
	}

	private static final MethodType GETTER_TYPE = MethodType.genericMethodType(1);

	/*
	A record component or a field, as a member of an object.
	*/
	/*
	Note: Not using `Method.invoke()` or `Field.get()` because an `(Object) -> Object` method handle is cheaper to call. `getter` is null when the member is not accessible, and reading it reports why.
	*/
	private sealed interface Member permits RecordMember, FieldMember {
		String key();

		Type type();

		@Nullable MethodHandle getter();

		private BindingError readFailure(Object object, @Nullable Throwable cause) {
			return new BindingError("Reading " + key() + " of " + object.getClass().getSimpleName() + " failed: " + cause, false, cause).at(key());
		}

		default @Nullable Object read(Object object) {
			var getter = getter();

			if (getter != null) {
				try {
					return (Object) getter.invokeExact(object);
				} catch (Throwable error) {
					throw readFailure(object, error);
				}
			}

			try {
				// A member has an accessor or a field.
				return switch (this) {
					case RecordMember member -> member.accessor().invoke(object);
					case FieldMember member -> member.field().get(object);
				};
			} catch (IllegalAccessException error) {
				throw inaccessible(object.getClass(), error).at(key());
			} catch (InvocationTargetException error) {
				throw readFailure(object, error.getCause());
			}
		}
	}

	private record RecordMember(String key, Type type, boolean isNullable, Method accessor, @Nullable MethodHandle getter) implements Member {
		RecordMember(String key, Type type, boolean isNullable, Method accessor) {
			this(key, type, isNullable, accessor, getterOf(accessor));
		}

		private static @Nullable MethodHandle getterOf(Method accessor) {
			try {
				// One type for every getter, so that one `invokeExact()` call site fits all of them.
				return MethodHandles.lookup().unreflect(accessor).asType(GETTER_TYPE);
			} catch (IllegalAccessException _) {
				return null;
			}
		}
	}

	private record FieldMember(String key, Type type, Field field, @Nullable MethodHandle getter) implements Member {
		FieldMember(String key, Type type, Field field) {
			this(key, type, field, getterOf(field));
		}

		private static @Nullable MethodHandle getterOf(Field field) {
			try {
				return MethodHandles.lookup().unreflectGetter(field).asType(GETTER_TYPE);
			} catch (IllegalAccessException _) {
				return null;
			}
		}

		Field writableField(Class<?> declaringType) {
			// The members of a class are fields.
			// Java plans to refuse setting a final field by reflection (JEP 500), and warns about it now, so the decoder refuses it first, with a message that says what to change.
			if (Modifier.isFinal(field.getModifiers())) {
				throw BindingError.setup("Cannot decode the final field " + field.getName() + " of " + declaringType.getSimpleName() + ". Make it not final, or make " + declaringType.getSimpleName() + " a record").at(key);
			}

			return field;
		}
	}

	/*
	The members of a record or a class, with their codecs, which are looked up on first use so that a type can contain itself.
	*/
	/*
	`formattedKeys` are the keys as a document writes them, and `canonicalOrder` the indexes of the members sorted by key, both found once for the encoder.
	*/
	private record Members<MemberType extends Member>(MemberType[] members, Codec[] codecs, Map<String, Integer> indexes, String[] formattedKeys, Integer @Nullable [] canonicalOrder) {
		int indexAt(int position, boolean isCanonical) {
			return isCanonical && canonicalOrder != null ? canonicalOrder[position] : position;
		}

		/*
		The index of the member with `key`, or -1. A document usually has its members in the order they are declared, so the member at `hint`, the position in the document, is tried first, which saves hashing the key.
		*/
		int indexOf(String key, int hint) {
			if (hint < members.length && members[hint].key().equals(key)) {
				return hint;
			}

			return indexes.getOrDefault(key, -1);
		}

		/*
		Writes the members of a record or a class, each on its own line, and returns how many it wrote. A member with an empty `Optional` is left out.
		*/
		int write(Object object, TextEncoder encoder, int depth, int indent) {
			var output = encoder.output;
			var written = 0;

			for (var position = 0; position < members.length; position++) {
				var index = indexAt(position, encoder.isCanonical);
				var member = members[index];
				var value = member.read(object);

				if (isAbsent(value)) {
					continue;
				}

				output.repeat('\t', indent).append(formattedKeys[index]).append(": ");

				try {
					encoder.value(value, depth, indent);
				} catch (BindingError error) {
					throw error.at(member.key());
				}

				output.append('\n');
				written++;
			}

			return written;
		}
	}

	private <MemberType extends Member> Members<MemberType> resolveMembers(MemberType[] members, Class<?> type) {
		var codecs = new Codec[members.length];
		var indexes = HashMap.<String, Integer>newHashMap(members.length);
		var formattedKeys = new String[members.length];
		var sortedKeys = new String[members.length];

		var isSubtype = subtypeName(type) != null;

		for (var index = 0; index < members.length; index++) {
			checkKey(members[index].key(), true);

			if (isSubtype && members[index].key().equals(TYPE_KEY)) {
				throw BindingError.setup("Cannot use " + type.getSimpleName() + ", because it is a subtype of a sealed type, whose member " + Parser.describeKey(TYPE_KEY) + " names the subtype, so " + type.getSimpleName() + " cannot have a member with that key. Give it another key with @SomlName");
			}

			var existing = indexes.put(members[index].key(), index);

			if (existing != null) {
				throw BindingError.setup("Cannot use " + type.getSimpleName() + ", because two of its members are both named " + Parser.describeKey(members[index].key()) + ". Give one of them another name with @SomlName");
			}

			codecs[index] = codecFor(members[index].type());
			formattedKeys[index] = Writer.formatKey(members[index].key());
			sortedKeys[index] = members[index].key();
		}

		// The writer returns null when the declaration order is already canonical.
		var canonicalOrder = Writer.canonicalOrder(sortedKeys, members.length);

		return new Members<>(members, codecs, indexes, formattedKeys, canonicalOrder);
	}

	private String keyOf(String javaName, AnnotatedElement element) {
		var annotation = element.getAnnotation(SomlName.class);
		return annotation != null ? annotation.value() : keyNaming.apply(javaName);
	}

	/*
	Whether a record component is marked nullable, with any annotation named `Nullable`, such as JSpecify's, on it or on its type.
	*/
	private static boolean isNullable(RecordComponent component) {
		return hasNullableAnnotation(component) || hasNullableAnnotation(component.getAnnotatedType());
	}

	private static boolean hasNullableAnnotation(AnnotatedElement element) {
		for (var annotation : element.getAnnotations()) {
			if (annotation.annotationType().getSimpleName().equals("Nullable")) {
				return true;
			}
		}

		return false;
	}

	private SomlValue encodeMembers(Members<?> members, Object object, int depth) {
		checkDepth(depth);
		var keys = new String[members.members().length];
		var values = new SomlValue[keys.length];
		var count = 0;

		for (var member : members.members()) {
			var value = member.read(object);

			try {
				if (!isAbsent(value)) {
					keys[count] = member.key();
					values[count] = encodeValue(value, depth + 1);
					count++;
				}
			} catch (BindingError error) {
				throw error.at(member.key());
			}
		}

		return SomlObject.ofTrusted(keys, values, count);
	}

	private static BindingError inaccessible(Class<?> type, Exception error) {
		return BindingError.setup("Cannot access " + type.getName() + ". If it is in a module, open its package to sh.soml, as in `opens " + type.getPackageName() + " to sh.soml;`", false, error);
	}

	private final class RecordCodec implements ObjectCodec {
		private final Class<?> type;
		private final Map<TypeVariable<?>, Type> bindings;
		// The constructor is immutable; members and their recursive codecs are resolved on first use.
		private volatile @Nullable Members<RecordMember> members;
		private final Constructor<?> constructor;
		private final RecordComponent[] components;

		RecordCodec(Class<?> type, Map<TypeVariable<?>, Type> bindings) {
			this.type = type;
			this.bindings = bindings;
			this.components = type.getRecordComponents();
			var parameters = new Class<?>[components.length];

			for (var index = 0; index < components.length; index++) {
				parameters[index] = components[index].getType();
			}

			try {
				constructor = type.getDeclaredConstructor(parameters);
				constructor.trySetAccessible();
			} catch (NoSuchMethodException error) {
				throw new IllegalStateException(error);
			}
		}

		private Members<RecordMember> members() {
			var members = this.members;

			if (members == null) {
				var recordMembers = new RecordMember[components.length];

				for (var index = 0; index < components.length; index++) {
					var component = components[index];
					if (isIgnored(type, component.getName())) {
						throw BindingError.setup("Cannot leave out the component " + component.getName() + " of " + type.getSimpleName() + " with @SomlIgnore, because its constructor needs it. Use a class for a member that is not in the document");
					}

					var accessor = component.getAccessor();
					// When this fails, reading the member reports why, with the `opens` hint.
					accessor.trySetAccessible();
					recordMembers[index] = new RecordMember(keyOf(component.getName(), component), resolve(component.getGenericType(), bindings), isNullable(component), accessor);
				}

				members = resolveMembers(recordMembers, type);
				this.members = members;
			}

			return members;
		}

		@Override
		public Object decode(SomlValue value) {
			if (!(value instanceof SomlObject object)) {
				throw mismatch("an object", value);
			}

			var members = members();
			var arguments = new @Nullable Object[members.members().length];
			// A present argument may be null, so only the sentinel marks a missing member.
			Arrays.fill(arguments, REQUIRED);
			BindingError errors = null;

			for (var position = 0; position < object.memberCount(); position++) {
				var key = object.keyAt(position);
				var index = members.indexOf(key, position);

				if (index == -1) {
					if (rejectsUnknownKeys) {
						errors = BindingError.add(errors, unknownKey(type, key, members));
					}

					continue;
				}

				// A member that does not fit is set, so it is not reported as missing too.
				arguments[index] = null;

				try {
					arguments[index] = decodeValue(members.codecs()[index], object.valueAt(position));
				} catch (BindingError error) {
					errors = BindingError.add(errors, error.at(key));
				}
			}

			return construct(members, arguments, errors);
		}

		/*
		The record from the members that are set, and the values of missing members for the others. With `errors` from its members, or with missing members, it reports them all and is not created, so that its constructor sees only values that fit.
		*/
		private Object construct(Members<RecordMember> members, @Nullable Object[] arguments, @Nullable BindingError errors) {
			for (var index = 0; index < arguments.length; index++) {
				if (arguments[index] == REQUIRED) {
					try {
						arguments[index] = missingValue(members.members()[index], members.codecs()[index], type);
					} catch (BindingError error) {
						errors = BindingError.add(errors, error);
					}
				}
			}

			if (errors != null) {
				throw errors;
			}

			try {
				// The canonical constructor is found once, before the members are resolved.
				return constructor.newInstance(arguments);
			} catch (InvocationTargetException error) {
				// Reflection always sets the cause, which is what the constructor threw.
				throw new BindingError("Could not create " + type.getSimpleName() + ": " + describeCause(Objects.requireNonNull(error.getCause())), false, error.getCause());
			} catch (IllegalAccessException error) {
				throw inaccessible(type, error);
			} catch (ReflectiveOperationException error) {
				throw BindingError.setup("Could not create " + type.getSimpleName() + ": " + error, false, error);
			}
		}

		@Override
		public Frame frame(boolean isObject) {
			if (!isObject) {
				return new TreeFrame(this, false);
			}

			var members = members();
			var arguments = new @Nullable Object[members.members().length];
			Arrays.fill(arguments, REQUIRED);

			return new Frame() {
				private int position;
				private int current = -1;

				@Override
				void key(String key) {
					current = members.indexOf(key, position++);

					if (current == -1 && rejectsUnknownKeys) {
						throw unknownKey(type, key, members);
					}
				}

				@Override
				Codec next() {
					return current == -1 ? SKIP : members.codecs()[current];
				}

				@Override
				void accept(@Nullable Object value) {
					if (current != -1) {
						arguments[current] = value;
					}
				}

				@Override
				Object finish() {
					return construct(members, arguments, null);
				}
			};
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return encodeMembers(members(), value, depth);
		}

		@Override
		public int writeMembers(Object value, TextEncoder encoder, int depth, int indent) {
			return members().write(value, encoder, depth, indent);
		}
	}

	/*
	`@SomlIgnore` on a record component is on its field, because the annotation is for fields.
	*/
	private static boolean isIgnored(Class<?> record, String component) {
		try {
			return record.getDeclaredField(component).isAnnotationPresent(SomlIgnore.class);
		} catch (NoSuchFieldException _) {
			return false;
		}
	}

	private static BindingError unknownKey(Class<?> type, String key, Members<?> members) {
		var count = members.members().length;
		var keys = new StringJoiner(", ", "its members are ", count > 20 ? ", …" : "").setEmptyValue("it has no members");

		for (var index = 0; index < Math.min(count, 20); index++) {
			keys.add(Parser.describeKey(members.members()[members.indexAt(index, true)].key()));
		}

		return new BindingError("Unknown key " + Parser.describeKey(key) + ". " + type.getSimpleName() + " has no member for it, and " + keys, true, null).at(key);
	}

	private static @Nullable Object missingValue(RecordMember member, Codec codec, Class<?> type) {
		var missing = codec.missing();

		if (missing != REQUIRED) {
			return missing;
		}

		if (member.isNullable() && !erasure(member.type()).isPrimitive()) {
			return null;
		}

		throw new BindingError("Missing the member " + Parser.describeKey(member.key()) + ", which " + type.getSimpleName() + " requires. Make it Optional or @Nullable if it may be left out");
	}

	/*
	A class with a constructor without parameters, whose fields are set one by one. A field the document leaves out keeps the value the constructor gave it.
	*/
	private final class ClassCodec implements ObjectCodec {
		private final Class<?> type;
		private final Map<TypeVariable<?>, Type> bindings;
		private volatile @Nullable Members<FieldMember> members;
		private final @Nullable Constructor<?> constructor;

		ClassCodec(Class<?> type, Map<TypeVariable<?>, Type> bindings) {
			this.type = type;
			this.bindings = bindings;
			this.constructor = findNoArgumentConstructor(type);
		}

		private Members<FieldMember> members() {
			var members = this.members;

			if (members == null) {
				var hierarchy = new ArrayList<Class<?>>();
				var allBindings = new HashMap<>(bindings);

				// From the class up, so that the type arguments that a class gives its superclass, which can use the class's own type variables, are resolved before the superclass's fields need them. The fields are then listed from the topmost superclass down.
				for (var current = type; current != null && current != Object.class; current = current.getSuperclass()) {
					hierarchy.add(current);

					if (current.getGenericSuperclass() instanceof ParameterizedType superclass) {
						allBindings.putAll(typeBindings((ParameterizedType) resolve(superclass, allBindings)));
					}
				}

				var list = new ArrayList<FieldMember>();

				for (var current : hierarchy.reversed()) {
					for (var field : current.getDeclaredFields()) {
						var modifiers = field.getModifiers();

						// A synthetic field, such as the reference of an inner class to its outer instance, is not a member.
						if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic() || field.isAnnotationPresent(SomlIgnore.class)) {
							continue;
						}

						field.trySetAccessible();
						list.add(new FieldMember(keyOf(field.getName(), field), resolve(field.getGenericType(), allBindings), field));
					}
				}

				members = resolveMembers(list.toArray(FieldMember[]::new), type);
				this.members = members;
			}

			return members;
		}

		@Override
		public Object decode(SomlValue value) {
			if (!(value instanceof SomlObject object)) {
				throw mismatch("an object", value);
			}

			if (constructor == null) {
				// An inner class, which is a class in another class or in a method that is not static, takes the outer object as the first parameter of each constructor. A local class has no `static` modifier, also in a static method.
				var outer = type.getEnclosingClass();
				var isInner = outer != null && !Modifier.isStatic(type.getModifiers()) && Arrays.stream(type.getDeclaredConstructors()).allMatch(each -> each.getParameterCount() > 0 && each.getParameterTypes()[0] == outer);
				throw BindingError.setup("Cannot decode to " + type.getSimpleName() + ", because " + (isInner ? "it is an inner class, whose constructor needs the outer object. Make it static, declare it in a static method, or make it a record" : "it has no constructor without parameters. Add one, or make it a record"));
			}

			var members = members();
			var instance = newInstance(constructor);
			BindingError errors = null;

			for (var position = 0; position < object.memberCount(); position++) {
				var key = object.keyAt(position);
				var index = members.indexOf(key, position);

				if (index == -1) {
					if (rejectsUnknownKeys) {
						errors = BindingError.add(errors, unknownKey(type, key, members));
					}

					continue;
				}

				var field = members.members()[index].writableField(type);

				try {
					field.set(instance, decodeValue(members.codecs()[index], object.valueAt(position)));
				} catch (BindingError error) {
					errors = BindingError.add(errors, error.at(key));
				} catch (IllegalAccessException error) {
					throw inaccessible(type, error);
				}
			}

			if (errors != null) {
				throw errors;
			}

			return instance;
		}

		@Override
		public Frame frame(boolean isObject) {
			if (!isObject || constructor == null) {
				return new TreeFrame(this, isObject);
			}

			var members = members();
			var instance = newInstance(constructor);

			return new Frame() {
				private int position;
				private int current = -1;

				@Override
				void key(String key) {
					current = members.indexOf(key, position++);

					if (current == -1) {
						if (rejectsUnknownKeys) {
							throw unknownKey(type, key, members);
						}

						return;
					}

					// The members of a class are fields.
					members.members()[current].writableField(type);
				}

				@Override
				Codec next() {
					return current == -1 ? SKIP : members.codecs()[current];
				}

				@Override
				void accept(@Nullable Object value) {
					if (current == -1) {
						return;
					}

					try {
						members.members()[current].field().set(instance, value);
					} catch (IllegalAccessException error) {
						throw inaccessible(type, error);
					}
				}

				@Override
				Object finish() {
					return instance;
				}
			};
		}

		@Override
		public SomlValue encode(Object value, int depth) {
			return encodeMembers(members(), value, depth);
		}

		@Override
		public int writeMembers(Object value, TextEncoder encoder, int depth, int indent) {
			return members().write(value, encoder, depth, indent);
		}
	}

	private static @Nullable Constructor<?> findNoArgumentConstructor(Class<?> type) {
		try {
			var constructor = type.getDeclaredConstructor();
			constructor.trySetAccessible();
			return constructor;
		} catch (NoSuchMethodException _) {
			return null;
		}
	}

	private static Object newInstance(Constructor<?> constructor) {
		try {
			return constructor.newInstance();
		} catch (InvocationTargetException error) {
			// Reflection always sets the cause, which is what the constructor threw.
			throw BindingError.setup("Could not create " + constructor.getDeclaringClass().getSimpleName() + ": " + describeCause(Objects.requireNonNull(error.getCause())), false, error.getCause());
		} catch (IllegalAccessException error) {
			throw inaccessible(constructor.getDeclaringClass(), error);
		} catch (ReflectiveOperationException error) {
			throw BindingError.setup("Could not create " + constructor.getDeclaringClass().getSimpleName() + ": " + error, false, error);
		}
	}

	/*
	The type arguments of a parameterized type, by the type parameters of its class, so that the members of a generic record or class get their actual types.
	*/
	private static Map<TypeVariable<?>, Type> typeBindings(ParameterizedType type) {
		var parameters = ((Class<?>) type.getRawType()).getTypeParameters();

		if (parameters.length == 0) {
			return Map.of();
		}

		var arguments = type.getActualTypeArguments();
		var bindings = HashMap.<TypeVariable<?>, Type>newHashMap(parameters.length);

		for (var index = 0; index < parameters.length; index++) {
			bindings.put(parameters[index], arguments[index]);
		}

		return bindings;
	}

	/*
	A type with its type variables replaced by their bindings.
	*/
	private static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
		return switch (type) {
			case TypeVariable<?> variable -> bindings.getOrDefault(variable, variable);
			case ParameterizedType parameterized -> {
				var arguments = parameterized.getActualTypeArguments();
				Type[] resolved = null;

				for (var index = 0; index < arguments.length; index++) {
					var argument = resolve(arguments[index], bindings);

					if (argument != arguments[index] && resolved == null) {
						resolved = arguments.clone();
					}

					if (resolved != null) {
						resolved[index] = argument;
					}
				}

				yield resolved == null ? type : new ResolvedParameterizedType((Class<?>) parameterized.getRawType(), resolved, parameterized.getOwnerType());
			}
			case GenericArrayType array -> {
				var original = array.getGenericComponentType();
				var component = resolve(original, bindings);
				// A `T[]` whose `T` is a class becomes that array class, so it finds the same cached codec as a declared `String[]`, for example.
				if (component instanceof Class<?> componentClass) {
					yield componentClass.arrayType();
				}

				yield component == original ? type : new ResolvedGenericArrayType(component);
			}
			case WildcardType wildcard -> resolve(wildcardBound(wildcard), bindings);
			default -> type;
		};
	}

	private static Type wildcardBound(WildcardType wildcard) {
		var lowerBounds = wildcard.getLowerBounds();
		return lowerBounds.length > 0 ? lowerBounds[0] : wildcard.getUpperBounds()[0];
	}

	private static Class<?> erasure(Type type) {
		return switch (type) {
			case Class<?> type_ -> type_;
			case ParameterizedType parameterized -> (Class<?>) parameterized.getRawType();
			case GenericArrayType array -> erasure(array.getGenericComponentType()).arrayType();
			case WildcardType wildcard -> erasure(wildcard.getUpperBounds()[0]);
			case TypeVariable<?> variable -> erasure(variable.getBounds()[0]);
			default -> Object.class;
		};
	}

	/*
	Equal to the JDK's own parameterized types with the same raw type, owner, and arguments, so that both find the same cached codec.
	*/
	private record ResolvedParameterizedType(Class<?> rawType, Type[] arguments, @Nullable Type ownerType) implements ParameterizedType {
		@Override
		public Type[] getActualTypeArguments() {
			return arguments.clone();
		}

		@Override
		public Type getRawType() {
			return rawType;
		}

		@Override
		public @Nullable Type getOwnerType() {
			return ownerType;
		}

		@Override
		public boolean equals(@Nullable Object object) {
			return object instanceof ParameterizedType other && rawType.equals(other.getRawType()) && Objects.equals(ownerType, other.getOwnerType()) && Arrays.equals(arguments, other.getActualTypeArguments());
		}

		// The formula of the JDK's own parameterized types, so that equal types have equal hash codes in the codec cache.
		@Override
		public int hashCode() {
			return Arrays.hashCode(arguments) ^ Objects.hashCode(ownerType) ^ Objects.hashCode(rawType);
		}

		@Override
		public String toString() {
			return getTypeName();
		}

		@Override
		public String getTypeName() {
			var names = new StringJoiner(", ", "<", ">");

			for (var argument : arguments) {
				names.add(argument.getTypeName());
			}

			return rawType.getTypeName() + names;
		}
	}

	private record ResolvedGenericArrayType(Type component) implements GenericArrayType {
		@Override
		public Type getGenericComponentType() {
			return component;
		}

		@Override
		public boolean equals(@Nullable Object object) {
			return object instanceof GenericArrayType other && component.equals(other.getGenericComponentType());
		}

		@Override
		public int hashCode() {
			return component.hashCode();
		}

		@Override
		public String getTypeName() {
			return component.getTypeName() + "[]";
		}
	}
}
