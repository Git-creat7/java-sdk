/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.ToolParamHeaderAnnotations;
import io.modelcontextprotocol.modern.ToolParamHeaderAnnotations.Annotation;
import io.modelcontextprotocol.modern.ToolParamHeaderAnnotations.InvalidAnnotation;
import io.modelcontextprotocol.modern.ToolParamHeaderAnnotations.Type;
import io.modelcontextprotocol.modern.server.McpParamHeaders;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.annotation.Nullable;

/**
 * Checks the {@code Mcp-Param-{Name}} headers of a {@code tools/call} against the
 * arguments the tool annotates with {@code x-mcp-header}.
 *
 * @author Daniel Garnier-Moiroux
 * @see <a href="https://modelcontextprotocol.io/seps/2243-http-standardization">SEP-2243:
 * HTTP Standardization</a>
 */
public final class ParamHeaderValidator {

	private static final Logger logger = LoggerFactory.getLogger(ParamHeaderValidator.class);

	private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

	private static final Valid VALID = new Valid();

	private ParamHeaderValidator() {
	}

	/**
	 * Validates the request's {@code Mcp-Param-{Name}} headers against the arguments the
	 * {@code tool} annotates with {@code x-mcp-header}.
	 * <p>
	 * Over a transport that sets {@link McpParamHeaders#NOT_MIRRORED} (such as stdio),
	 * validation is skipped. Invalid {@code x-mcp-header} annotations on the tool are
	 * logged at {@code WARN} and not enforced.
	 * @param ctx the current request context
	 * @param tool the tool being called
	 * @param arguments the call arguments, or {@code null} if the call has none
	 * @throws McpException {@code -32020} ({@link ErrorCodes#HEADER_MISMATCH}) if a
	 * header is missing, malformed, or does not match its argument, or {@code -32603}
	 * ({@link ErrorCodes#INTERNAL_ERROR}) if the transport did not populate
	 * {@link McpParamHeaders#TRANSPORT_CONTEXT_KEY}
	 */
	public static void validate(McpRequestContext ctx, Tool tool, @Nullable Map<String, Object> arguments) {
		Assert.notNull(ctx, "ctx must not be null");
		Assert.notNull(tool, "tool must not be null");
		Object headers = ctx.transportContext().get(McpParamHeaders.TRANSPORT_CONTEXT_KEY);
		if (headers == McpParamHeaders.NOT_MIRRORED) {
			return;
		}
		// Parsed on every call, without caching, because repositories are user code and
		// may return a different tool each time.
		ToolParamHeaderAnnotations annotations = ToolParamHeaderAnnotations.parse(tool);
		if (!annotations.isValid()) {
			// Compliant clients drop the tool from tools/list: this is how its author
			// finds out why.
			logger.warn("Tool '{}' has invalid {} annotations, which are not enforced: {}", tool.name(),
					ToolParamHeaderAnnotations.KEYWORD,
					annotations.invalid().stream().map(InvalidAnnotation::describe).collect(Collectors.joining("; ")));
		}
		if (annotations.valid().isEmpty()) {
			return;
		}
		// Failing rather than skipping, so a transport that forgets the headers can't
		// silently disable validation.
		if (!(headers instanceof McpParamHeaders paramHeaders)) {
			logger
				.warn("Cannot validate Mcp-Param headers for tool '{}': transport context key '{}' is {}. The transport "
						+ "must map it to an McpParamHeaders, or to McpParamHeaders.NOT_MIRRORED if it has no headers",
						tool.name(), McpParamHeaders.TRANSPORT_CONTEXT_KEY,
						(headers == null) ? "missing" : "a " + headers.getClass().getName());
			throw new McpException(ErrorCodes.INTERNAL_ERROR, "Internal error");
		}
		Result result = validate(annotations.valid(), arguments != null ? arguments : Map.of(), paramHeaders);
		if (result instanceof Invalid invalid) {
			throw new McpException(ErrorCodes.HEADER_MISMATCH, invalid.problem());
		}
	}

