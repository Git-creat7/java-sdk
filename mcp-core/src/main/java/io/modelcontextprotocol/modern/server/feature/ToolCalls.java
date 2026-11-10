/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;
import reactor.util.annotation.Nullable;

/**
 * Reusable per-call steps around a tool invocation: required client capability checks,
 * {@code Mcp-Param-{Name}} header validation, input validation against
 * {@code inputSchema}, and output validation against {@code outputSchema} with
 * {@code structuredContent} fallback population.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class ToolCalls {

	private ToolCalls() {
	}

	/**
	 * Executes {@code tool} for {@code request}, applying required client capability
	 * checks, {@code Mcp-Param-{Name}} header validation, {@code inputSchema} validation,
	 * and {@code outputSchema} validation with {@code structuredContent} text fallback.
	 */
	public static Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, McpAsyncTool tool,
			CallToolRequest request, JsonSchemaValidator validator, McpJsonMapper jsonMapper) {
		Assert.notNull(ctx, "ctx must not be null");
		Assert.notNull(tool, "tool must not be null");
		Assert.notNull(request, "request must not be null");
		Assert.notNull(validator, "validator must not be null");
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		McpException missing = RequiredCapabilities.check(tool.requiredCapabilities(), ctx);
		if (missing != null) {
			return Mono.error(missing);
		}
		Map<String, Object> arguments = request.arguments() != null ? request.arguments() : Map.of();
		// Before input-schema validation, so a mismatch is a protocol error rather than a
		// tool execution error the model would try to fix.
		ParamHeaderValidator.validate(ctx, tool.definition(), arguments);
		CallToolResult invalidInput = validateInput(validator, tool.definition(), arguments);
		if (invalidInput != null) {
			return Mono.just(McpAsyncResponse.result(invalidInput));
		}
		return tool.handler()
			.call(ctx, request)
			.map(response -> validateOutput(validator, jsonMapper, tool.definition(), response));
	}

	/**
	 * Validates {@code arguments} against the {@code tool}'s {@code inputSchema}.
	 * @param validator the JSON schema validator
	 * @param tool the tool definition
	 * @param arguments the call arguments, or {@code null} if the call has none
	 * @return an {@code isError} {@link CallToolResult} when the arguments do not conform
	 * to {@code inputSchema}, or {@code null} when they are valid
	 */
	@Nullable
	public static CallToolResult validateInput(JsonSchemaValidator validator, Tool tool,
			@Nullable Map<String, Object> arguments) {
		Assert.notNull(validator, "validator must not be null");
		Assert.notNull(tool, "tool must not be null");
		ValidationResponse input = validator.validate(tool.inputSchema(), arguments != null ? arguments : Map.of());
		if (!input.valid()) {
			// A tool execution error, so the model can fix the call.
			return CallToolResult.builder()
				.addContent(TextContent.builder("Invalid arguments: " + input.errorMessage()).build())
				.isError(true)
				.build();
		}
		return null;
	}

	/**
	 * Validates a single or streaming tool response against the {@code tool}'s
	 * {@code outputSchema}, if declared, and populates {@code content} from
	 * {@code structuredContent} when {@code content} is empty.
	 */
	public static McpAsyncResponse<CallToolOutcome> validateOutput(JsonSchemaValidator validator,
			McpJsonMapper jsonMapper, Tool tool, McpAsyncResponse<CallToolOutcome> response) {
		Assert.notNull(validator, "validator must not be null");
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.notNull(tool, "tool must not be null");
		Assert.notNull(response, "response must not be null");
		if (response instanceof McpAsyncResponse.Result<CallToolOutcome> result) {
			return McpAsyncResponse.result(validateOutput(validator, jsonMapper, tool, result.result()));
		}
		McpAsyncResponse.Body<CallToolOutcome> body = ((McpAsyncResponse.Streaming<CallToolOutcome>) response).body();
		return McpAsyncResponse.streaming(
				notifier -> body.run(notifier).map(outcome -> validateOutput(validator, jsonMapper, tool, outcome)));
	}

	/**
	 * Validates a tool outcome against the {@code tool}'s {@code outputSchema}, if
	 * declared, and populates {@code content} from {@code structuredContent} when
	 * {@code content} is empty. Non-{@link CallToolResult} outcomes (such as
	 * {@code InputRequiredResult}) and error results ({@code isError == true}) are
	 * returned unchanged.
	 * @throws IllegalStateException if {@code tool} declares an {@code outputSchema} and
	 * the result has no {@code structuredContent} or its {@code structuredContent} does
	 * not conform to the schema
	 */
	public static CallToolOutcome validateOutput(JsonSchemaValidator validator, McpJsonMapper jsonMapper, Tool tool,
			CallToolOutcome outcome) {
		Assert.notNull(validator, "validator must not be null");
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.notNull(tool, "tool must not be null");
		Assert.notNull(outcome, "outcome must not be null");
		if (outcome instanceof CallToolResult result) {
			return validateOutput(validator, jsonMapper, tool, result);
		}
		return outcome;
	}

	/**
	 * Validates a tool result against the {@code tool}'s {@code outputSchema}, if
	 * declared, and populates {@code content} from {@code structuredContent} when
	 * {@code content} is empty. Error results ({@code isError == true}) are returned
	 * unchanged.
	 * @throws IllegalStateException if {@code tool} declares an {@code outputSchema} and
	 * the result has no {@code structuredContent} or its {@code structuredContent} does
	 * not conform to the schema
	 */
	// Nonconforming output is a server bug, so it fails as an internal error.
	public static CallToolResult validateOutput(JsonSchemaValidator validator, McpJsonMapper jsonMapper, Tool tool,
			CallToolResult result) {
		Assert.notNull(validator, "validator must not be null");
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.notNull(tool, "tool must not be null");
		Assert.notNull(result, "result must not be null");
		if (Boolean.TRUE.equals(result.isError())) {
			return result;
		}
		Object structuredContent = result.structuredContent();
		if (tool.outputSchema() != null) {
			if (structuredContent == null) {
				throw new IllegalStateException("Tool " + tool.name() + " returned no structuredContent");
			}
			ValidationResponse output = validator.validate(tool.outputSchema(), structuredContent);
			if (!output.valid()) {
				throw new IllegalStateException(
						"Tool " + tool.name() + " returned invalid structuredContent: " + output.errorMessage());
			}
		}
		if (structuredContent == null || !result.content().isEmpty()) {
			return result;
		}
		return new CallToolResult(List.of(TextContent.builder(toJson(jsonMapper, structuredContent)).build()),
				structuredContent, result.isError(), result.resultType(), result.meta());
	}

	private static String toJson(McpJsonMapper jsonMapper, Object value) {
		try {
			return jsonMapper.writeValueAsString(value);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
