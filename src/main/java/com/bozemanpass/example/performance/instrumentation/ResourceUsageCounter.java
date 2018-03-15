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

import java.lang.management.ManagementFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class tracks elapsed time, CPU usage, and memory allocation (in bytes).
 * For memory allocation tracking to operate, it must be supported and enabled
 * in the JVM.
 *
 * Starting the counter 'checkpoints' all the values for the current thread at that moment.
 *
 * Since the values relate to the current thread, this counter is not threadsafe (see
 * {@link ConcurrentResourceUsageCounter} for a threadsafe alternative).
 *
 * Pausing or halting the counter updates the progress by comparing the current values
 * to those that were checkpointed previously.
 *
 * "Splitting" the counter updates the values, but keeps the counter running.  It is
 * functionally the same as pausing and immediately starting the counter again.
 *
 * Example usage:
 *
 *     ResourceUsageCounter counter = new ResourceUsageCounter().start();
 *     runMyCode();
 *     counter.halt();
 *     System.out.println("My code used:  " + counter.toString());
 *
 * @see java.lang.management.ThreadMXBean
 * @see com.sun.management.ThreadMXBean
 * @see com.sun.management.ThreadMXBean#setThreadCpuTimeEnabled(boolean)
 * @see com.sun.management.ThreadMXBean#setThreadAllocatedMemoryEnabled(boolean)
 */
public class ResourceUsageCounter {
    private static final Logger log = Logger.getLogger(ResourceUsageCounter.class.getName());
    private final long initialStartMillis = System.currentTimeMillis();

    private long cpuTimeNanos = 0L;
    private long usrTimeNanos = 0L;
    private long activeTimeMillis = 0L;
    private long memBytes = 0L;

    private long lastCpuTimeNanos = 0L;
    private long lastUsrTimeNanos = 0L;
    private long lastActiveTimeMillis = 0L;
    private long lastMemBytes = 0L;

    private long _cpu = 0L;
    private long _usr = 0L;
    private long _act = 0L;
    private long _mem = 0L;

    private long halted = 0L;
    private boolean running = false;

    private final boolean isThreadAllocatedMemoryEnabled;
    private final boolean isThreadCpuTimeEnabled;

