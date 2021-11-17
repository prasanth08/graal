/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.truffle.espresso.trufflethreads;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.espresso.impl.SuppressFBWarnings;

/**
 * Lock implementation for guest objects. Provides a similar interface to {@link Object} built-in
 * monitor locks, along with some bookkeeping.
 */
public interface TruffleLock {

    /**
     * Creates a new {@code TruffleLock} instance.
     */
    @TruffleBoundary // ReentrantLock.<init> blacklisted by SVM
    static TruffleLock create(TruffleThreads truffleThreads) {
        return new TruffleLockImpl(truffleThreads);
    }

    /**
     * Acquires the lock.
     * <p>
     * Acquires the lock if it is not held by another thread and returns immediately, setting the
     * lock hold count to one.
     *
     * <p>
     * If the current thread already holds the lock then the hold count is incremented by one and
     * the method returns immediately.
     * <p>
     * If the lock is held by another thread then the current thread becomes disabled for thread
     * scheduling purposes and lies dormant until the lock has been acquired, at which time the lock
     * hold count is set to one. During this, the thread will still handle {@link TruffleSafepoint
     * safepoints}
     */
    void lock();

    /**
     * Acquires the lock only if it is not held by another thread at the time of invocation.
     * <p>
     * Acquires the lock if it is not held by another thread and returns immediately with the value
     * {@code true}, setting the lock hold count to one.
     * <p>
     * If the current thread already holds this lock then the hold count is incremented by one and
     * the method returns {@code true}.
     * <p>
     * If the lock is held by another thread then this method will return immediately with the value
     * {@code false}.
     *
     * @return {@code true} if the lock was free and was acquired by the current thread, or the lock
     *         was already held by the current thread; and {@code false} otherwise
     */
    boolean tryLock();

    /**
     * Acquires the lock unless the current thread is
     * {@linkplain TruffleThreads#guestInterrupt(Thread) guest-interrupted}.
     * <p>
     * Acquires the lock if it is not held by another thread and returns immediately, setting the
     * lock hold count to one.
     * <p>
     * If the current thread already holds this lock then the hold count is incremented by one and
     * the method returns immediately.
     * <p>
     * If the lock is held by another thread then the current thread becomes disabled for thread
     * scheduling purposes but still answers to {@link com.oracle.truffle.api.TruffleSafepoint
     * safepoints} and lies dormant until one of two things happens:
     * <ul>
     * <li>The lock is acquired by the current thread; or
     * <li>Some other thread {@linkplain TruffleThreads#guestInterrupt(Thread) guest-interrupts} the
     * current thread.
     * </ul>
     * <p>
     * If the lock is acquired by the current thread then the lock hold count is set to one.
     * <p>
     * If the current thread:
     * <ul>
     * <li>has its guest-interrupted status set on entry to this method; or
     * <li>is {@linkplain TruffleThreads#guestInterrupt(Thread) guest-interrupted}} while acquiring
     * the lock,
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's interrupted status is
     * cleared.
     * <p>
     * In this implementation, as this method is an explicit interruption point, preference is given
     * to responding to the interrupt over normal or reentrant acquisition of the lock.
     *
     * @throws GuestInterruptedException if the current thread is guest-interrupted
     */
    void lockInterruptible() throws GuestInterruptedException;

    /**
     * Attempts to release this lock.
     *
     * <p>
     * If the current thread is the holder of this lock then the hold count is decremented. If the
     * hold count is now zero then the lock is released. If the current thread is not the holder of
     * this lock then {@link IllegalMonitorStateException} is thrown.
     *
     * @throws IllegalMonitorStateException if the current thread does not hold this lock
     */
    void unlock();

    /**
     * Causes the current thread to wait until either another thread invokes the
     * {@link TruffleLock#signal()} method or the {@link TruffleLock#signalAll()} method for this
     * object, or a specified amount of time has elapsed.
     *
     * <p>
     * The current thread must own this object's monitor.
     * <p>
     * Analogous to the {@link Object#wait(long)} method for built-in monitor locks.
     *
     * @param timeout the maximum time to wait in milliseconds. {@code false} if the waiting time
     *            detectably elapsed before return from the method, else {@code true}
     * @throws IllegalArgumentException if the value of timeout is negative.
     * @throws IllegalMonitorStateException if the current thread is not the owner of the lock.
     * @throws GuestInterruptedException if any thread guest-interrupted the current thread before
     *             or while the current thread was waiting for a notification.
     */
    boolean await(long timeout) throws GuestInterruptedException;

