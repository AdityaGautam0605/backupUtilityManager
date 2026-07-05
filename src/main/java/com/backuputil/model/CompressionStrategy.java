package com.backuputil.model;

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

    // Availability = "can we actually build this stream at runtime?". Compression is done
    // through Java libraries (loaded reflectively in the services), NOT the system CLI, so we
    // probe the classpath for the backing class rather than running `which`/`where`. GZIP is
    // always available because it ships with the JDK.
    public boolean isAvailable(){
        if (this == GZIP) return true;

        String backingClass = switch (this){
            case BZIP2 -> "org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream";
            case LZ4   -> "net.jpountz.lz4.LZ4FrameOutputStream";
            case ZSTD  -> "com.github.luben.zstd.ZstdOutputStream";
            default    -> null;
        };
        if (backingClass == null) return false;

        try {
            Class.forName(backingClass);
            return true;
        } catch (ClassNotFoundException e){
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

    // Auto-detect the compression used by a backup file from its extension.
    // Used by the restore pipeline to pick a decompressor. Defaults to GZIP
    // (the backup-side fallback) when the extension is unrecognised.
    public static CompressionStrategy fromFileName (String fileName){
        if (fileName == null) return GZIP;
        String lower = fileName.toLowerCase();
        for (CompressionStrategy strategy : values()){
            if (lower.endsWith(strategy.getExtension())){
                return strategy;
            }
        }
        return GZIP;
    }

}