/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpFeature;
import io.modelcontextprotocol.modern.server.McpParamHeaders;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.ModernTestFixtures;
import io.modelcontextprotocol.util.ToolsUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.PERMISSIVE_VALIDATOR;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ToolCallsTests {

	private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

	private static final Map<String, Object> OUTPUT_SCHEMA = Map.of("type", "object", "required", List.of("n"));

	private static final Tool STRUCTURED_TOOL = Tool.builder("structured", ToolsUtils.EMPTY_JSON_SCHEMA)
		.outputSchema(OUTPUT_SCHEMA)
		.build();

	private static JsonSchemaValidator rejecting(Map<String, Object> rejected) {
		return (schema, content) -> schema.equals(rejected) ? ValidationResponse.asInvalid("rejected")
				: ValidationResponse.asValid(null);
	}

	@Test
	void validateInputReturnsNullWhenValidAndErrorResultWhenInvalid() {
		assertThat(ToolCalls.validateInput(PERMISSIVE_VALIDATOR, STRUCTURED_TOOL, Map.of("x", 1))).isNull();

		CallToolResult invalid = ToolCalls.validateInput(rejecting(ToolsUtils.EMPTY_JSON_SCHEMA), STRUCTURED_TOOL,
				null);
		assertThat(invalid).isNotNull();
		assertThat(invalid.isError()).isTrue();
		assertThat(((TextContent) invalid.content().get(0)).text()).isEqualTo("Invalid arguments: rejected");
	}

	@Test
	void validateOutputPopulatesTextFallbackAndValidatesSchema() {
		CallToolResult populated = ToolCalls.validateOutput(PERMISSIVE_VALIDATOR, MAPPER, STRUCTURED_TOOL,
				CallToolResult.builder().structuredContent(Map.of("n", 42)).build());
		assertThat(populated.content()).containsExactly(TextContent.builder("{\"n\":42}").build());

		CallToolResult existingContent = CallToolResult.builder()
			.addContent(TextContent.builder("custom").build())
			.structuredContent(Map.of("n", 42))
			.build();
		assertThat(ToolCalls.validateOutput(PERMISSIVE_VALIDATOR, MAPPER, STRUCTURED_TOOL, existingContent))
			.isSameAs(existingContent);

		CallToolResult errorResult = CallToolResult.builder()
			.addContent(TextContent.builder("failed").build())
			.isError(true)
			.build();
		assertThat(ToolCalls.validateOutput(rejecting(OUTPUT_SCHEMA), MAPPER, STRUCTURED_TOOL, errorResult))
			.isSameAs(errorResult);

		InputRequiredResult inputRequired = InputRequiredResult.builder()
			.elicit("k", ElicitFormRequest.builder("msg", ToolsUtils.EMPTY_JSON_SCHEMA).build())
			.build();
		assertThat(ToolCalls.validateOutput(rejecting(OUTPUT_SCHEMA), MAPPER, STRUCTURED_TOOL,
				(CallToolOutcome) inputRequired))
			.isSameAs(inputRequired);

		assertThatIllegalStateException()
			.isThrownBy(() -> ToolCalls.validateOutput(PERMISSIVE_VALIDATOR, MAPPER, STRUCTURED_TOOL,
					CallToolResult.builder().build()))
			.withMessageContaining("no structuredContent");

		assertThatIllegalStateException()
			.isThrownBy(() -> ToolCalls.validateOutput(rejecting(OUTPUT_SCHEMA), MAPPER, STRUCTURED_TOOL,
					CallToolResult.builder().structuredContent(Map.of("n", 1)).build()))
			.withMessageContaining("invalid structuredContent: rejected");
	}

	@Test
	void customFeatureCanComposeBuildingBlocksDirectly() {
		Tool tool = Tool.builder("regional", MAPPER, """
				{
				  "type": "object",
				  "properties": {
				    "region": { "type": "string", "x-mcp-header": "Region" }
				  }
				}
				""").outputSchema(OUTPUT_SCHEMA).build();
		ClientCapabilities needsSampling = ClientCapabilities.builder().sampling().build();

		McpFeature customFeature = new McpFeature() {
			@Override
			public Set<String> methods() {
				return Set.of("custom/call");
			}

			@Override
			@SuppressWarnings("unchecked")
			public Mono<? extends McpAsyncResponse<? extends Result>> handle(McpRequestContext ctx, Object params) {
				McpException missing = RequiredCapabilities.check(needsSampling, ctx);
				if (missing != null) {
					return Mono.error(missing);
				}
				Map<String, Object> arguments = (Map<String, Object>) ((Map<String, Object>) params).get("arguments");
				ParamHeaderValidator.validate(ctx, tool, arguments);
				CallToolResult invalid = ToolCalls.validateInput(PERMISSIVE_VALIDATOR, tool, arguments);
				if (invalid != null) {
					return Mono.just(McpAsyncResponse.result(invalid));
				}
				CallToolResult raw = CallToolResult.builder().structuredContent(Map.of("n", 7)).build();
				return Mono
					.just(McpAsyncResponse.result(ToolCalls.validateOutput(PERMISSIVE_VALIDATOR, MAPPER, tool, raw)));
			}
		};

		McpServer server = McpServer.builder().serverInfo(SERVER_INFO).feature(customFeature).build();

		// 1. Missing required capability -> -32021
		JSONRPCRequest noCapReq = new JSONRPCRequest("custom/call", 1,
				Map.of("_meta", meta(), "arguments", Map.of("region", "eu-west1")));
		McpTransportContext withHeader = McpTransportContext.create(Map.of(McpParamHeaders.TRANSPORT_CONTEXT_KEY,
				McpParamHeaders.builder().value("Region", "eu-west1").build()));
		JSONRPCResponse noCapResp = ModernTestFixtures.respond(server, withHeader, noCapReq).block();
		assertThat(noCapResp.error().code()).isEqualTo(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY);

		// 2. Mismatched Mcp-Param header -> -32020
		JSONRPCRequest withCapReq = new JSONRPCRequest("custom/call", 2,
				Map.of("_meta", meta(MetaKeys.CLIENT_CAPABILITIES, Map.of("sampling", Map.of())), "arguments",
						Map.of("region", "eu-west1")));
		McpTransportContext wrongHeader = McpTransportContext.create(Map.of(McpParamHeaders.TRANSPORT_CONTEXT_KEY,
				McpParamHeaders.builder().value("Region", "us-east1").build()));
		JSONRPCResponse wrongHeaderResp = ModernTestFixtures.respond(server, wrongHeader, withCapReq).block();
		assertThat(wrongHeaderResp.error().code()).isEqualTo(ErrorCodes.HEADER_MISMATCH);

		// 3. Valid call -> output validated and text fallback populated
		JSONRPCResponse okResp = ModernTestFixtures.respond(server, withHeader, withCapReq).block();
		assertThat(okResp.error()).isNull();
		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) okResp.result();
		assertThat(result.get("structuredContent")).isEqualTo(Map.of("n", 7));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
		assertThat(content.get(0).get("text")).isEqualTo("{\"n\":7}");
	}

}
