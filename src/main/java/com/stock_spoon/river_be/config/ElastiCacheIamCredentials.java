package com.stock_spoon.river_be.config;

import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;

/** 새 연결과 장시간 유지되는 Pub/Sub 연결에 Serverless IAM 자격을 제공한다. */
final class ElastiCacheIamCredentials implements RedisCredentialsProvider {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ElastiCacheIamCredentials.class);
    private final AwsCredentialsProvider aws;
    private final String cache;
    private final String user;
    private final String region;
    private final Clock clock;

    ElastiCacheIamCredentials(AwsCredentialsProvider aws, String cache, String user, String region, Clock clock) {
        this.aws = aws;
        this.cache = cache.trim().toLowerCase(Locale.ROOT);
        this.user = user;
        this.region = region;
        this.clock = clock;
    }

    public Mono<RedisCredentials> resolveCredentials() {
        // 인스턴스 자격 조회가 네트워크를 사용할 수 있으므로 Netty 이벤트 루프에서 실행하지 않는다.
        return Mono.fromCallable(this::sign).subscribeOn(Schedulers.boundedElastic());
    }

    private RedisCredentials sign() {
        var request = SdkHttpFullRequest.builder().uri(URI.create("http://" + cache + "/"))
                .method(SdkHttpMethod.GET).appendRawQueryParameter("Action", "connect")
                .appendRawQueryParameter("User", user).appendRawQueryParameter("ResourceType", "ServerlessCache").build();
        var signed = AwsV4HttpSigner.create().sign(builder -> builder.identity(aws.resolveCredentials()).request(request)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "elasticache")
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, AwsV4HttpSigner.AuthLocation.QUERY_STRING)
                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, Duration.ofMinutes(15))
                .putProperty(AwsV4HttpSigner.SIGNING_CLOCK, clock));
        String token = signed.request().getUri().toString().substring("http://".length());
        return RedisCredentials.just(user, token.toCharArray());
    }

    public boolean supportsStreaming() { return true; }

    public Flux<RedisCredentials> credentials() {
        return Flux.concat(refresh(), Flux.interval(Duration.ofMinutes(10)).concatMap(tick -> refresh()));
    }

    private Mono<RedisCredentials> refresh() {
        return resolveCredentials().onErrorResume(error -> {
            log.warn("event=redis_iam_refresh_failed causeType={} stage=credentials_refresh nestedCauseType={}",
                    error.getClass().getSimpleName(),
                    error.getCause() == null ? "none" : error.getCause().getClass().getSimpleName());
            return Mono.empty();
        });
    }
}