    /**
     * Wakes up one waiting thread.
     *
     * <p>
     * If any threads are waiting on this condition then one is selected for waking up. That thread
     * must then re-acquire the lock before returning from {@code await}.
     *
     * <p>
     * Analogous to the {@link Object#notify()} method for built-in monitor locks.
     */
    void signal();

    /**
     * Wakes up all waiting threads.
     *
     * <p>
     * If any threads are waiting on this condition then they are all woken up. Each thread must
     * re-acquire the lock before it can return from {@code await}.
     *
     * <p>
     * Analogous to the {@link Object#notifyAll()} method for built-in monitor locks.
     */
    void signalAll();

    /**
     * Queries if this lock is held by the current thread.
     *
     * <p>
     * Analogous to the {@link Thread#holdsLock(Object)} method for built-in monitor locks.
     */
    boolean isHeldByCurrentThread();

    /**
     * Returns the thread that currently owns this lock, or {@code null} if not owned. When this
     * method is called by a thread that is not the owner, the return value reflects a best-effort
     * approximation of current lock status. For example, the owner may be momentarily {@code null}
     * even if there are threads trying to acquire the lock but have not yet done so.
     *
     * @return the owner, or {@code null} if not owned
     */
    Thread getOwnerThread();

    /**
     * Exposes the underlying lock heldCount.
     *
     * @return the entry count
     */
    int getEntryCount();
}

final class TruffleLockImpl extends ReentrantLock implements TruffleLock {

    private static final long serialVersionUID = -2776792497346642438L;

    TruffleLockImpl(TruffleThreads truffleThreads) {
        this.truffleThreads = truffleThreads;
    }

    private final TruffleThreads truffleThreads;
    private volatile Condition waitCondition;

    @SuppressFBWarnings(value = "JLM_JSR166_LOCK_MONITORENTER", justification = "Truffle runtime method.")
    private Condition getWaitCondition() {
        Condition cond = waitCondition;
        if (cond == null) {
            synchronized (this) {
                cond = waitCondition;
                if (cond == null) {
                    waitCondition = cond = super.newCondition();
                }
            }
        }
        return cond;
    }

    @Override
    public void lock() {
        TruffleSafepoint.setBlockedThreadInterruptible(/* TODO */null, TruffleLockImpl::superLockInterruptibly, this);
    }

    @Override
    public void lockInterruptible() throws GuestInterruptedException {
        truffleThreads.enterInterruptible(TruffleLockImpl::superLockInterruptibly, /* TODO */null, this);
    }

    @Override
    public void lockInterruptibly() {
        throw new UnsupportedOperationException("lockInterruptibly unsupported for TruffleLocks. Use lockInterruptible instead.");
    }

    @Override
    public boolean await(long timeout) throws GuestInterruptedException {
        if (timeout == 0) {
            truffleThreads.enterInterruptible(awaitInterruptible, /* TODO */null, this);
        } else if (timeout > 0) {
            TimedWaitInterruptible interruptible = new TimedWaitInterruptible(timeout);
            truffleThreads.enterInterruptible(interruptible, /* TODO */null, this);
            return interruptible.result;
        } else {
            throw new IllegalArgumentException();
        }
        return false;
    }

    @Override
    public void signal() {
        getWaitCondition().signal();
    }

    @Override
    public void signalAll() {
        getWaitCondition().signalAll();
    }

    @Override
    @TruffleBoundary // ReentrantLock.getOwner blacklisted by SVM
    public Thread getOwnerThread() {
        return getOwner();
    }

    @Override
    public Condition newCondition() {
        // Disable arbitrary conditions.
        throw new UnsupportedOperationException();
    }

    @Override
    public int getEntryCount() {
        return getHoldCount();
    }

    @TruffleBoundary
    private void superLockInterruptibly() throws InterruptedException {
        super.lockInterruptibly();
    }

    private static final TruffleSafepoint.Interruptible<TruffleLockImpl> awaitInterruptible = new TruffleSafepoint.Interruptible<TruffleLockImpl>() {
        @Override
        @SuppressFBWarnings(value = "WA_AWAIT_NOT_IN_LOOP", justification = "Truffle runtime method.")
        public void apply(TruffleLockImpl lock) throws InterruptedException {
            lock.getWaitCondition().await();
        }
    };

    private static final class TimedWaitInterruptible implements TruffleSafepoint.Interruptible<TruffleLockImpl> {
        TimedWaitInterruptible(long timeout) {
            this.timeout = timeout;
        }

        private final long timeout;
        boolean result;

        @Override
        public void apply(TruffleLockImpl lock) throws InterruptedException {
            result = lock.getWaitCondition().await(timeout, TimeUnit.MILLISECONDS);
        }
    }
}
