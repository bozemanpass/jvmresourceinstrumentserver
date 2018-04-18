Overview
==========

This repository holds the example code for the article: [Analyzing Performance in a JVM Server at Scale by Self-Measurement](https://www.bozemanpass.com/analyzing-performance-in-a-jvm-server-at-scale-by-self-measurement/)

It performs some "busy work" of finding primes as a way to demonstrate a few performance tracing and self-measurement techniques possible with the standard JVM.

The [ResourceUsageCounter](https://github.com/bozemanpass/jvmresourceinstrumentserver/blob/master/src/main/java/com/bozemanpass/example/performance/instrumentation/ResourceUsageCounter.java) and [ConcurrentResourceUsageCounter](https://github.com/bozemanpass/jvmresourceinstrumentserver/blob/master/src/main/java/com/bozemanpass/example/performance/instrumentation/ConcurrentResourceUsageCounter.java) are the key classes.

Building
==========

[Gradle](https://gradle.org/) and Java 8 (or higher) are required to build.

To build run:

    $ gradle war

The build output is located at 'build/libs'.  For example:

    $ ls build/libs

    JvmResourceInstrumentServer-1.0-SNAPSHOT.war

Running
===========

Java 8+ and a servlet-api 3.1 compatible servlet container are required to run.

[Tomcat 8](https://tomcat.apache.org/) or higher is a good choice, but any servlet-api compatible container should work.  If using Tomcat, you can deploy by using the 'manger' app, or by copying the WAR package to '$CATALINA_HOME/webapps'


Unless you change the context when deploying, the initial portion of the URL will match the WAR name.  The ExampleServlet is configured to handle 'urlPatterns = "/example/\*"'.  Combined, this means you should use a URL like:

    $ wget -q -O - http://localhost:8080/JvmResourceInstrumentServer-1.0-SNAPSHOT/example
