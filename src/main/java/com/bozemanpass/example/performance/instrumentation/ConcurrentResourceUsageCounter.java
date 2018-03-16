/* START COPY NOTICE
 * MIT License

 * Copyright (c) 2018 Bozeman Pass, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 * END COPY NOTICE */

package com.bozemanpass.example.performance.instrumentation;

import com.sun.management.ThreadMXBean;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class offers a threadsafe way to track elapsed time, CPU usage, and memory allocation.
 * A ConcurrentResourceUsageCounter instance can be shared amongst all threads that are
 * concurrently working on an operation, accumulating the results of distinct
 * {@link ResourceUsageCounter} instances into the shared ConcurrentResourceUsageCounter instance.
 * <p>
 * Example:
 *
 *       class MyOperation {
 *           //our shared counter
 *           final ConcurrentResourceUsageCounter counter = new ConcurrentResourceUsageCounter();
 *           . . . everything else . . .
 *       }
 *
 *
 *       void start() {
 *           ExecutorService executor = Executors.newCachedThreadPool();
 *           MyOperation op = new MyOperation();
 *
 *           //spin up several threads to work on our operation
 *           for (int i = 0; i < NUM_CPUS; i++) {
 *               executor.submit(() -> {
 *                   ResourceUsageCounter counter = op.counter.start();
 *                   workOnOperation(op);
 *                   op.counter.add(counter.halt());
 *               });
 *           }
 *
 *           . . . wait for operation to be complete . . .
 *
 *           System.out.println("Total resources for my operation: " + op.counter.toString());
 *       }
 *
 */
public class ConcurrentResourceUsageCounter {
    private static final Logger log = Logger.getLogger(ConcurrentResourceUsageCounter.class.getName());
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock r = lock.readLock();
    private final Lock w = lock.writeLock();

    //this counter will not be started and stopped; it is used to accumulate results from other counters
    private final ResourceUsageCounter main = new ResourceUsageCounter();

    /**
     * Return a brand new ResourceUsageCounter, already running.  The
     * returned counter is not associated with our object in anyway.
     * <p>
     * The standard usage is something likeL
     * <p>
     * ResourceUsageCounter x = crc.start();
     * . . . do things . . .
     * crc.add(x.halt());
     *
     * @return a new ResourceUsageCounter object, already started.
     */
    public ResourceUsageCounter start() {
        return new ResourceUsageCounter().start();
    }

    /**
     * Like start(), but the returned counter will be automatically
     * added to our tally when it is halted.  This is done via the
     * ResourceUsageCounter.onHalt handler, so if that handler is
     * reset, the tally will not be updated.
     *
     * @return a new ResourceUsageCounter object, already started.
     */
    public ResourceUsageCounter auto() {
        return auto(null);
    }

    /**
     * Like start(), but the returned counter will be automatically
     * added to our tally when it is halted.  The supplied function
     * will also run on halt.  This is done by setting the
     * ResourceUsageCounter.onHalt handler, so if that handler is
     * reset, the tally will not be updated nor the supplied
     * function run.
     *
     * @param fn a function to run on halt
     * @return a new ResourceUsageCounter object, already started.
     */
    public ResourceUsageCounter auto(Consumer<ResourceUsageCounter> fn) {
        ResourceUsageCounter ret = new ResourceUsageCounter().onHalt((t) -> {
            add(t);
            if (null != fn) {
                fn.accept(t);
            }
        });
        return ret.start();
    }

    /**
     * Accumulate the ResourceUsageCounter into our counter.  ResourceUsageCounter
     * MUST NOT be running for us to obtain an accurate tally.
     *
     * @param c the ResourceUsageCounter to add
     */
    public void add(ResourceUsageCounter c) {
        w.lock();
        try {
            main.add(c);
        } catch (Throwable ex) {
            log.log(Level.SEVERE, "Timer error!", ex);
        } finally {
            w.unlock();
        }
    }

    /**
     * Accumulate the last results of ResourceUsageCounter into our counter.
     * ResourceUsageCounter MUST NOT be running for us to obtain an accurate tally.
     *
     * @param c the ResourceUsageCounter to add
     */
    public void addLast(ResourceUsageCounter c) {
        w.lock();
        try {
            main.addLast(c);
        } catch (Throwable ex) {
            log.log(Level.SEVERE, "Timer error!", ex);
        } finally {
            w.unlock();
        }
    }

    /**
     * Returns the total CPU time in nanoseconds.
     *
     * @return the time in nanoseconds
     * @see ThreadMXBean#getCurrentThreadCpuTime()
     */
    public long getCpuTimeNanos() {
        long ret = -1L;
        r.lock();
        try {
            ret = main.getCpuTimeNanos();
        } catch (Throwable ex) {
            log.log(Level.SEVERE, "Timer error!", ex);
        } finally {
            r.unlock();
        }
        return ret;
    }

    /**
     * Returns the CPU time in user-mode in nanoseconds.
     *
     * @return the time in nanoseconds
     * @see ThreadMXBean#getCurrentThreadUserTime()
     */
    public long getUsrTimeNanos() {
        long ret = -1L;
        r.lock();
        try {
            ret = main.getUsrTimeNanos();
        } catch (Throwable ex) {
            log.log(Level.SEVERE, "Timer error!", ex);
        } finally {
            r.unlock();
        }
        return ret;
    }

    /**
     * The amount of time, in milliseconds, that the counter
     * has been actively running.
     *
     * @return the time in milliseconds
     */
    public long getActiveTimeMillis() {
        long ret = -1L;
        r.lock();
        try {
            ret = main.getActiveTimeMillis();
        } catch (Throwable ex) {
            log.log(Level.SEVERE, "Timer error!", ex);
        } finally {
            r.unlock();
        }
        return ret;
    }

    /**
     * The total memory allocated, in bytes.
     *
     * @return the total memory allocated, in bytes
     * @see ThreadMXBean#getThreadAllocatedBytes(long)
     */
    public long getMemBytes() {
        long ret = -1L;
        r.lock();
        try {
            ret = main.getMemBytes();
        } catch (Throwable ex) {
            log.log(Level.SEVERE, "Timer error!", ex);
        } finally {
            r.unlock();
        }
        return ret;
    }

    public String toString() {
        String ret = "";
        r.lock();
        try {
            ret = main.toString();
        } catch (Throwable ex) {
            log.log(Level.SEVERE, "Timer error!", ex);
        } finally {
            r.unlock();
        }
        return ret;
    }
}
