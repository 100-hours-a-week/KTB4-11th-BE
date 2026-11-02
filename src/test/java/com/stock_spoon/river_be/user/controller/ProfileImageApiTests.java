package com.stock_spoon.river_be.user.controller;

import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.user.entity.*;
import com.stock_spoon.river_be.user.repository.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "profile-image.bucket=test-bucket")
@Transactional
class ProfileImageApiTests {
    @Autowired WebApplicationContext context;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired JsonMapper json;
    @MockitoBean S3Client s3;
    @MockitoBean S3Presigner signer;
    @MockitoBean com.stock_spoon.river_be.auth.service.JwtUserExistenceVerifier authLookup;
    S3Presigner actualSigner;
    MockMvc mvc;
    User me;
    Cookie access;

    @BeforeEach void setup() {
        actualSigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test-secret"))).build();
        when(signer.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenAnswer(call -> actualSigner.presignPutObject(call.getArgument(0, PutObjectPresignRequest.class)));
        when(signer.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenAnswer(call -> actualSigner.presignGetObject(call.getArgument(0, GetObjectPresignRequest.class)));
        when(authLookup.exists(anyLong())).thenAnswer(call -> users.existsById(call.getArgument(0, Long.class)));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        me = users.saveAndFlush(new User("기존", "https://example.com/old.png"));
        access = new Cookie("access_token", tokens.issue(me.getId()).accessToken());
    }

    @AfterEach void close() { actualSigner.close(); }

    @Test void issuesThenUpdatesBothFieldsAndReturnsDisplayUrl() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        byte[] bytes = out.toByteArray();
        String key = issue(bytes.length);
        assertThat(me.getProfileImageRef()).isEqualTo("https://example.com/old.png");
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(call -> new ResponseInputStream<>(
                GetObjectResponse.builder().contentType("image/png").contentLength((long) bytes.length).build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes))));
        mvc.perform(patch("/api/v1/users/me").cookie(access).with(csrf()).contentType("application/json")
                        .content("{\"nickname\":\"변경\",\"profile_image_key\":\"" + key + "\"}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"message\":\"success\"}"));
        users.flush();
        assertThat(me.getProfileImageRef()).startsWith("profiles/final/" + me.getId() + "/");
        mvc.perform(get("/api/v1/users/me").cookie(access)).andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("변경"))
                .andExpect(jsonPath("$.profile_image_url").value(org.hamcrest.Matchers.containsString("X-Amz-Signature=")));
    }

    @Test void rejectsAnotherUsersIssuedKeyAndKeepsBothFields() throws Exception {
        String key = issue(100);
        var other = users.saveAndFlush(new User("다른 회원"));
        mvc.perform(patch("/api/v1/users/me").cookie(new Cookie("access_token", tokens.issue(other.getId()).accessToken()))
                        .with(csrf()).contentType("application/json")
                        .content("{\"nickname\":\"공격\",\"profile_image_key\":\"" + key + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(other.getNickname()).isEqualTo("다른 회원");
        assertThat(other.getProfileImageRef()).isNull();
        verifyNoInteractions(s3);
    }

    @Test void rejectsMissingUploadWithoutChangingNickname() throws Exception {
        String key = issue(100);
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());
        mvc.perform(patch("/api/v1/users/me").cookie(access).with(csrf()).contentType("application/json")
                        .content("{\"nickname\":\"변경\",\"profile_image_key\":\"" + key + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(me.getNickname()).isEqualTo("기존");
        assertThat(me.getProfileImageRef()).isEqualTo("https://example.com/old.png");
    }

    @Test void rejectsInvalidUploadRequestsAndUnauthenticatedIssuance() throws Exception {
        for (String body : java.util.List.of("{}", "{\"content_type\":\"image/svg+xml\",\"content_length\":100}",
                "{\"content_type\":\"image/png\",\"content_length\":0}",
                "{\"content_type\":\"image/png\",\"content_length\":5242881}",
                "{\"content_type\":\"image/png\",\"content_length\":\"100\"}",
                "{\"content_type\":\"image/png\",\"content_length\":1.5}")) {
            mvc.perform(post("/api/v1/users/me/profile-image/presigned-url").cookie(access).with(csrf())
                            .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/users/me/profile-image/presigned-url").with(csrf()).contentType("application/json")
                        .content("{\"content_type\":\"image/png\",\"content_length\":100}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(signer, s3);
    }

    @Test void databaseFailureAfterS3DoesNotChangeTheSavedProfile() throws Exception {
        String key = issue(100);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
                .when(users).findLockedById(me.getId());
        mvc.perform(patch("/api/v1/users/me").cookie(access).with(csrf()).contentType("application/json")
                        .content("{\"nickname\":\"변경\",\"profile_image_key\":\"" + key + "\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("USER_STORAGE_UNAVAILABLE"));
        assertThat(me.getNickname()).isEqualTo("기존");
        assertThat(me.getProfileImageRef()).isEqualTo("https://example.com/old.png");
    }

    String issue(int length) throws Exception {
        String response = mvc.perform(post("/api/v1/users/me/profile-image/presigned-url").cookie(access).with(csrf())
                        .contentType("application/json").content("{\"content_type\":\"image/png\",\"content_length\":" + length + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var node = json.readTree(response);
        assertThat(node.propertyNames()).containsExactlyInAnyOrder("presigned_url", "profile_image_key");
        assertThat(node.path("presigned_url").stringValue()).contains("X-Amz-Signature=", "X-Amz-Expires=300");
        String key = node.path("profile_image_key").stringValue();
        assertThat(key).startsWith("profiles/" + me.getId() + "/").endsWith(".png");
        return key;
    }
}
