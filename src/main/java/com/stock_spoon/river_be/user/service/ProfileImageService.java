package com.stock_spoon.river_be.user.service;

import com.stock_spoon.river_be.config.ProfileImageProperties;
import com.stock_spoon.river_be.user.dto.*;
import com.stock_spoon.river_be.user.exception.*;
import com.stock_spoon.river_be.user.repository.*;
import java.io.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

@Service
public class ProfileImageService {
    private static final Map<String, String> TYPES = Map.of("image/png", "png", "image/jpeg", "jpg");
    private final UserRepository users;
    private final S3Client s3;
    private final S3Presigner signer;
    private final ProfileImageProperties properties;

    public ProfileImageService(UserRepository users,
            S3Client profileImageS3Client, S3Presigner profileImageS3Presigner,
            ProfileImageProperties properties) {
        this.users = users;
        this.s3 = profileImageS3Client;
        this.signer = profileImageS3Presigner;
        this.properties = properties;
    }

    public ProfileImageUploadResponse issue(long userId, ProfileImageUploadRequest request) {
        if (!TYPES.containsKey(request.contentType()) || request.contentLength() < 1
                || request.contentLength() > properties.maxBytes()) {
            throw ProfileUpdateException.invalid("지원하는 이미지 형식과 최대 파일 크기를 확인해주세요.");
        }
        if (!users.existsById(userId)) throw new UserNotFoundException();
        configured();
        String name = UUID.randomUUID() + "." + TYPES.get(request.contentType());
        String key = temporaryPrefix(userId) + name;
        String url;
        try {
            var put = PutObjectRequest.builder().bucket(properties.bucket()).key(key)
                    .contentType(request.contentType()).contentLength(request.contentLength()).build();
            url = signer.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(properties.uploadTtlSeconds())).putObjectRequest(put).build())
                    .url().toString();
        } catch (SdkException error) {
            throw ProfileUpdateException.unavailable();
        }
        return new ProfileImageUploadResponse(url, key);
    }

    public String prepare(long userId, String key) {
        String prefix = temporaryPrefix(userId);
        if (key == null || !key.startsWith(prefix)) throw ProfileUpdateException.invalidImage();
        String filename = key.substring(prefix.length());
        if (!filename.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg)")) {
            throw ProfileUpdateException.invalidImage();
        }
        String contentType = filename.endsWith(".png") ? "image/png" : "image/jpeg";
        String finalKey = finalPrefix() + userId + "/" + filename;
        configured();
        try {
            byte[] bytes;
            try (var stream = s3.getObject(GetObjectRequest.builder().bucket(properties.bucket()).key(key).build())) {
                var response = stream.response();
                if (response.contentLength() == null || response.contentLength() < 1
                        || response.contentLength() > properties.maxBytes()
                        || !contentType.equals(response.contentType())) {
                    stream.abort();
                    throw ProfileUpdateException.invalidImage();
                }
                bytes = stream.readNBytes(properties.maxBytes() + 1);
                if (bytes.length != response.contentLength() || bytes.length > properties.maxBytes()) {
                    stream.abort();
                    throw ProfileUpdateException.invalidImage();
                }
            }
            validateImage(bytes, contentType);
            // 검증한 바이트만 최종 키에 저장하여 업로드 URL 재사용으로 이미지가 바뀌지 않게 한다.
            var put = PutObjectRequest.builder().bucket(properties.bucket()).key(finalKey)
                    .contentType(contentType).ifNoneMatch("*").build();
            try {
                s3.putObject(put, RequestBody.fromBytes(bytes));
            } catch (S3Exception error) {
                // 동시 요청도 이미 검증해서 저장한 최종 객체를 덮어쓰지 않는다.
                if (error.statusCode() != 412) throw error;
            }
            return finalKey;
        } catch (S3Exception error) {
            if (error.statusCode() == 404) throw ProfileUpdateException.invalidImage();
            throw ProfileUpdateException.unavailable();
        } catch (SdkException | IOException error) {
            throw ProfileUpdateException.unavailable();
        }
    }

    public String resolveUrl(String ref) {
        if (ref == null || !ref.startsWith(finalPrefix())) return ref;
        configured();
        if (properties.publicBaseUrl().isBlank()) throw ProfileUpdateException.unavailable();
        return properties.publicBaseUrl() + ref.substring(properties.keyPrefix().length());
    }

    private String temporaryPrefix(long userId) { return properties.keyPrefix() + userId + "/"; }

    private String finalPrefix() { return properties.keyPrefix() + "final/"; }

    private void validateImage(byte[] bytes, String contentType) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw ProfileUpdateException.invalidImage();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String actual = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(contentType.equals("image/png") && actual.equals("png"))
                        && !(contentType.equals("image/jpeg") && actual.equals("jpeg"))) {
                    throw ProfileUpdateException.invalidImage();
                }
                if ((long) reader.getWidth(0) * reader.getHeight(0) > properties.maxPixels()) {
                    throw ProfileUpdateException.invalidImage();
                }
                if (reader.read(0) == null) throw ProfileUpdateException.invalidImage();
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException error) {
            throw ProfileUpdateException.invalidImage();
        }
    }

    private void configured() {
        if (properties.bucket().isBlank()) throw ProfileUpdateException.unavailable();
    }
}
