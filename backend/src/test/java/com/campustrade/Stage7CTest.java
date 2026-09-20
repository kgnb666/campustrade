package com.campustrade;

import com.campustrade.entity.User;
import com.campustrade.event.ReviewCreatedEvent;
import com.campustrade.listener.CreditReviewEventListener;
import com.campustrade.security.CurrentUserMethodArgumentResolver;
import com.campustrade.security.JwtTokenProvider;
import com.campustrade.security.SecurityUtils;
import com.campustrade.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 7-C 验收测试套件
 */
@SpringBootTest
@AutoConfigureMockMvc
public class Stage7CTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private CurrentUserMethodArgumentResolver argumentResolver;

    @Autowired
    private CreditReviewEventListener creditReviewEventListener;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("测试1: 未登录访客直接访问商品评价列表放行 200 OK")
    void test01_publicReviewsAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/reviews/goods/99999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/reviews/goods/99999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("测试2: 普通 USER 访问商品浏览量同步接口被阻断 403 Forbidden")
    void test02_syncViewsForbiddenForNormalUser() throws Exception {
        String userToken = jwtTokenProvider.generateAccessToken(88001L, "stage7c_normal_user", "USER");

        mockMvc.perform(post("/goods/sync-views")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/goods/sync-views")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("测试3: 管理员 ADMIN 访问商品浏览量同步接口成功 200 OK")
    void test03_syncViewsAllowedForAdmin() throws Exception {
        String adminToken = jwtTokenProvider.generateAccessToken(88002L, "stage7c_admin_user", "ADMIN");

        mockMvc.perform(post("/goods/sync-views")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/goods/sync-views")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("测试4: UserPrincipal 与 SecurityUtils 零 DB-I/O 凭据提取")
    void test04_userPrincipalAndSecurityUtils() {
        UserPrincipal principal = UserPrincipal.builder()
                .id(778899L)
                .username("test_principal")
                .role("USER")
                .enabled(true)
                .build();

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertEquals(778899L, SecurityUtils.getCurrentUserId());
        assertEquals(778899L, SecurityUtils.getCurrentUserIdOrNull());
        assertEquals("test_principal", SecurityUtils.getCurrentUsername());
        assertNotNull(SecurityUtils.getCurrentUserPrincipal());
        assertEquals("test_principal", SecurityUtils.getCurrentUserPrincipal().getUsername());
    }

    @Test
    @DisplayName("测试5: CurrentUserMethodArgumentResolver 快速通道解析轻量 User 实体")
    void test05_currentUserResolverFastPath() throws Exception {
        UserPrincipal principal = UserPrincipal.builder()
                .id(556677L)
                .username("fast_user")
                .role("ADMIN")
                .enabled(true)
                .build();

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        MethodParameter userParam = new MethodParameter(
                this.getClass().getDeclaredMethod("dummyMethod", User.class, UserPrincipal.class), 0
        );
        MethodParameter principalParam = new MethodParameter(
                this.getClass().getDeclaredMethod("dummyMethod", User.class, UserPrincipal.class), 1
        );

        assertTrue(argumentResolver.supportsParameter(userParam));
        assertTrue(argumentResolver.supportsParameter(principalParam));

        Object resolvedUser = argumentResolver.resolveArgument(userParam, null, null, null);
        assertNotNull(resolvedUser);
        assertInstanceOf(User.class, resolvedUser);
        User u = (User) resolvedUser;
        assertEquals(556677L, u.getId());
        assertEquals("fast_user", u.getUsername());
        assertEquals("ADMIN", u.getRole());

        Object resolvedPrincipal = argumentResolver.resolveArgument(principalParam, null, null, null);
        assertNotNull(resolvedPrincipal);
        assertInstanceOf(UserPrincipal.class, resolvedPrincipal);
        assertEquals(556677L, ((UserPrincipal) resolvedPrincipal).getId());
    }

    @Test
    @DisplayName("测试6: CreditReviewEventListener 领域事件安全消费与降级")
    void test06_creditReviewEventListener() {
        ReviewCreatedEvent validEvent = ReviewCreatedEvent.builder()
                .reviewId(991122L)
                .reviewedUserId(88001L)
                .reviewerId(88002L)
                .orderId(771122L)
                .score(5)
                .build();

        assertDoesNotThrow(() -> creditReviewEventListener.handleReviewCreated(validEvent));

        ReviewCreatedEvent invalidEvent = ReviewCreatedEvent.builder().build();
        assertDoesNotThrow(() -> creditReviewEventListener.handleReviewCreated(invalidEvent));
    }

    private void dummyMethod(@com.campustrade.common.annotation.CurrentUser User user,
                             @com.campustrade.common.annotation.CurrentUser UserPrincipal principal) {
    }
}