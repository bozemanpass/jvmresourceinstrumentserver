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

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * An example to demonstrate how to use a {@link ConcurrentResourceUsageCounter}
 * to track resource consumption across the lifetime of an operation in a
 * multi-threaded, concurrent environment.
 * <p>
 * In response to each GET, the servlet discovers a random quantity
 * of prime numbers.  It may do this using a sieve (more memory intensive),
 * by primality testing (more CPU intensive) or a mixture of both.
 * <p>
 * Rather than process each request linearly, new operations are placed into
 * a work queue, processed by a thread pool.  Random integers are tested for
 * primality.  After each test, if the tally is complete, the operation is
 * closed and the response returned to the client, else the operation is
 * placed back in the queue for more processing.
 * <p>
 * The operation can also be closed if any error is sent by the
 * asynchronous Servlet API {@link AsyncListener#onError(AsyncEvent)}.
 */
@WebServlet(urlPatterns = "/example/*", asyncSupported = true)
public class ExampleServlet extends HttpServlet {
    private final int NUM_PROCS = Runtime.getRuntime().availableProcessors();
    private final ExecutorService executor = Executors.newFixedThreadPool(NUM_PROCS);
    private final LinkedBlockingQueue<MyOperation> workQueue = new LinkedBlockingQueue<>();

    /**
     * Process an HTTP GET request.
     * For this example, all operations begin here.
     *
     * @param req  the HttpServletRequest
     * @param resp the HttpServletResponse
     * @throws ServletException
     * @throws IOException
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //Switch to async processing and initializing our operation
        MyOperation op = new MyOperation(req.startAsync());

        //Offer the operation to the work queue so that our worker threads can start processing it
        if (!workQueue.offer(op)) {
            //if the queue cannot take the operation, close it now
            op.complete(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Initial servlet setup.
     *
     * @throws ServletException
     */
    @Override
    public void init() throws ServletException {
        //Whether JVM measurement of CPU time and memory allocation is available or
        //enabled by default is platform dependent.  Though not required in most cases,
        //attempting to enable it explicitly does not hurt.
        try {
            ResourceUsageCounter.enableMeasurementsInJVM();
        } catch (Throwable t) {
            log("Error enabling resource tracking in JVM!", t);
        }

        //this will submit as many jobs as we have processors, all processing the same work queue
        for (int i = 0; i < NUM_PROCS; i++) {
            executor.submit(() -> {
                while (true) {
                    try {
                        //pull an operation of the queue, blocking if necessary until one is available
                        MyOperation op;
                        try {
                            op = workQueue.take();
                        } catch (InterruptedException ex) {
                            continue;
                        }

                        //if the operation is already closed, skip it
                        if (op.closed.get()) {
                            continue;
                        }

                        //start the resource counter
                        //this is really the heart of the example
                        ResourceUsageCounter t = op.perf.start();
                        try {
                            op.doSomething();
                        } finally {
                            //there isn't a lot to go wrong in this example, but
                            //we want to ensure that no matter what happens, we stop
                            //the counter and add the results
                            op.perf.add(t.halt());
                        }

                        if (op.isFull()) {
                            //if we have all the results we need, finish out the request
                            op.complete();
                        } else {
                            //else offer it back to the queue
                            if (!workQueue.offer(op)) {
                                //if the queue cannot take the operation, close it now
                                //the status code here could be an error, but in this case
                                //we just acknowledge that we got the request, but didn't
                                //really finish it
                                op.complete(HttpServletResponse.SC_ACCEPTED);
                            }
                        }
                    } catch (Throwable t) {
                        log("Error in worker!", t);
                    }
                }

            });
        }
    }

    /**
     * A simple class representing the operation.  In this 'busy work' example, the
     * operation is to discover a random quantity of prime numbers.  It can
     * use a sieve, primality testing on random numbers, or a mix of both.
     */
    private class MyOperation implements AsyncListener {
        //resource usage for the whole op
        final ConcurrentResourceUsageCounter perf = new ConcurrentResourceUsageCounter();
        //hits/misses and resource usage for finding primes
        final PrimeStats sieveStats = new PrimeStats();
        final PrimeStats trialStats = new PrimeStats();

        final String id = UUID.randomUUID().toString();
        final AsyncContext context;
        final Set<Integer> results = Collections.synchronizedSet(new HashSet<Integer>());
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicBoolean full = new AtomicBoolean(false);

        final Random random = new Random();

        MyOperation(AsyncContext context) {
            this.context = context;

            //hook our onError, onComplete, and onTimeout handlers up to the async Servlet API
            this.context.addListener(this);
        }

        /**
         * Find some primes!  In the current implementation, this is not executed
         * by more than one thread at a time.  Since the result set is concurrency-safe,
         * it could be though, with no real harm.  A more advanced implementation would
         * split the prime-finding  into segments, make use of wheels, etc. all allowing
         * for better scaling. Our goal is neither speed nor efficiency though, we just
         * want to use one memory-intensive way (the sieve) and one CPU-intensive way
         * (the primality test) to give the counters something to count.
         */
        void doSomething() {
            //set some arbitrary conditions on when to use our primeSieve
            if (1 == random.nextInt(2000)) {
                ResourceUsageCounter counter = sieveStats.perf.start();

                //find some primes the sieve way (this needs a lot of memory)
                int limit = random.nextInt(7 * 1024 * 1024);
                List<Integer> primes = primeSieve(limit);
                results.addAll(primes);

                sieveStats.hits.addAndGet(primes.size());
                sieveStats.misses.addAndGet(limit - primes.size());
                sieveStats.perf.add(counter.halt());

                //call it good...
                if (primes.size() > 500) {
                    full.set(true);
                }
            } else {
                ResourceUsageCounter counter = trialStats.perf.start();

                //find some primes by testing random numbers (this needs a lot of CPU)
                for (int candidate = random.nextInt(); ; candidate = random.nextInt()) {
                    if (isPrime(candidate)) {
                        results.add(candidate);
                        trialStats.hits.incrementAndGet();
                        break;
                    } else {
                        trialStats.misses.incrementAndGet();
                    }
                }

                trialStats.perf.add(counter.halt());

                if (1 == random.nextInt(5000)) {
                    full.set(true);
                }
            }
        }

        /**
         * Do we have all our results?
         *
         * @return true if we are done, else false
         */
        boolean isFull() {
            return full.get();
        }

        /**
         * Set status to SUCCESS and rite our primes (however many we've got) to the
         * ServletResponse and  close the AsyncContext.
         *
         * @throws IOException
         */
        void complete() throws IOException {
            complete(HttpServletResponse.SC_OK);
        }

        /**
         * Set our status and write our primes (however many we've got) to the
         * ServletResponse and close the AsyncContext.
         *
         * @param status the HTTP status code
         * @throws IOException
         */
        void complete(int status) throws IOException {
            //we can only be closed once
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            try {
                HttpServletResponse response = (HttpServletResponse) context.getResponse();
                if (!response.isCommitted()) {
                    response.setStatus(status);
                }

                PrintWriter w = response.getWriter();
                w.write(results.toString());

                w.write("\n\nTotal: [");
                w.write(perf.toString());

                w.write("]\n\tSieve: [");
                w.write(sieveStats.toString());

                w.write("]\n\tTrial: [");
                w.write(trialStats.toString());
                w.write("]");
            } finally {
                context.complete();
            }
        }

        /**
         * {@link AsyncListener#onComplete(AsyncEvent)} - called when the AsyncContext is completed.
         *
         * @param event
         * @throws IOException
         */
        @Override
        public void onComplete(AsyncEvent event) throws IOException {
            if (!closed.getAndSet(true)) {
                logResult("CLOSED BY CONTAINER");
                return;
            }

            logResult("COMPLETE");
        }

        /**
         * {@link AsyncListener#onError(AsyncEvent)} - called if there is any error
         *
         * @param event
         * @throws IOException
         */
        @Override
        public void onError(AsyncEvent event) throws IOException {
            //close out our part
            try {
                complete(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            } catch (Throwable t) {
                log("ERROR!", t);
            }

            //first log the cause
            log("ERROR", event.getThrowable());

            //now log us
            logResult("ERROR");
        }

        /**
         * {@link AsyncListener#onTimeout(AsyncEvent)} - called when a timeout is encountered
         *
         * @param event
         * @throws IOException
         */
        @Override
        public void onTimeout(AsyncEvent event) throws IOException {
            //close out our part
            try {
                complete(HttpServletResponse.SC_ACCEPTED);
            } catch (Throwable t) {
                log("ERROR!", t);
            }

            logResult("TIMEOUT");
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException {
            //pass
        }

        /**
         * Log the result of our operation.
         *
         * @param tag prefix to our message
         */
        private void logResult(String tag) {
            log(String.format("%s: OP-%s :: Total: (%s)\n\tSieve: [%s]\n\tTrial: [%s]",
                    tag, id, perf, sieveStats, trialStats));
        }
    }

    /**
     * A class to contain some metrics about our prime finding endeavors.
     */
    class PrimeStats {
        final ConcurrentResourceUsageCounter perf = new ConcurrentResourceUsageCounter();
        final AtomicInteger hits = new AtomicInteger();
        final AtomicInteger misses = new AtomicInteger();

        @Override
        public String toString() {
            return String.format("Hits: %d; Misses: %d :: (%s)", hits.get(), misses.get(), perf);
        }
    }

    /**
     * Find all primes <= n
     *
     * @param n
     * @return
     */
    private List<Integer> primeSieve(int n) {
        boolean[] candidates = new boolean[n + 1];

        int found = 0;
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i] = i >= 2) {
                found++;
            }
        }

        for (int i = 2; i < Math.sqrt(candidates.length); i++) {
            if (candidates[i]) {
                for (int j = 0; j < candidates.length; j++) {
                    int k = i * i + j * i;
                    if (k >= candidates.length) {
                        break;
                    }
                    if (candidates[k]) {
                        candidates[k] = false;
                        found--;
                    }
                }
            }
        }

        ArrayList<Integer> ret = new ArrayList<>(found);

        for (int i = 0, j = 0; i < candidates.length; i++) {
            if (candidates[i]) {
                ret.add(i);
            }
        }

        return ret;
    }

    /**
     * A 6k ± 1 primality tester.
     */
    private boolean isPrime(int n) {
        if (n <= 1) {
            return false;
        } else if (n <= 3) {
            return true;
        } else if (0 == n % 2 || 0 == n % 3) {
            return false;
        }
        for (int i = 5; i * i <= n; i += 6) {
            if (0 == n % i || 0 == n % (i + 2)) {
                return false;
            }
        }
        return true;
    }
}
