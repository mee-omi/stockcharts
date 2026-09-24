package com.stockcharts.auth;

import com.stockcharts.marketdata.MarketDataClient;
import com.stockcharts.trading.TradingClient;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"stockcharts.auth.enabled=true", "stockcharts.rate-limit.auth-per-minute=1000"})
class AuthIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AppUserRepository users;

    @MockBean
    private MarketDataClient marketData;

    @MockBean
    private TradingClient trading;

    @BeforeEach
    void reset() {
        users.deleteAll();
    }

    private static String creds(String u, String p) {
        return "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}";
    }

    private Cookie setup() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/setup").contentType(MediaType.APPLICATION_JSON)
                        .content(creds("trader", "correct-horse-1")))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andReturn();
        Cookie c = r.getResponse().getCookie(AuthFilter.COOKIE);
        assertNotNull(c);
        return c;
    }

    @Test
    void protectedRoutesNeedASession() throws Exception {
        mvc.perform(get("/api/v1/auth/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.authEnabled").value(true))
                .andExpect(jsonPath("$.setupRequired").value(true))
                .andExpect(jsonPath("$.authenticated").value(false));
        mvc.perform(get("/api/v1/instruments")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Login required"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());

        Cookie session = setup();
        mvc.perform(get("/api/v1/instruments").cookie(session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/status").cookie(session))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("trader"));
        mvc.perform(get("/api/v1/instruments").cookie(new Cookie(AuthFilter.COOKIE, session.getValue() + "x")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setupRunsOnceAndValidatesInput() throws Exception {
        mvc.perform(post("/api/v1/auth/setup").contentType(MediaType.APPLICATION_JSON)
                        .content(creds("trader", "short")))
                .andExpect(status().isBadRequest());
        setup();
        mvc.perform(post("/api/v1/auth/setup").contentType(MediaType.APPLICATION_JSON)
                        .content(creds("intruder", "another-pass-99")))
                .andExpect(status().isConflict());
    }

    @Test
    void loginLogoutAndPasswordChange() throws Exception {
        Cookie first = setup();
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(creds("trader", "wrong-password")))
                .andExpect(status().isUnauthorized());
        Cookie login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(creds("TRADER", "correct-horse-1")))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(AuthFilter.COOKIE);
        assertNotNull(login);

        mvc.perform(post("/api/v1/auth/password").cookie(login).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"nope-nope-nope\",\"newPassword\":\"brand-new-pass-2\"}"))
                .andExpect(status().isBadRequest());
        Cookie changed = mvc.perform(post("/api/v1/auth/password").cookie(login)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"correct-horse-1\",\"newPassword\":\"brand-new-pass-2\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(AuthFilter.COOKIE);
        assertNotNull(changed);

        // Sessions issued before the change are revoked; the new one works.
        mvc.perform(get("/api/v1/instruments").cookie(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/instruments").cookie(login)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/instruments").cookie(changed)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/logout").cookie(changed))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    @Test
    void tokensAreBoundToTheirPurpose(@Autowired SignedTokens tokens) {
        String t = tokens.sign("order-confirm", "trader:abc", java.time.Instant.now().plusSeconds(60));
        org.junit.jupiter.api.Assertions.assertTrue(tokens.verify("session", t).isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals("trader:abc", tokens.verify("order-confirm", t).orElseThrow());
        String expired = tokens.sign("session", "x", java.time.Instant.now().minusSeconds(1));
        org.junit.jupiter.api.Assertions.assertTrue(tokens.verify("session", expired).isEmpty());
    }
}