    /**
     * Enable allocated memory tracking and cp.  Whether this is enabled by default or not
     * is a platform dependent.   This setting will apply JVM-wide.
     */
    public static void enableMeasurementsInJVM() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        bean.setThreadCpuTimeEnabled(true);
    }

    private static boolean isMemoryTrackingAvailable() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        return bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled();
    }

    private static boolean isCpuTimeTrackingAvailable() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        return bean.isThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled();
    }

    public ResourceUsageCounter() {
        isThreadAllocatedMemoryEnabled = isMemoryTrackingAvailable();
        isThreadCpuTimeEnabled = isCpuTimeTrackingAvailable();
    }

    private void reset() {
        running = false;
        _cpu = 0L;
        _usr = 0L;
        _act = 0L;
        _mem = 0L;
    }

    /**
     * Start the counter running.
     *
     * @return this
     */
    public ResourceUsageCounter start() {
        reset();
        running = true;

        try {
            ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();

            //stash all the current values
            _act = System.currentTimeMillis();

            if (isThreadCpuTimeEnabled) {
                _cpu = bean.getCurrentThreadCpuTime();
                _usr = bean.getCurrentThreadUserTime();
            }

            if (isThreadAllocatedMemoryEnabled) {
                long tid = Thread.currentThread().getId();
                _mem = bean.getThreadAllocatedBytes(tid);
            }
        } catch (Throwable t) {
            log.log(Level.SEVERE, "Error starting counters!", t);
        }

        return this;
    }

    /**
     * 'Split' the counter, that is, calculate the values of the last run, but keep timing.
     * This is equivalent to calling {@link #pause()} and {@link #start()}.  The values
     * of the last run are available in the getLastXYZ() methods and in string form as
     * {@link #last()}.
     *
     * @return this
     */
    public ResourceUsageCounter split() {
        pause();
        start();
        return this;
    }

    /**
     * Pause the counter (this will also update the calculated values).
     *
     * @return this
     */
    public ResourceUsageCounter pause() {
        if (!running || 0 != halted) {
            return this;
        }

        try {
            ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();

            //calculate all the differences between 'now' and when we started
            if (isThreadCpuTimeEnabled) {
                lastCpuTimeNanos = bean.getCurrentThreadCpuTime() - _cpu;
                cpuTimeNanos += lastCpuTimeNanos;

                lastUsrTimeNanos = bean.getCurrentThreadUserTime() - _usr;
                usrTimeNanos += lastUsrTimeNanos;
            }

            lastActiveTimeMillis = System.currentTimeMillis() - _act;
            activeTimeMillis += lastActiveTimeMillis;

            if (isThreadAllocatedMemoryEnabled) {
                long tid = Thread.currentThread().getId();
                lastMemBytes = bean.getThreadAllocatedBytes(tid) - _mem;
                memBytes += lastMemBytes;
            }
        } catch (Throwable t) {
            log.log(Level.SEVERE, "Error pausing counters!", t);
        }

        return this;
    }

    /**
     * Halt the counter.  A halted counter cannot be started again.
     *
     * @return this
     */
    public ResourceUsageCounter halt() {
        pause();
        halted = System.currentTimeMillis();
        return this;
    }

    /**
     * Add two counters together.  The incoming counter MUST NOT
     * be running in order to obtain an accurate value.
     *
     * @param c the counter to add
     */
    public void add(ResourceUsageCounter c) {
        //if the counter is running, we may not get anything here
        cpuTimeNanos += c.getCpuTimeNanos();
        usrTimeNanos += c.getUsrTimeNanos();
        activeTimeMillis += c.getActiveTimeMillis();
        memBytes += c.getMemBytes();
    }

    /**
     * Like {@link #toString()} but for the last run values rather
     * than for the total values.
     *
     * @return a string representation of the last counter values
     */
    public String last() {
        StringBuilder ret = new StringBuilder();
        ret.append("l-Active: ").append(lastActiveTimeMillis).append(" ms; ");

        ret.append("l-CPU: ");
        if (isThreadCpuTimeEnabled) {
            ret.append(String.format("%.3f", lastCpuTimeNanos / 1000000.0)).append(" ms; ");
        }
        else {
            ret.append("DISABLED");
        }

        ret.append("l-MemAlloc: ");
        if (isThreadAllocatedMemoryEnabled) {
            ret.append(String.format("%.2f", lastMemBytes / 1024.0)).append(" kB");
        }
        else {
            ret.append("DISABLED");
        }

        return ret.toString();
    }

    public String toString() {
        StringBuilder ret = new StringBuilder();

        long wall = (0 != halted) ? halted - initialStartMillis : System.currentTimeMillis() - initialStartMillis;

        ret.append("Lifetime: ").append(wall).append(" ms; ");
        ret.append("Active: ").append(activeTimeMillis).append(" ms; ");

        ret.append("CPU: ");
        if (isThreadCpuTimeEnabled) {
            ret.append(String.format("%.3f", cpuTimeNanos / 1000000.0)).append(" ms; ");
        }
        else {
            ret.append("DISABLED");
        }

        ret.append("MemAlloc: ");
        if (isThreadAllocatedMemoryEnabled) {
            ret.append(String.format("%.2f", memBytes / 1024.0)).append(" kB");
        }
        else {
            ret.append("DISABLED");
        }

        return ret.toString();
    }

   /**
    * Returns the total CPU time in nanoseconds.
    *
    * @see ThreadMXBean#getCurrentThreadCpuTime()
    * @return the time in nanoseconds
    */
    public long getCpuTimeNanos() {
        return cpuTimeNanos;
    }

   /**
    * Returns the CPU time in user-mode in nanoseconds.
    *
    * @see ThreadMXBean#getCurrentThreadUserTime()
    * @return the time in nanoseconds
    */
     public long getUsrTimeNanos() {
        return usrTimeNanos;
    }

    /**
     * The amount of time, in milliseconds, that the counter
     * has been actively running.
     *
     * @return the time in milliseconds
     */
    public long getActiveTimeMillis() {
        return activeTimeMillis;
    }

    /**
     * The total memory allocated, in bytes.
     *
     * @see ThreadMXBean#getThreadAllocatedBytes(long)
     * @return the total memory allocated, in bytes
     */
    public long getMemBytes() {
        return memBytes;
    }

    /**
     * Like {@link #getCpuTimeNanos()}, but the last run
     * of the counter rather than the total of all runs.
     *
     * @return the time in nanoseconds
     */
    public long getLastCpuTimeNanos() {
        return lastCpuTimeNanos;
    }

    /**
     * Like {@link #getUsrTimeNanos()}, but the last run
     * of the counter rather than the total of all runs.
     *
     * @return the time in nanoseconds
     */
     public long getLastUsrTimeNanos() {
        return lastUsrTimeNanos;
    }

    /**
     * Like {@link #getActiveTimeMillis()}, but the last run
     * of the counter rather than the total of all runs.
     *
     * @return the time in nanoseconds
     */
     public long getLastActiveTimeMillis() {
        return lastActiveTimeMillis;
    }

    /**
     * Like {@link #getMemBytes()}, but the last run
     * of the counter rather than the total of all runs.
     *
     * @return the time in nanoseconds
     */
     public long getLastMemBytes() {
        return lastMemBytes;
    }
}

