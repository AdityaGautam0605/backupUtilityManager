package com.backuputil.cli;

import com.backuputil.config.DbConfig;
import com.backuputil.service.DatabaseService;
import com.backuputil.service.impl.MongoService;
import com.backuputil.service.impl.MysqlService;
import com.backuputil.service.impl.PostgresService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import com.backuputil.model.BackupResult;

import java.util.concurrent.Callable;

@Command(name = "backup-util", mixinStandardHelpOptions = true, version = "1.0",
            description = "Database Backup Utility CLI")

public class BackupCommand implements Callable<Integer> {
    @Option(names = {"-o", "--output"}, description = "Directory folder path to store backups", defaultValue = "./backups")
    private String outputDir;

    @Option(names = {"-t", "--type"}, description = "DBMS type (postgres, mysql)", required = true)
    private String dbType;

    @Option(names = {"-H", "--host"}, description = "Database host address", defaultValue = "localhost")
    private String host;

    @Option(names = {"-p", "--port"}, description = "Database port", defaultValue = "5432")
    private int port;

    @Option(names = {"-u", "--user"}, description = "Database username", required = true)
    private String user;

    @Option(names = {"-P", "--password"}, description = "Database password", interactive = true, prompt = "Enter database password: ")
    private String password;

    @Option(names = {"-d", "--database"}, description = "Target database name", required = true)
    private String dbName;

    @Option(names = {"--mock"}, description = "Mock Variable for testing", defaultValue = "false")
    private boolean mock;

    @Override
    public Integer call() throws Exception {
        System.out.println ("Initializing workflow verification...");

        DbConfig config = new DbConfig (host, port, user, password, dbName, mock);

        DatabaseService dbService;

        if ("postgres".equalsIgnoreCase(dbType)){
            dbService = new PostgresService();
        }else if ("mysql".equalsIgnoreCase(dbType)){
            dbService = new MysqlService();
        }else if ("mongo".equalsIgnoreCase(dbType)){
            dbService = new MongoService();
        }
        else{
            System.out.println ("Error: Unsupported Database management system engine: "+ dbType);
            return 1;
        }

        boolean isConnected = dbService.testConnection(config);

        if (isConnected){
            System.out.println("Ready for backup processing pipeline");

            java.io.File directory = new java.io.File(outputDir);
            if (!directory.exists()){
                directory.mkdirs();
            }

           try{
               BackupResult result = dbService.backup(config, outputDir);
               if (result.getStatus() == BackupResult.Status.SUCCESS){
                   System.out.println("Backup completed: "+ result);
                   return 0;
               }else{
                   System.err.println("Backup failed: " + result);
                   return 1;
               }
           }catch (IllegalArgumentException e){
               System.err.println(e.getMessage());
               System.err.println("Workflow aborted safely due to configuration constraints.");
               return 1;
           } catch (Exception e){
               System.err.println ("Unexpected runtime pipeline error: " + e.getMessage());
               return 1;
           }
        }else{
            System.out.println("Workflow terminated early due to verification failure.");
            return 1;
        }
    }
}
