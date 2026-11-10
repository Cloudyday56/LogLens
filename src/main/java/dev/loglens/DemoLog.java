package dev.loglens;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.UUID;

/** Generates a realistic Spring Boot style log with a database incident in it (--demo). */
final class DemoLog {
    private final Random rnd = new Random(42);
    private final StringBuilder sb = new StringBuilder();
    private long now = LocalDateTime.of(2026, 11, 9, 8, 0, 0).toEpochSecond(ZoneOffset.UTC) * 1000L;

    static String generate(int entries) {
        return new DemoLog().build(Math.max(200, entries));
    }

    private String build(int n) {
        startup();
        boolean crashed = false;
        for (int i = 0; i < n; i++) {
            double p = (double) i / n;
            boolean incident = p >= 0.62 && p < 0.74;
            double wave = 1 + 0.6 * Math.sin(p * Math.PI * 6);
            now += (long) (-Math.log(1 - rnd.nextDouble()) * 420 / wave) + 1;
            if (incident) incident(p);
            else {
                if (p >= 0.74 && !crashed) {
                    crashed = true;
                    crash();
                }
                normal();
            }
        }
        log("INFO", "main", "com.acme.shop.ShopApplication", "Graceful shutdown complete");
        return sb.toString();
    }

    private void startup() {
        log("INFO", "main", "com.acme.shop.ShopApplication", "Starting ShopApplication v2.4.1 using Java 21.0.4 with PID 48211");
        now += 812;
        log("INFO", "main", "com.zaxxer.hikari.HikariDataSource", "HikariPool-1 - Start completed.");
        now += 1650;
        log("INFO", "main", "org.springframework.boot.web.embedded.tomcat.TomcatWebServer", "Tomcat started on port 8080 (http) with context path '/'");
        now += 140;
        log("INFO", "main", "com.acme.shop.ShopApplication", "Started ShopApplication in 3.421 seconds (process running for 3.9)");
    }

    private void normal() {
        double r = rnd.nextDouble();
        String http = "http-nio-8080-exec-" + (1 + rnd.nextInt(10));
        if (r < 0.60) {
            String[] paths = {"/api/orders/" + id(), "/api/products/" + rnd.nextInt(900), "/api/cart", "/api/customers/" + id(), "/api/search?q=shoes"};
            String method = rnd.nextInt(5) == 0 ? "POST" : "GET";
            log("INFO", http, "com.acme.shop.web.RequestLogger", method + " " + paths[rnd.nextInt(paths.length)] + " -> 200 in " + (4 + rnd.nextInt(180)) + " ms");
        } else if (r < 0.72) {
            log("DEBUG", http, "com.acme.shop.cache.CacheManager", "Cache " + (rnd.nextInt(4) == 0 ? "miss" : "hit") + " for key product:" + rnd.nextInt(900));
        } else if (r < 0.80) {
            log("INFO", http, "com.acme.shop.order.OrderService", "Order " + id() + " placed by customer " + id() + " (total $" + (5 + rnd.nextInt(400)) + "." + (10 + rnd.nextInt(89)) + ")");
        } else if (r < 0.85) {
            log("INFO", http, "com.acme.shop.payment.PaymentClient", "Payment " + uuid() + " authorized in " + (80 + rnd.nextInt(900)) + " ms");
        } else if (r < 0.88) {
            log("DEBUG", "scheduler-1", "com.acme.shop.inventory.StockSyncJob", "Synchronized " + rnd.nextInt(60) + " stock records in " + (20 + rnd.nextInt(200)) + " ms");
        } else if (r < 0.90) {
            log("TRACE", "kafka-consumer-1", "com.acme.shop.events.OrderEventListener", "Polled " + rnd.nextInt(12) + " records from partition " + rnd.nextInt(6));
        } else if (r < 0.935) {
            log("WARN", http, "com.acme.shop.web.RateLimiter", "Rate limit approaching for client 10.4." + rnd.nextInt(255) + "." + rnd.nextInt(255) + ": " + (80 + rnd.nextInt(20)) + "/100 requests");
        } else if (r < 0.96) {
            log("WARN", "scheduler-1", "com.acme.shop.inventory.StockService", "Low stock for SKU-" + (1000 + rnd.nextInt(9000)) + ": " + (1 + rnd.nextInt(5)) + " units left");
        } else if (r < 0.975) {
            log("WARN", http, "com.acme.shop.payment.PaymentClient", "Slow response from payment gateway: " + (2000 + rnd.nextInt(4000)) + " ms (threshold 2000 ms)");
        } else if (r < 0.987) {
            log("ERROR", http, "com.acme.shop.order.OrderService", "Failed to process order " + id() + ": validation failed");
            trace("java.lang.IllegalArgumentException: Quantity must be positive for line item " + rnd.nextInt(9),
                    "com.acme.shop.order.OrderValidator.validate(OrderValidator.java:57)",
                    "com.acme.shop.order.OrderService.place(OrderService.java:112)",
                    "com.acme.shop.web.OrderController.create(OrderController.java:48)");
        } else if (r < 0.995) {
            log("ERROR", http, "com.acme.shop.payment.PaymentClient", "Payment " + uuid() + " declined by gateway");
            trace("com.acme.shop.payment.PaymentDeclinedException: Card declined (code 51: insufficient funds)",
                    "com.acme.shop.payment.PaymentClient.charge(PaymentClient.java:141)",
                    "com.acme.shop.order.CheckoutService.pay(CheckoutService.java:77)");
        } else {
            log("ERROR", http, "com.acme.shop.reco.RecommendationService", "Could not build recommendations for customer " + id());
            trace("java.lang.NullPointerException: Cannot invoke \"com.acme.shop.reco.Profile.getTags()\" because \"profile\" is null",
                    "com.acme.shop.reco.RecommendationService.rank(RecommendationService.java:93)",
                    "com.acme.shop.reco.RecommendationService.forCustomer(RecommendationService.java:61)",
                    "com.acme.shop.web.HomeController.index(HomeController.java:35)");
        }
    }

