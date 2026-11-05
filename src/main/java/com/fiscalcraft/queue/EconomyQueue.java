package com.fiscalcraft.queue;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 全局单线程资金队列。所有资金/税务/贷款读写都从这里串行执行。
 * 回调用主线程执行器送回，成功消息只能在事务返回 ok 后发送。
 */
public final class EconomyQueue implements AutoCloseable {
    public interface MainExecutor {
        void execute(Runnable runnable);
    }

    private final ExecutorService worker;
    private final MainExecutor main;
    private final Logger logger;
    private final AtomicLong submitted = new AtomicLong();
    private volatile boolean accepting = true;

    public EconomyQueue(MainExecutor main, Logger logger) {
        this.main = main;
        this.logger = logger;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "FiscalCraft-Economy");
            t.setDaemon(true);
            return t;
        });
    }

    public long submittedCount() {
        return submitted.get();
    }

    public boolean accepting() {
        return accepting;
    }

    public void submit(String label, Callable<Outcome> task, Consumer<Outcome> callback) {
        submitted.incrementAndGet();
        if (!accepting) {
            if (callback != null) main.execute(() -> callback.accept(Outcome.fail("银行正在停机维护")));
            return;
        }
        worker.submit(() -> {
            Outcome result;
            try {
                result = task.call();
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "资金操作失败: " + label, t);
                result = Outcome.fail("操作失败，资金未变动，请联系管理员");
            }
            if (callback != null) {
                Outcome finalResult = result;
                main.execute(() -> callback.accept(finalResult));
            }
        });
    }

    public void submit(String label, Callable<Outcome> task) {
        submit(label, task, null);
    }

    /** 初始化任务，不检查 accepting。 */
    public <T> Future<T> submitInternal(String label, Callable<T> task) {
        return worker.submit(() -> {
            try {
                return task.call();
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "初始化任务失败: " + label, t);
                throw new RuntimeException(t);
            }
        });
    }

    public CompletableFuture<Outcome> future(String label, Callable<Outcome> task) {
        CompletableFuture<Outcome> future = new CompletableFuture<>();
        submit(label, task, future::complete);
        return future;
    }

    public boolean finish(Duration timeout) {
        accepting = false;
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        worker.submit(() -> barrier.complete(null));
        try {
            barrier.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            worker.shutdown();
            return worker.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            worker.shutdownNow();
            return false;
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
            return false;
        }
    }

    @Override
    public void close() {
        accepting = false;
        worker.shutdown();
    }
}
