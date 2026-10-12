package com.stock_spoon.river_be.config;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.*;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElastiCacheIamCredentialsTests {
    @Test
    void signsServerlessCacheAndSessionCredentialsWithoutEndpointOrScheme() {
        var aws = mock(AwsCredentialsProvider.class);
        when(aws.resolveCredentials()).thenReturn(AwsSessionCredentials.create("test-access", "test-secret", "test-session"));
        var provider = new ElastiCacheIamCredentials(aws, "stockspoon-v2-dev-valkey", "backend-user",
                "ap-northeast-2", Clock.fixed(Instant.parse("2026-10-12T01:00:00Z"), ZoneOffset.UTC));
        var credentials = provider.resolveCredentials().block(Duration.ofSeconds(5));
        assertThat(credentials.getUsername()).isEqualTo("backend-user");
        String token = new String(credentials.getPassword());
        assertThat(token).startsWith("stockspoon-v2-dev-valkey/?").doesNotContain("http://", ".amazonaws.com");
        var query = parameters(token);
        assertThat(query).containsEntry("Action", "connect").containsEntry("User", "backend-user")
                .containsEntry("ResourceType", "ServerlessCache").containsEntry("X-Amz-Expires", "900")
                .containsEntry("X-Amz-Date", "20261012T010000Z").containsEntry("X-Amz-Security-Token", "test-session");
        assertThat(query.get("X-Amz-Credential")).contains("/ap-northeast-2/elasticache/aws4_request");
        assertThat(query.get("X-Amz-Signature")).matches("[0-9a-f]{64}");
    }

    @Test
    void initialStreamingFailureDoesNotPreventNextTickRenewal() {
        var aws = mock(AwsCredentialsProvider.class);
        when(aws.resolveCredentials()).thenThrow(new IllegalStateException("test failure"))
                .thenReturn(AwsBasicCredentials.create("renewed", "test-secret"));
        var provider = new ElastiCacheIamCredentials(aws, "test-cache", "backend-user", "ap-northeast-2", Clock.systemUTC());
        StepVerifier.withVirtualTime(provider::credentials).thenAwait(Duration.ofMinutes(10))
                .expectNextMatches(value -> parameters(new String(value.getPassword())).get("X-Amz-Credential").startsWith("renewed/"))
                .thenCancel().verify(Duration.ofSeconds(10));
    }

    @Test
    void missingInitialConnectionCredentialsFailAuthentication() {
        var aws = mock(AwsCredentialsProvider.class);
        when(aws.resolveCredentials()).thenThrow(new IllegalStateException("test failure"));
        var provider = new ElastiCacheIamCredentials(aws, "test-cache", "backend-user", "ap-northeast-2", Clock.systemUTC());
        StepVerifier.create(provider.resolveCredentials()).expectError(IllegalStateException.class).verify(Duration.ofSeconds(5));
    }

    @Test
    void streamingRenewsWithNewAwsCredentialsAndRecoversAfterFailure() {
        var aws = mock(AwsCredentialsProvider.class);
        when(aws.resolveCredentials()).thenReturn(AwsBasicCredentials.create("first", "test-secret"))
                .thenThrow(new IllegalStateException("test failure"))
                .thenReturn(AwsBasicCredentials.create("renewed", "test-secret"));
        var provider = new ElastiCacheIamCredentials(aws, "test-cache", "backend-user", "ap-northeast-2", Clock.systemUTC());
        assertThat(provider.supportsStreaming()).isTrue();
        StepVerifier.withVirtualTime(provider::credentials)
                .expectNextMatches(value -> parameters(new String(value.getPassword())).get("X-Amz-Credential").startsWith("first/"))
                .thenAwait(Duration.ofMinutes(10))
                .thenAwait(Duration.ofMinutes(10))
                .expectNextMatches(value -> parameters(new String(value.getPassword())).get("X-Amz-Credential").startsWith("renewed/"))
                .thenCancel().verify(Duration.ofSeconds(10));
        verify(aws, times(3)).resolveCredentials();
    }

    static Map<String, String> parameters(String token) {
        var values = new HashMap<String, String>();
        for (String pair : URI.create("http://" + token).getRawQuery().split("&")) {
            var parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return values;
    }
}
