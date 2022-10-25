package com.leooop.timer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author ziyou.cxf
 * @version : HashedWheelTimer.java, v 0.1 2022年10月23日 16:41 ziyou.cxf Exp $
 */
public class HashedWheelTimer implements Timer {

    private final HashedWheelBucket[] wheel;

    private final long tickDuration;

    /**
     * 定时器初始化时的启动时间
     */
    private volatile long startTime;

    private final int mask;

    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();

    private final Queue<HashedWheelTimeout> cancelledTimeouts = new LinkedBlockingDeque<>();

    private final Queue<HashedWheelTimeout> timeouts = new LinkedBlockingDeque<>();


    /**
     * 定时器初始化时的栅栏
     */
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);

    public final AtomicLong pendingTimeouts = new AtomicLong(0);
    private final long maxPendingTimeouts;

    private volatile int workerState;
    private static final int WORKER_STATE_INIT = 0;
    private static final int WORKER_STATE_STARTED = 1;
    private static final int WORKER_STATE_SHUTDOWN = 2;

    private final Worker worker = new Worker();

    private final Thread workerThread;

    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");


    public HashedWheelTimer() {
        this(Executors.defaultThreadFactory(), 1000, TimeUnit.MILLISECONDS, 8, -1);
    }

    public HashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit, int ticksPerWheel, int maxPendingTimeouts) {
        wheel = createWheel(ticksPerWheel);

        mask = wheel.length - 1;

        this.tickDuration = unit.toNanos(tickDuration);

        workerThread = threadFactory.newThread(worker);

        this.maxPendingTimeouts = maxPendingTimeouts;
    }

    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();

        start();

        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

        //Guard against overflow.
        if (delay > 0 && deadline < 0) {
            deadline = Long.MAX_VALUE;
        }
        HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
        timeouts.add(timeout);
        return timeout;
    }

    @Override
    public Set<Timeout> stop() {
        if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.getAndDecrement();
            }
            return Collections.emptySet();
        }

        try {
            boolean interrupted = false;
            while (workerThread.isAlive()) {
                workerThread.interrupt();
                try {
                    workerThread.join(100);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            INSTANCE_COUNTER.decrementAndGet();
        }
        return worker.unprocessedTimeouts;
    }

    @Override
    public boolean isStop() {
        return false;
    }

    public void start() {
        switch (WORKER_STATE_UPDATER.get(this)) {
            case WORKER_STATE_INIT:
                if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
                    workerThread.start();
                }
                break;
            case WORKER_STATE_STARTED:
                break;
            case WORKER_STATE_SHUTDOWN:
                throw new RuntimeException();
        }
        while (startTime == 0) {
            try {
                // 等待定时任务的调度
                startTimeInitialized.await();
            } catch (InterruptedException exception) {
                // ignored
            }
        }
    }

    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        HashedWheelBucket[] wheels = new HashedWheelBucket[ticksPerWheel];
        for (int i = 0; i < wheels.length; i++) {
            wheels[i] = new HashedWheelBucket();
        }
        return wheels;
    }

    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizeTicksPerWheel = ticksPerWheel - 1;
        normalizeTicksPerWheel |= normalizeTicksPerWheel >>> 1;
        normalizeTicksPerWheel |= normalizeTicksPerWheel >>> 2;
        normalizeTicksPerWheel |= normalizeTicksPerWheel >>> 4;
        normalizeTicksPerWheel |= normalizeTicksPerWheel >>> 8;
        normalizeTicksPerWheel |= normalizeTicksPerWheel >>> 16;
        return normalizeTicksPerWheel + 1;
    }

    // 调度器
    public class Worker implements Runnable {

        private final Set<Timeout> unprocessedTimeouts = new HashSet<>();

        private long tick;

        @Override
        public void run() {
            // Initialize the startTime.
            startTime = System.nanoTime();
            if (startTime == 0) {
                // we use 0 as an indicator for the uninitialized value
                startTime = 1;
            }

            // Notify the other threads waiting for the initialization at start()
            startTimeInitialized.countDown();

            do {
                final long deadline = waitForNextTick();
                if (deadline > 0) {
                    int idx = (int) (tick & mask);
                    processCancelledTasks();
                    HashedWheelBucket bucket = wheel[idx];
                    transferTimeoutsToBuckets();
                    bucket.expireTimeouts(deadline);
                    tick++;
                }
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);

            for (HashedWheelBucket bucket : wheel) {
                bucket.clearTimeouts(unprocessedTimeouts);
            }

            for (; ; ) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (null == timeout) {
                    break;
                }
                if (!timeout.isCancelled()) {
                    unprocessedTimeouts.add(timeout);
                }
            }
            processCancelledTasks();
        }

        private void processCancelledTasks() {
            for (; ; ) {
                HashedWheelTimeout timeout = cancelledTimeouts.poll();
                if (Objects.isNull(timeout)) {
                    break;
                }
                try {
                    timeout.remove();
                } catch (Throwable throwable) {
                    // ignored
                }
            }
        }

        /**
         * 将等待队列中的任务，投递到篮子中
         * 这个方法应该就是实现了 -- 中途任务可以添加进来的好处
         */
        private void transferTimeoutsToBuckets() {
            for (int i = 0; i < 100000; i++) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                if (timeout.isCancelled()) {
                    continue;
                }
                long calculated = timeout.deadline / tickDuration;
                // 计算出需要轮询的次数
                timeout.remainingRounds = (calculated - tick) / wheel.length;

                // Ensure we don't schedule for past
                final long ticks = Math.max(calculated, tick);
                int stopIndex = (int) (ticks & mask);

                HashedWheelBucket bucket = wheel[stopIndex];
                bucket.addTimeout(timeout);
            }
        }

        /**
         * 计算是否可以开始下一次轮询了。其实currentTime 表示当前时间与初始时间的间隔
         *
         * @return
         */
        private long waitForNextTick() {
            long deadline = tickDuration * (tick + 1);
            for (; ; ) {
                final long currentTime = System.nanoTime() - startTime;
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;

                if (sleepTimeMs <= 0) {
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        return currentTime;
                    }
                }

                if (isWindows()) {
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                }

                try {
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ignored) {
                    if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
                        return Long.MIN_VALUE;
                    }
                }
            }
        }

    }

    private static class HashedWheelTimeout implements Timeout {
        private static final int ST_INIT = 0;
        private static final int ST_CANCELLED = 1;
        private static final int ST_EXPIRED = 2;

        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");

        /**
         * RemainingRounds will be calculated and set by Worker.transferTimeoutsToBuckets() before the
         * HashedWheelTimeout will be added to the correct HashedWheelBucket.
         */
        private long remainingRounds;

        private HashedWheelTimer timer;
        private final TimerTask task;
        private final long deadline;

        // 服务于 HashedWheelBucket
        private HashedWheelTimeout prev;
        private HashedWheelTimeout next;

        /**
         * The bucket to which the timeout was added
         */
        HashedWheelBucket bucket;

        private volatile int state = ST_INIT;

        private HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.task = task;
            this.timer = timer;
            this.deadline = deadline;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean isExpired() {
            return state == ST_EXPIRED;
        }

        @Override
        public boolean isCancelled() {
            return state == ST_CANCELLED;
        }

        @Override
        public boolean cancel() {
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }
            // If a task should be canceled we put this to another queue which will be processed on each tick.
            // So this means that we will have a GC latency of max. 1 tick duration which is good enough. This way we
            // can make again use of our LinkedBlockingQueue and so minimize the locking / overhead as much as possible.
            timer.cancelledTimeouts.add(this);
            return true;
        }

        int state() {
            return state;
        }

        void remove() {
            HashedWheelBucket bucket = this.bucket;
            if (null != bucket) {
                bucket.remove(this);
            } else {
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        void expire() {
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) {
                return;
            }
            try {
                task.run(this);
            } catch (Throwable t) {
                // ignored
            }
        }

        boolean compareAndSetState(int expected, int state) {
            return STATE_UPDATER.compareAndSet(this, expected, state);
        }
    }

    private static class HashedWheelBucket {

        private HashedWheelTimeout head;

        private HashedWheelTimeout tail;

        void addTimeout(HashedWheelTimeout timeout) {
            assert timeout.bucket == null;
            timeout.bucket = this;
            if (head == null) {
                head = tail = timeout;
            } else {
                tail.next = timeout;
                timeout.prev = tail;
                tail = timeout;
            }
        }

        // 真正执行延迟任务的地方
        // remainingRounds为轮询的次数
        void expireTimeouts(long deadline) {
            HashedWheelTimeout timeout = head;

            // process all timeouts
            while (timeout != null) {
                HashedWheelTimeout next = timeout.next;
                // 轮询次数
                if (timeout.remainingRounds <= 0) {
                    next = remove(timeout);
                    if (timeout.deadline <= deadline) {
                        timeout.expire();
                    }
                } else if (timeout.isCancelled()) {
                    next = remove(timeout);
                } else {
                    timeout.remainingRounds--;
                }
                timeout = next;
            }
        }

        HashedWheelTimeout remove(HashedWheelTimeout timeout) {
            HashedWheelTimeout next = timeout.next;
            if (timeout.prev != null) {
                timeout.prev.next = next;
            }
            if (timeout.next != null) {
                timeout.next.prev = next;
            }
            if (timeout == head) {
                if (timeout == tail) {
                    head = null;
                    tail = null;
                } else {
                    head = next;
                }
            } else if (timeout == tail) {
                tail = timeout.prev;
            }
            // null out prev, next and bucket to allow for GC
            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         * clear this bucket and return all not expired / cancelled
         *
         * @param set
         */
        void clearTimeouts(Set<Timeout> set) {
            for (; ; ) {
                HashedWheelTimeout timeout = pollTimeout();
                if (null == timeout) {
                    return;
                }
                if (timeout.isExpired() || timeout.isCancelled()) {
                    continue;
                }
                set.add(timeout);
            }
        }

        private HashedWheelTimeout pollTimeout() {
            HashedWheelTimeout head = this.head;
            if (null == head) {
                return null;
            }
            HashedWheelTimeout next = head.next;
            if (next == null) {
                tail = this.head = null;
            } else {
                this.head = next;
                next.prev = null;
            }

            // null out prev and next to allow for GC
            head.next = null;
            head.prev = null;
            head.bucket = null;
            return head;
        }
    }

    private static final boolean IS_OS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.US)
            .contains("win");

    private boolean isWindows() {
        return IS_OS_WINDOWS;
    }

}