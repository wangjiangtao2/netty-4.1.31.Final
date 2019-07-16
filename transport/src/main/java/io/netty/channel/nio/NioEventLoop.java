/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    /**
     * 是否开启  通过反射方式对selector key 优化
     */
    private static final boolean DISABLE_KEYSET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;


    /**
     * 若开启优化，则就是优化过的selector
     * 构造器时候赋值
     */
    private Selector selector;

    /**
     * 构造器时候赋值,原生selector
     * The NIO {@link Selector}
     */
    private Selector unwrappedSelector;

    /**
     * 通过优化,反射赋值
     */
    private SelectedSelectionKeySet selectedKeys;

    /**
     * 构造器时候赋值
     */
    private final SelectorProvider provider;

    /**
     * 选择策略 构造器时候赋值
     */
    private final SelectStrategy selectStrategy;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     */
    private final AtomicBoolean wakenUp = new AtomicBoolean();

    private volatile int ioRatio = 50;
    private int cancelledKeys;
    private boolean needsToSelectAgain;


    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        final String key = "sun.nio.ch.bugLevel";
        final String buglevel = SystemPropertyUtil.get(key);
        if (buglevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEYSET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler) {
        // 调用父类 SingleThreadEventLoop 构造器
        super(parent, executor, false, DEFAULT_MAX_PENDING_TASKS, rejectedExecutionHandler);
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        if (strategy == null) {
            throw new NullPointerException("selectStrategy");
        }
        // 设置 selectorProvider
        provider = selectorProvider;
        // 通过反射方式 对 selector key 优化了  看源码
        final SelectorTuple selectorTuple = openSelector();
        // 设置优化过的selector
        selector = selectorTuple.selector;
        // 设置原生的selector
        unwrappedSelector = selectorTuple.unwrappedSelector;
        // 设置select策略
        selectStrategy = strategy;
    }

    private static final class SelectorTuple {
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    // selectKey 优化选项flag
    private SelectorTuple openSelector() {
        // JDK原生的selector
        final Selector unwrappedSelector;
        try {
            // 通过 SelectorProvider 创建获得selector
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }
        // 如果不优化，则直接返回
        if (DISABLE_KEYSET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        // 通过反射创建 sun.nio.ch.SelectorImpl 对象
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        // 如果 maybeSelectorImplClass 不是 selector 的一个实现，则直接返回原生的Selector
        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
                // 确保当前的选择器实现是我们可以检测的
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        // maybeSelectorImplClass 是selector的实现，则转化为 selector 实现类
        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        // 创建新的 SelectionKey 集合 SelectedSelectionKeySet,内部采用的是 SelectionKey 数组的形式，而非 set 集合
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // 通过反射的方式获取 sun.nio.ch.SelectorImpl 的成员变量 selectedKeys
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    // 通过反射的方式获取 sun.nio.ch.SelectorImpl 的成员变量 publicSelectedKeys
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }
                    // 设置字段 selectedKeys  Accessible 为true
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    // 设置字段 publicSelectedKeys  Accessible 为true
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        // 设置 SelectedSelectionKeySet
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        // 返回包含了原生selector和优化过的selector的SelectorTuple
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    // NioEventLoop 创建TaskQueue队列
    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                                                    : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        if (ch == null) {
            throw new NullPointerException("ch");
        }
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        if (task == null) {
            throw new NullPointerException("task");
        }

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     *
     * 重新创建selector
     */
    public void rebuildSelector() {
        /**
         * inEventLoop()方法判断启动线程与当前线程相同，
         * 相同表示已经启动，不同则添加一个任务到该NioEventLoop的线程中
         */
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0(); //重建
                }
            });
            return;
        }
        rebuildSelector0(); //重建
    }

    private void rebuildSelector0() {
        // 暂存老的selector
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }
        try {
            //第一步：通过调用openSelector()创建新的selector。优化过的
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        // 记录select上注册的channel数量
        int nChannels = 0;
        // 遍历老的 selector 上的 SelectionKey
        for (SelectionKey key: oldSelector.keys()) {
            // 获取 attachment，这里的attachment就是我们前面在讲 Netty Channel注册时，select会将channel赋值到 attachment 变量上。
            // 获取老的selector上注册的channel
            Object a = key.attachment();
            try {
                //第二步：将oldSelector中的所有key cancel，并将key上的channel重新register在newSelector上。
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }
                // 获取兴趣集
                int interestOps = key.interestOps();
                //将old selector上的key cancel掉
                // 取消 SelectionKey
                key.cancel();
                //将old selector上的channel重新注册到new selector上
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                // nChannels计数 + 1
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }
        // 设置新的 selector
        selector = newSelectorTuple.selector;
        // 设置新的 unwrappedSelector
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            // 关闭老的seleclor
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    @Override
    protected void run() {
        for (;;) {
            try {
                switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                    case SelectStrategy.CONTINUE:
                        continue;

                    case SelectStrategy.BUSY_WAIT:
                        // fall-through to SELECT since the busy-wait is not supported with NIO

                    case SelectStrategy.SELECT:
                        // 轮询I/O事件
                        // wakenUp为了标记selector是否是唤醒状态，每次select操作，都设置为false，也就是未唤醒状态。
                        select(wakenUp.getAndSet(false));

                        // 'wakenUp.compareAndSet(false, true)' is always evaluated
                        // before calling 'selector.wakeup()' to reduce the wake-up
                        // overhead. (Selector.wakeup() is an expensive operation.)
                        //
                        // However, there is a race condition in this approach.
                        // The race condition is triggered when 'wakenUp' is set to
                        // true too early.
                        //
                        // 'wakenUp' is set to true too early if:
                        // 1) Selector is waken up between 'wakenUp.set(false)' and
                        //    'selector.select(...)'. (BAD)
                        // 2) Selector is waken up between 'selector.select(...)' and
                        //    'if (wakenUp.get()) { ... }'. (OK)
                        //
                        // In the first case, 'wakenUp' is set to true and the
                        // following 'selector.select(...)' will wake up immediately.
                        // Until 'wakenUp' is set to false again in the next round,
                        // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                        // any attempt to wake up the Selector will fail, too, causing
                        // the following 'selector.select(...)' call to block
                        // unnecessarily.
                        //
                        // To fix this problem, we wake up the selector again if wakenUp
                        // is true immediately after selector.select(...).
                        // It is inefficient in that it wakes up the selector for both
                        // the first case (BAD - wake-up required) and the second case
                        // (OK - no wake-up required).
                        /**
                         * 'wakenUp.compareAndSet(false, true)' 总是在调用 'selector.wakeup()' 之前进行评估，以减少唤醒的开销
                         * (Selector.wakeup() 是非常耗性能的操作.)但是，这种方法存在竞争条件。当「wakeup」太早设置为true时触发竞争条件
                         * 在下面两种情况下，「wakenUp」会过早设置为true：
                         *      1）Selector 在 'wakenUp.set(false)' 与 'selector.select(...)' 之间被唤醒。(BAD)
                         *      2）Selector 在 'selector.select(...)' 与 'if (wakenUp.get()) { ... }' 之间被唤醒。(OK)
                         *
                         *    在第一种情况下，'wakenUp'设置为true，后面的'selector.select（...）'将立即唤醒。 直到'wakenUp'在下一轮中再次设置为false，'wakenUp.compareAndSet（false，true）'将失败，
                         * 因此任何唤醒选择器的尝试也将失败，从而导致以下'selector.select（。 ..）'呼吁阻止不必要的。
                         *    要解决这个问题，如果在selector.select（...）操作之后wakenUp立即为true，我们会再次唤醒selector。
                         * 它是低效率的，因为它唤醒了第一种情况（BAD - 需要唤醒）和第二种情况（OK - 不需要唤醒）的选择器。
                         */
                        if (wakenUp.get()) {
                            selector.wakeup();
                        }
                        // fall through
                    default:
                }

                cancelledKeys = 0;
                needsToSelectAgain = false;
                // ioRatio 表示处理I/O事件与执行具体任务事件之间所耗时间的比值。默认为50
                final int ioRatio = this.ioRatio;
                if (ioRatio == 100) {
                    try {
                        // 处理I/O事件
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        // 处理任务队列
                        runAllTasks();
                    }
                } else {
                    // 处理IO事件的开始时间
                    final long ioStartTime = System.nanoTime();
                    try {
                        // 处理I/O事件
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        // 记录io所耗时间
                        final long ioTime = System.nanoTime() - ioStartTime;
                        /**处理任务队列，设置最大的超时时间
                         * {@link SingleThreadEventExecutor#runAllTasks(long)}
                         */
                        runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
            // Always handle shutdown even if the loop processing threw an exception.
            try {
                if (isShuttingDown()) {
                    closeAll();
                    if (confirmShutdown()) {
                        return;
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
        }
    }

    private void select(boolean oldWakenUp) throws IOException {
        Selector selector = this.selector;
        try {
            // select操作计数
            int selectCnt = 0;
            // 记录当前系统时间
            long currentTimeNanos = System.nanoTime();
            // 计算select的时间， 当前时间 + 计算延迟任务队列中第一个任务的到期执行时间（即最晚还能延迟多长时间执行），默认返回1s
            /**
             * delayNanos方法用于计算定时任务队列，最近一个任务的截止时间
             * selectDeadLineNanos 表示当前select操作所不能超过的最大截止时间
             */
            long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);

            for (;;) {
                /**
                 * 如果延迟任务队列中第一个任务的最晚还能延迟执行的时间小于500000纳秒(0.5ms)
                 * 且selectCnt == 0(selectCnt 用来记录selector.select方法的执行次数和标识是否执行过selector.selectNow())
                 * 则执行selector.selectNow()方法并立即返回
                 */
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                // 若延迟任务队列中第一个任务的最晚还能延迟执行的时间小于500000纳秒(0.5ms),
                //如果 timeoutMillis <= 0， 表示超时，进行一个非阻塞的 select 操作。设置 selectCnt 为 1. 并终止本次循环。
                if (timeoutMillis <= 0) {
                    //若 selector没有轮询
                    if (selectCnt == 0) {
                        selector.selectNow();
                        selectCnt = 1;
                    }
                    break;
                }

                // If a task was submitted when wakenUp value was true, the task didn't get a chance to call
                // Selector#wakeup. So we need to check task queue again before executing select operation.
                // If we don't, the task might be pended until select operation was timed out.
                // It might be pended until idle timeout if IdleStateHandler existed in pipeline.
                /**
                 * 判断当前异步任务队列是否有任务(也就是说当前是否外部线程丢入到一个任务到taskqueue需要执行，若需要执行的话，调用一个非阻塞selectNow方法)，
                 * 刚开始进入select方法时将wakeup设置为false的，
                 * 这里通过CAS操作将wakenUp设置为true就说明select已经wakeup了，
                 * 接下来调用一个非阻塞selectNow方法 然后break，本次select操作结束。
                 */
                /**
                 * 当wakenUp为ture时，恰好有task被提交，这个task将无法获得调用的机会
                 * Selector#wakeup. 因此，在执行select操作之前，需要再次检查任务队列
                 * 如果不这么做，这个Task将一直挂起，直到select操作超时
                 * 如果 pipeline 中存在 IdleStateHandler ，那么Task将一直挂起直到 空闲超时。
                 */
                if (hasTasks() && wakenUp.compareAndSet(false, true)) {
                    // 调用非阻塞方法
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }

                // 阻塞式 select，等待 timeoutMillis
                // 如果当前任务队列为空，并且超时时间未到，则进行一个阻塞式的selector操作。timeoutMillis 为最大的select时间
                int selectedKeys = selector.select(timeoutMillis);
                // 操作计数 +1
                selectCnt ++;

                //1）已经ready的selectionKey，2）selector被唤醒，3）任务taskQueue不为空，4）scheduledTaskQueue不为空。5）线程被中断
                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasTasks() || hasScheduledTasks()) {
                    // - Selected something,
                    // - waken up by user, or
                    // - the task queue has a pending task.
                    // - a scheduled task is ready for processing
                    /**
                     * - 轮询到了事件（Selected something,）
                     * - 被用户唤醒（waken up by user,）
                     * - 已有任务队列（the task queue has a pending task.）
                     * - 已有定时任务（a scheduled task is ready for processing）
                     */
                    break;
                }
                if (Thread.interrupted()) {
                    // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
                    // As this is most likely a bug in the handler of the user or it's client library we will
                    // also log it.
                    //
                    // See https://github.com/netty/netty/issues/2426
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                                "Thread.currentThread().interrupt() was called. Use " +
                                "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
                    }
                    selectCnt = 1;
                    break;
                }
                // 记录当前时间
                long time = System.nanoTime();
                //当前时间-select开始时间>= timeoutMillis(超时时间)  说明执行了一次阻塞操作
                // 如果time > currentTimeNanos + timeoutMillis(超时时间)，则表明已经执行过一次select操作
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // timeoutMillis elapsed without anything selected.
                    selectCnt = 1;
                }
                // 如果 time <= currentTimeNanos + timeoutMillis，表示触发了空轮询
                // 如果空轮训的次数超过 SELECTOR_AUTO_REBUILD_THRESHOLD (512)，则重建一个新的selctor，避免空轮训
                else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                        selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    // The selector returned prematurely many times in a row.
                    // Rebuild the selector to work around the problem.
                    logger.warn(
                            "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                            selectCnt, selector);
                    // 重建创建一个新的selector
                    rebuildSelector();
                    selector = this.selector;

                    // Select again to populate selectedKeys.
                    // 对重建后的selector进行一次非阻塞调用，用于获取最新的selectedKeys
                    selector.selectNow();
                    // 设置select计数
                    selectCnt = 1;
                    break;
                }

                currentTimeNanos = time;
            }

            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                            selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                        selector, e);
            }
            // Harmless exception - log anyway
        }
    }


    private void processSelectedKeys() {
        if (selectedKeys != null) {
            processSelectedKeysOptimized();
        } else {
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    private void processSelectedKeysOptimized() {
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            // 设置为null，有利于GC回收
            selectedKeys.keys[i] = null;//方便gC

            // 获取 SelectionKey 中的 attachment, 我们这里就是 NioChannel
            final Object a = k.attachment();
            //key关联两种不同类型的对象，一种是AbstractNioChannel，一种是NioTask
            if (a instanceof AbstractNioChannel) {
                // 处理 SelectedKey
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }
            //  如果需要重新select则重置当前数据
            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    // 处理 SelectedKey
    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        // 获取Netty Channel中的 NioUnsafe 对象，用于后面的IO操作
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        // 判断 SelectedKey 的有效性，如果无效，则直接返回并关闭channel
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop != this || eventLoop == null) {
                return;
            }
            // close the channel if the key is not valid anymore
            // 关闭channel
            unsafe.close(unsafe.voidPromise());
            return;
        }

        try {
            // 获取 SelectionKey 中所有准备就绪的操作集
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            /**
             * 如果是OP_CONNECT，则需要移除OP_CONNECT否则Selector.select(timeout)将立即返回不会有任何阻塞，这样可能会出现cpu 100%
             *
             * 在调用处理READ与WRITE事件之间，先调用finishConnect()接口，避免异常 NotYetConnectedException 发生。
             */
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            /**
             * 如果准备好了WRITE则将缓冲区中的数据发送出去，如果缓冲区中数据都发送完成，则清除之前关注的OP_WRITE标记
             */
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            /**
             * 如果准备好READ或ACCEPT则触发unsafe.read()
             */
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                /**
                 * 调用
                 * @see AbstractNioMessageChannel.NioMessageUnsafe#read()
                 */
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }




    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }



    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    @Override
    protected Runnable pollTask() {
        Runnable task = super.pollTask();
        if (needsToSelectAgain) {
            selectAgain();
        }
        return task;
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }




    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        try {
            return selector.selectNow();
        } finally {
            // restore wakeup state if needed
            if (wakenUp.get()) {
                selector.wakeup();
            }
        }
    }


    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