    private void incident(double p) {
        double r = rnd.nextDouble();
        String http = "http-nio-8080-exec-" + (1 + rnd.nextInt(10));
        if (r < 0.42) {
            log("ERROR", http, "com.acme.shop.order.OrderRepository", "Unable to acquire JDBC connection for order " + id());
            trace("org.springframework.dao.DataAccessResourceFailureException: Unable to acquire JDBC Connection",
                    "org.springframework.orm.jpa.vendor.HibernateJpaDialect.convertHibernateAccessException(HibernateJpaDialect.java:256)",
                    "com.acme.shop.order.OrderRepository.findById(OrderRepository.java:44)",
                    "com.acme.shop.order.OrderService.load(OrderService.java:71)");
            cause("java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.",
                    "com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)",
                    "com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:181)");
            cause("java.net.SocketTimeoutException: Read timed out",
                    "java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:288)",
                    "org.postgresql.core.PGStream.receiveChar(PGStream.java:467)");
        } else if (r < 0.57) {
            log("WARN", "HikariPool-1 housekeeper", "com.zaxxer.hikari.pool.HikariPool", "HikariPool-1 - Thread starvation or clock leap detected (housekeeper delta=" + (45 + rnd.nextInt(30)) + "s" + rnd.nextInt(999) + "ms)");
        } else if (r < 0.70) {
            log("ERROR", http, "com.acme.shop.web.RequestLogger", "GET /api/orders/" + id() + " -> 503 in " + (30000 + rnd.nextInt(40)) + " ms");
        } else if (r < 0.75) {
            log("WARN", http, "com.acme.shop.web.CircuitBreaker", "Circuit 'orders-db' is OPEN, rejecting call (failure rate " + (60 + rnd.nextInt(40)) + "%)");
        } else {
            normal();
        }
    }

    private void crash() {
        log("FATAL", "http-nio-8080-exec-7", "com.acme.shop.ShopApplication", "Unrecoverable error, shutting down worker pool");
        trace("java.lang.OutOfMemoryError: Java heap space",
                "java.base/java.util.Arrays.copyOf(Arrays.java:3512)",
                "com.acme.shop.order.RetryQueue.enqueue(RetryQueue.java:39)",
                "com.acme.shop.order.OrderService.retry(OrderService.java:158)");
        now += 47_300; // restart gap
        startup();
        log("INFO", "main", "com.acme.shop.web.CircuitBreaker", "Circuit 'orders-db' is CLOSED, database connectivity restored");
    }

    private void trace(String header, String... frames) {
        sb.append(header).append('\n');
        for (String f : frames) sb.append("\tat ").append(f).append('\n');
        sb.append("\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)\n");
    }

    private void cause(String header, String... frames) {
        sb.append("Caused by: ").append(header).append('\n');
        for (String f : frames) sb.append("\tat ").append(f).append('\n');
        sb.append("\t... ").append(20 + rnd.nextInt(60)).append(" common frames omitted\n");
    }

    private void log(String level, String thread, String logger, String message) {
        LocalDateTime t = Style.dateTime(now);
        sb.append(String.format("%s %02d:%02d:%02d.%03d %5s 48211 --- [%s] %-40s : %s%n",
                t.toLocalDate(), t.getHour(), t.getMinute(), t.getSecond(), Math.floorMod(now, 1000L),
                level, thread, logger, message));
    }

    private int id() {
        return 10_000 + rnd.nextInt(90_000);
    }

    private String uuid() {
        return new UUID(rnd.nextLong(), rnd.nextLong()).toString();
    }
}
