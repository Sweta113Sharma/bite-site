package com.bitesite.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The pool for work a user should never wait on.
 *
 * <p>Every outbound message in this product was sent on the request thread: the SMTP
 * handshake during registration, the Twilio call during phone verification, and — worst
 * of the three — a web-push POST per subscription while marking an order ready, which
 * happens <em>inside</em> {@code OrderService}'s transaction. A canteen tapping "ready"
 * held a database transaction open for the length of a round trip to Google's push
 * gateway, and the operator watched a spinner for it. None of that work belongs in the
 * request.
 *
 * <p>Deliberately small and bounded. Queue overflow runs the task on the calling thread
 * rather than discarding it: the failure mode of a full queue should be "this one request
 * is slow again", not "that student never heard their order was ready".
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    @Bean("applicationTaskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("bitesite-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Let in-flight sends finish on shutdown rather than killing a half-written SMTP
        // conversation; bounded so a wedged relay cannot hold the process open.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * A {@code void @Async} method's exception has nowhere to go — no caller is holding a
     * Future to unwrap it. Without this it vanishes entirely. Every async method here
     * already logs its own failures; this catches the ones that never got that far.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable ex, Method method, Object... params) ->
                log.error("Uncaught exception in async {}.{}({})",
                        method.getDeclaringClass().getSimpleName(), method.getName(),
                        Arrays.toString(params), ex);
    }
}
