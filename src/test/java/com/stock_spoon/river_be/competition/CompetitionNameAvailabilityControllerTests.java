package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class CompetitionNameAvailabilityControllerTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;

    @Test
    void returnsAvailabilityForTheAuthenticatedUsersAccounts() throws Exception {
        var user = users.save(new User("대결명 확인"));
        var account = new Account(user, "반도체", 1000000, false);
        account.deactivate(); // 종료 대결처럼 비활성화된 계좌도 중복에 포함한다.
        accounts.save(account);
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String path = "/api/v3/users/me/competitions/name-availability";

        mvc.perform(get(path).with(jwt().jwt(j -> j.subject(user.getId().toString())))
                        .param("competition_name", " 반도체 "))
                .andExpect(status().isOk()).andExpect(content().json("{\"available\":false}", true));
        mvc.perform(get(path).with(jwt().jwt(j -> j.subject(user.getId().toString())))
                        .param("competition_name", "새 대결"))
                .andExpect(status().isOk()).andExpect(content().json("{\"available\":true}", true));
        mvc.perform(get(path).with(jwt().jwt(j -> j.subject(user.getId().toString())))
                        .param("competition_name", ""))
                .andExpect(status().isOk()).andExpect(content().json("{\"available\":false}", true));
        mvc.perform(get(path).with(jwt().jwt(j -> j.subject(user.getId().toString()))))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path).param("competition_name", "새 대결"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void comparesOnlyTheUsersNamesAndChecksLengthBeforeTrimming() throws Exception {
        var user = users.save(new User("이름 확인 사용자"));
        var other = users.save(new User("다른 사용자"));
        accounts.save(new Account(user, "Tech", 1000000, false));
        accounts.save(new Account(other, "다른 사람 이름", 1000000, false));
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String path = "/api/v3/users/me/competitions/name-availability";

        mvc.perform(get(path).with(jwt().jwt(j -> j.subject(user.getId().toString())))
                        .param("competition_name", " tech "))
                .andExpect(status().isOk()).andExpect(content().json("{\"available\":false}", true));
        mvc.perform(get(path).with(jwt().jwt(j -> j.subject(user.getId().toString())))
                        .param("competition_name", "다른 사람 이름"))
                .andExpect(status().isOk()).andExpect(content().json("{\"available\":true}", true));
        mvc.perform(get(path).with(jwt().jwt(j -> j.subject(user.getId().toString())))
                        .param("competition_name", "가".repeat(20)))
                .andExpect(status().isOk()).andExpect(content().json("{\"available\":true}", true));
        mvc.perform(get(path).with(jwt().jwt(j -> j.subject(user.getId().toString())))
                        .param("competition_name", " " + "가".repeat(20)))
                .andExpect(status().isOk()).andExpect(content().json("{\"available\":false}", true));
    }
}
