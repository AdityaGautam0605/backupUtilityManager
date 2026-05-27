package org.example;

import com.backuputil.cli.BackupCommand;
import picocli.CommandLine;

public class Main {
    public static void main(String[] args){
        int exitCode = new CommandLine (new BackupCommand()).execute(args);
        System.exit(exitCode);
    }
}
