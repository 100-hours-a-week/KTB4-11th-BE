package com.stock_spoon.river_be.user;

import com.stock_spoon.river_be.config.ProfileImageProperties;
import com.stock_spoon.river_be.user.dto.ProfileImageUploadRequest;
import com.stock_spoon.river_be.user.exception.*;
import com.stock_spoon.river_be.user.repository.UserRepository;
import com.stock_spoon.river_be.user.service.ProfileImageService;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileImageServiceTests {
    final S3Client s3 = mock(S3Client.class);
    final UserRepository users = mock(UserRepository.class);
    final String key = "profiles/123/12345678-1234-4234-8234-123456789abc.png";
    final String finalKey = "profiles/final/123/12345678-1234-4234-8234-123456789abc.png";
    final S3Presigner signer = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test-secret"))).build();
    ProfileImageService service;

    @BeforeEach void setup() {
        service = service(5242880, 20000000);
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).build());
    }

    @AfterEach void close() { signer.close(); }

    @Test void acceptsOwnValidS3ImageWithoutIssuanceHistory() throws Exception {
        byte[] bytes = png();
        uploaded(bytes, "image/png", bytes.length);
        var put = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        assertThat(service.prepare(123, key)).isEqualTo(finalKey);
        verify(s3).putObject(put.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertThat(put.getValue().key()).isEqualTo(finalKey);
        assertThat(put.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(put.getValue().contentType()).isEqualTo("image/png");
    }

    @Test void rejectsForeignAndMalformedKeysBeforeReadingS3() {
        for (String invalid : java.util.List.of(key.replace("/123/", "/1234/"),
                key.replace("/123/", "/999/"), "profiles/123/../999/image.png", finalKey,
                "profiles/123/image.png", "https://example.com/image.png", key + "?x=1")) {
            assertThatThrownBy(() -> service.prepare(123, invalid)).isInstanceOf(ProfileUpdateException.class);
        }
        verifyNoInteractions(s3);
    }

    @Test void issuesUrlForExistingUserWithoutChangingTheUser() {
        when(users.existsById(123L)).thenReturn(true);
        var result = service.issue(123, new ProfileImageUploadRequest("image/png", 100));
        assertThat(result.profileImageKey()).startsWith("profiles/123/").endsWith(".png");
        assertThat(result.presignedUrl()).contains("X-Amz-Signature=", "X-Amz-Expires=300");
        verify(users, never()).save(any());
    }

    @Test void rejectsDeletedUserBeforeIssuingUrl() {
        assertThatThrownBy(() -> service.issue(123, new ProfileImageUploadRequest("image/png", 100)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test void rejectsHeaderSizeMismatchAndFakeImages() throws Exception {
        byte[] bytes = png();
        uploaded(bytes, "image/png", bytes.length + 1L);
        assertThatThrownBy(() -> service.prepare(123, key)).isInstanceOf(ProfileUpdateException.class);
        uploaded("not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8), "image/png", 12);
        assertThatThrownBy(() -> service.prepare(123, key)).isInstanceOf(ProfileUpdateException.class);
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test void rejectsWrongContentTypeAndStorageFailure() throws Exception {
        byte[] bytes = png();
        uploaded(bytes, "image/jpeg", bytes.length);
        assertThatThrownBy(() -> service.prepare(123, key)).isInstanceOf(ProfileUpdateException.class);
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(503).build());
        assertThatThrownBy(() -> service.prepare(123, key))
                .isInstanceOfSatisfying(ProfileUpdateException.class, e -> assertThat(e.status().value()).isEqualTo(503));
    }

    @Test void reusesFinalObjectEvenIfTemporaryUploadIsGone() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        assertThat(service.prepare(123, key)).isEqualTo(finalKey);
        verify(s3, never()).getObject(any(GetObjectRequest.class));
    }

    @Test void resolvesPrivateImageButPreservesKakaoUrlAndNull() {
        assertThat(service.resolveUrl(finalKey)).contains("test-bucket.s3.ap-northeast-2.amazonaws.com", "X-Amz-Signature=");
        assertThat(service.resolveUrl("https://example.com/kakao.jpg")).isEqualTo("https://example.com/kakao.jpg");
        assertThat(service.resolveUrl(null)).isNull();
    }

    @Test void acceptsDecodedJpegAndConcurrentFinalCreation() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpeg", out);
        byte[] bytes = out.toByteArray();
        uploaded(bytes, "image/jpeg", bytes.length);
        when(s3.putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(412).build());
        assertThat(service.prepare(123, key.replace(".png", ".jpg"))).isEqualTo(finalKey.replace(".png", ".jpg"));
    }

    @Test void rejectsTooManyPixelsBeforeFinalWrite() throws Exception {
        byte[] bytes = png();
        uploaded(bytes, "image/png", bytes.length);
        service = service(5242880, 1);
        assertThatThrownBy(() -> service.prepare(123, key)).isInstanceOf(ProfileUpdateException.class);
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    @Test void rejectsTruncatedAndOversizedStreamsEvenWhenHeaderMatches() {
        service = service(200, 20000000);
        for (int byteCount : new int[] { 99, 201 }) {
            uploaded(new byte[byteCount], "image/png", 100);
            assertThatThrownBy(() -> service.prepare(123, key)).isInstanceOf(ProfileUpdateException.class);
        }
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
    }

    ProfileImageService service(int maxBytes, long maxPixels) {
        return new ProfileImageService(users, s3, signer,
                new ProfileImageProperties("test-bucket", "ap-northeast-2", maxBytes, 300, 900, maxPixels));
    }

    void uploaded(byte[] bytes, String type, long length) {
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(call -> new ResponseInputStream<>(
                GetObjectResponse.builder().contentType(type).contentLength(length).build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes))));
    }

    static byte[] png() throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
