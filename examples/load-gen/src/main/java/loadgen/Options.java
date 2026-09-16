package loadgen;

/** Command line options, all of the form {@code --name=value}. */
public record Options(String bookstore, String orders, double rps, int durationSeconds,
                      long seed, int concurrency, int waitSeconds) {

    public static final String USAGE = """
            load-gen - sends traffic to silk-bookstore and spring-orders

              --bookstore=http://localhost:8081   base URL of silk-bookstore
              --orders=http://localhost:8082      base URL of spring-orders
              --rps=4                             target requests per second, jittered
              --duration=0                        seconds to run; 0 means until Ctrl-C
              --seed=<long>                       random seed; default is the current time
              --concurrency=4                     how many requests may be in flight
              --wait=60                           seconds to wait for the apps' health endpoints
            """;

    public static Options parse(String[] args) {
        String bookstore = "http://localhost:8081";
        String orders = "http://localhost:8082";
        double rps = 4;
        int duration = 0;
        long seed = System.nanoTime();
        int concurrency = 4;
        int wait = 60;

        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                System.out.print(USAGE);
                System.exit(0);
            }
            int eq = arg.indexOf('=');
            if (!arg.startsWith("--") || eq < 0) {
                throw new IllegalArgumentException("unrecognised argument: " + arg + "\n" + USAGE);
            }
            String name = arg.substring(2, eq);
            String value = arg.substring(eq + 1);
            switch (name) {
                case "bookstore" -> bookstore = trimSlash(value);
                case "orders" -> orders = trimSlash(value);
                case "rps" -> rps = Double.parseDouble(value);
                case "duration" -> duration = Integer.parseInt(value);
                case "seed" -> seed = Long.parseLong(value);
                case "concurrency" -> concurrency = Integer.parseInt(value);
                case "wait" -> wait = Integer.parseInt(value);
                default -> throw new IllegalArgumentException("unknown option: --" + name + "\n" + USAGE);
            }
        }
        if (rps <= 0) {
            throw new IllegalArgumentException("--rps must be greater than 0");
        }
        return new Options(bookstore, orders, rps, Math.max(duration, 0), seed,
                Math.clamp(concurrency, 1, 64), Math.max(wait, 0));
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public long intervalMillis() {
        return Math.max(1, Math.round(1000.0 / rps));
    }
}
