package com.backuputil.model;

import java.util.concurrent.TimeUnit;

public enum CompressionStrategy{
    GZIP("gzip", ".gz", "Best compatibility, decent compression, always available"),
    BZIP2("bzip2", ".bz2", "Better compression than GZIP, slower — good for infrequent small backups"),
    LZ4("lz4", ".lz4", "Fastest compression — ideal for large databases or frequent backups"),
    ZSTD("zstd", ".zst", "Best balance of speed and compression ratio — recommended default");

    private final String command;
    private final String extension;
    private final String description;

    CompressionStrategy (String command, String extension, String description){
        this.command = command;
        this.extension = extension;
        this.description = description;
    }

    public String getCommand() { return command; }
    public String getExtension() { return extension; }
    public String getDescription() { return description; }

    // use 'which' on UNIX/ 'where' on WINDOWS -more reliable than --version
    public boolean isAvailable(){
        // GZIP is always available - it's part of JAVA'S standard library.
        if (this == GZIP) return true;

        try {
            String checker = System.getProperty("os.name").toLowerCase().contains("win") ?
                    "where" : "which";

            Process process = new ProcessBuilder (checker, command)
                    .redirectErrorStream(true).start();

            // timeout prevents hanging on slow systems;
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished){
                process.destroyForcibly();
                return false;
            }

            return process.exitValue () == 0;
        } catch (Exception e){
            return false;
        }
    }

    // returns null on unknown input so caller can handle it explicitly
    public static CompressionStrategy fromString (String input){
        if (input == null || input.isBlank()) return null;
        for (CompressionStrategy strategy : values()){
            if (strategy.name().equalsIgnoreCase(input.trim())){
                return strategy;
            }
        }
        return null;
    }

}