	/**
	 * Validates every annotation, in order, and reports the first failure.
	 * <p>
	 * Headers that no annotation declares are ignored, even when malformed. Error
	 * messages name the header and the argument but never their values, which are logged
	 * at {@code DEBUG} instead.
	 * @param annotations the tool's annotations
	 * @param callToolArguments the arguments for tools/call, empty if the call has none
	 * @param headers the request's headers
	 * @return {@link Valid}, or {@link Invalid} with the first failure
	 */
	public static Result validate(List<Annotation> annotations, Map<String, Object> callToolArguments,
			McpParamHeaders headers) {
		for (Annotation annotation : annotations) {
			Object argument = resolve(callToolArguments, annotation.path());
			String problem = check(annotation, argument, headers);
			if (problem != null) {
				logger.debug("Rejecting tools/call: {} (header: {}, argument: {})", problem,
						describe(headers, annotation.name()), argument);
				return new Invalid(problem);
			}
		}
		return VALID;
	}

	// null if the header agrees with the argument.
	private static String check(Annotation annotation, Object argument, McpParamHeaders headers) {
		boolean present = headers.contains(annotation.name());
		String mismatch = header(annotation) + " header does not match argument '" + annotation.argument() + "'";
		if (argument == null) {
			return present
					? header(annotation) + " header present but argument '" + annotation.argument() + "' is absent"
					: null;
		}
		if (argument instanceof Map<?, ?> || argument instanceof List<?>) {
			// Can't be mirrored: a header is a mismatch, and without one the input
			// schema validation reports the wrong type.
			return present ? mismatch : null;
		}
		if (!present) {
			return "Missing required header: " + header(annotation);
		}
		if (headers.isMalformed(annotation.name())) {
			return header(annotation) + " header is malformed";
		}
		return matches(annotation.type(), argument, headers.value(annotation.name())) ? null : mismatch;
	}

	private static Object resolve(Map<String, Object> arguments, List<String> path) {
		Object value = arguments;
		for (String key : path) {
			if (!(value instanceof Map<?, ?> object)) {
				return null;
			}
			value = object.get(key);
		}
		return value;
	}

	// Integers are compared numerically, so 42, 42.0 and 4.2e1 are equal whatever form
	// the client and the JSON parser used.
	//
	// The spec asks servers to reject integers beyond +/-(2^53-1). That rule exists
	// because JavaScript can't represent larger integers exactly. Java's Long and
	// BigInteger can, so large values are compared exactly instead; tool authors who
	// want a bound can declare minimum and maximum.
	private static boolean matches(Type type, Object argument, String header) {
		if (type == Type.INTEGER && argument instanceof Number number) {
			BigDecimal value = decimal(number);
			return value != null && DECIMAL.matcher(header).matches() && value.compareTo(new BigDecimal(header)) == 0;
		}
		return String.valueOf(argument).equals(header);
	}

	// null for NaN and infinities.
	private static BigDecimal decimal(Number number) {
		if (number instanceof BigDecimal decimal) {
			return decimal;
		}
		if (number instanceof BigInteger integer) {
			return new BigDecimal(integer);
		}
		if (number instanceof Double || number instanceof Float) {
			double value = number.doubleValue();
			return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
		}
		return BigDecimal.valueOf(number.longValue());
	}

	private static String header(Annotation annotation) {
		return McpParamHeaders.HEADER_PREFIX + annotation.name();
	}

	private static String describe(McpParamHeaders headers, String name) {
		if (headers.isMalformed(name)) {
			return "<malformed>";
		}
		String value = headers.value(name);
		return (value != null) ? value : "<absent>";
	}

	/**
	 * The outcome of {@link #validate(List, Map, McpParamHeaders)}.
	 */
	public sealed interface Result {

	}

	/**
	 * Every annotated header agrees with its argument.
	 */
	public record Valid() implements Result {

	}

	/**
	 * A header disagrees with its argument.
	 *
	 * @param problem names the header and the argument, never their values
	 */
	public record Invalid(String problem) implements Result {

	}

}
