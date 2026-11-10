/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.util.Assert;
import reactor.util.annotation.Nullable;

/**
 * Checks that the client declared the capabilities a primitive requires before its
 * handler runs.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class RequiredCapabilities {

	private RequiredCapabilities() {
	}

	/**
	 * Returns a {@code -32021} {@link McpException} listing the {@code required}
	 * capabilities the client did not declare on {@code ctx}, or {@code null} if it
	 * declared all of them.
	 * @param required the capabilities required by the primitive
	 * @param ctx the current request context
	 * @return an {@link McpException} for the missing capabilities, or {@code null}
	 */
	@Nullable
	public static McpException check(ClientCapabilities required, McpRequestContext ctx) {
		Assert.notNull(required, "required must not be null");
		Assert.notNull(ctx, "ctx must not be null");
		ClientCapabilities missing = missing(required, ctx.clientCapabilities());
		return missing != null ? McpException.missingClientCapability(missing) : null;
	}

	private static ClientCapabilities missing(ClientCapabilities required, ClientCapabilities declared) {
		boolean roots = required.supportsRoots() && !declared.supportsRoots();
		boolean sampling = required.supportsSampling() && !declared.supportsSampling();
		boolean form = required.supportsElicitationForm() && !declared.supportsElicitationForm();
		boolean url = required.supportsElicitationUrl() && !declared.supportsElicitationUrl();
		Map<String, Object> experimental = missingKeys(required.experimental(), declared.experimental());
		Map<String, Map<String, Object>> extensions = missingKeys(required.extensions(), declared.extensions());
		if (!roots && !sampling && !form && !url && experimental == null && extensions == null) {
			return null;
		}
		ClientCapabilities.Builder builder = ClientCapabilities.builder()
			.experimental(experimental)
			.extensions(extensions);
		if (roots) {
			builder.roots();
		}
		if (sampling) {
			builder.sampling();
		}
		if (form || url) {
			builder
				.elicitation(new ClientCapabilities.Elicitation(form ? new ClientCapabilities.Elicitation.Form() : null,
						url ? new ClientCapabilities.Elicitation.Url() : null));
		}
		return builder.build();
	}

	private static <V> Map<String, V> missingKeys(Map<String, V> required, Map<String, V> declared) {
		if (required == null) {
			return null;
		}
		Map<String, V> missing = new LinkedHashMap<>(required);
		if (declared != null) {
			missing.keySet().removeAll(declared.keySet());
		}
		return missing.isEmpty() ? null : missing;
	}

}
