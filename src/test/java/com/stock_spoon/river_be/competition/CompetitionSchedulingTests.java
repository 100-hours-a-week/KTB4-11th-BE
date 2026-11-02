package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.config.CompetitionSchedulingConfiguration;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.competition.service.*;
import com.stock_spoon.river_be.market.kiwoom.CollectorSchedulingConfig;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompetitionSchedulingTests {
    @Test void registeredCloseTriggerUsesKoreanWeekdayCloseAndSecondCheck() {
        var competitions = mock(CompetitionRepository.class);
        var hours = mock(CompetitionTradingHours.class);
        when(hours.now()).thenReturn(ZonedDateTime.parse("2026-10-23T16:00:00+09:00[Asia/Seoul]"));
        var completion = new CompetitionCompletionScheduler(competitions, mock(CompetitionCompletionService.class),
                hours, mock(CompetitionDailyPerformanceService.class));
        new ApplicationContextRunner()
                .withUserConfiguration(SchedulingEnabled.class, CollectorSchedulingConfig.class, CompetitionSchedulingConfiguration.class)
                .withBean(CompetitionCompletionScheduler.class, () -> completion)
                .run(context -> {
                    var processor = context.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class);
                    var cronTasks = processor.getScheduledTasks().stream().map(task -> task.getTask())
                            .filter(task -> task instanceof org.springframework.scheduling.config.CronTask)
                            .map(task -> (org.springframework.scheduling.config.CronTask) task).toList();
                    assertThat(cronTasks).hasSize(1);
                    var trigger = cronTasks.getFirst().getTrigger();
                    for (var pair : java.util.List.of(
                            new String[]{"2026-10-23T06:29:59Z", "2026-10-23T06:30:00Z"},
                            new String[]{"2026-10-23T06:31:00Z", "2026-10-23T06:35:00Z"},
                            new String[]{"2026-10-23T06:36:00Z", "2026-10-26T06:30:00Z"})) {
                        var previous = Instant.parse(pair[0]);
                        assertThat(trigger.nextExecution(new org.springframework.scheduling.support.SimpleTriggerContext(previous, previous, previous)))
                                .isEqualTo(Instant.parse(pair[1]));
                    }
                });
    }

    @Test void blockedCompletionDoesNotBlockSharedScheduler() {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var competitions = mock(CompetitionRepository.class);
        var hours = mock(CompetitionTradingHours.class);
        when(hours.now()).thenReturn(ZonedDateTime.parse("2026-10-05T16:00:00+09:00[Asia/Seoul]"));
        when(competitions.findDailyPerformanceCandidates(any())).thenAnswer(call -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        when(competitions.findCompletionCandidates(any())).thenReturn(List.of());
        var completion = new CompetitionCompletionScheduler(competitions, mock(CompetitionCompletionService.class),
                hours, mock(CompetitionDailyPerformanceService.class));

        new ApplicationContextRunner()
                .withUserConfiguration(SchedulingEnabled.class, CollectorSchedulingConfig.class, CompetitionSchedulingConfiguration.class)
                .withBean(CompetitionCompletionScheduler.class, () -> completion)
                .run(context -> {
                    try {
                        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                        var shared = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
                        // 정산이 대기 중이어도 공용 스레드의 작업은 끝나야 한다.
                        var result = new java.util.concurrent.CompletableFuture<String>();
                        shared.schedule(() -> result.complete("executed"), Instant.now());
                        assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo("executed");
                    } finally {
                        release.countDown();
                    }
                });
    }

    @Test void disabledCompletionDoesNotCreateDedicatedScheduler() {
        new ApplicationContextRunner().withUserConfiguration(CompetitionSchedulingConfiguration.class)
                .withPropertyValues("competition.completion.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("competitionCompletionTaskScheduler"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingEnabled {}
}
