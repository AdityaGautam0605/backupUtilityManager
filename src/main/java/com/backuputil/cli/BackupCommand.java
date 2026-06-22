package com.backuputil.cli;

import com.backuputil.ai.CompressionAdvisor;
import com.backuputil.config.AppConfig;
import com.backuputil.config.DbConfig;
import com.backuputil.model.CompressionStrategy;
import com.backuputil.service.DatabaseService;
import com.backuputil.service.impl.MongoService;
import com.backuputil.service.impl.MysqlService;
import com.backuputil.service.impl.PostgresService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import com.backuputil.model.BackupResult;
import com.backuputil.ai.RootCauseAnalyser;
import com.backuputil.ai.NaturalLanguageParser;
import com.backuputil.model.ParsedIntent;

import java.util.concurrent.Callable;


@Command(name = "backup-util", mixinStandardHelpOptions = true, version = "1.0",
            description = "Database Backup Utility CLI")

public class BackupCommand implements Callable<Integer> {
    @Option(names = {"-o", "--output"}, description = "Directory folder path to store backups", defaultValue = "./backups")
    private String outputDir;

    @Option(names = {"-t", "--type"}, description = "DBMS type (postgres, mysql)")
    private String dbType;

    @Option(names = {"-H", "--host"}, description = "Database host address", defaultValue = "localhost")
    private String host;

    @Option(names = {"-p", "--port"}, description = "Database port", defaultValue = "5432")
    private int port;

    @Option(names = {"-u", "--user"}, description = "Database username")
    private String user;

    @Option(names = {"-P", "--password"}, description = "Database password", interactive = true, prompt = "Enter database password: ")
    private String password;

    @Option(names = {"-d", "--database"}, description = "Target database name")
    private String dbName;

    @Option(names = {"--mock"}, description = "Mock Variable for testing", defaultValue = "false")
    private boolean mock;

    @Option(names = {"--backup-frequency"}, description = "How many times per day you run backups", defaultValue = "1")
    private int backupFrequencyPerDay;

    @Option(names = {"--nl"}, description = "Describe what you want in natural language, e.g. \"backup my postgres database called shop_db on localhost\"")
    private String naturalLanguageInput;

    @Override
    public Integer call() throws Exception {
        AppConfig.getInstance().printStatus();

        if (naturalLanguageInput != null && !naturalLanguageInput.isBlank()) {
            resolveFromNaturalLanguage();
        }

        if (dbType == null || dbType.isBlank()) {
            dbType = promptForValue("Enter DBMS type (postgres/mysql/mongo): ");
        }
        if (user == null || user.isBlank()) {
            user = promptForValue("Enter database username: ");
        }
        if (dbName == null || dbName.isBlank()) {
            dbName = promptForValue("Enter target database name: ");
        }
        RootCauseAnalyser analyser = new RootCauseAnalyser();
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
            final CompressionAdvisor compressionAdvisor = new CompressionAdvisor();
            CompressionStrategy strategy = compressionAdvisor.adviseAndConfirm (config, backupFrequencyPerDay);

           try{
               BackupResult result = dbService.backup(config, outputDir, strategy);
               String analysis = analyser.analyse(result);
               if (result.getStatus() == BackupResult.Status.SUCCESS){
                   System.out.println("Backup completed: "+ result);
                   System.out.println("\n[AI Analysis] " + analysis);
                   return 0;
               }else{
                   System.err.println("Backup failed: " + result);
                   System.err.println("\n[AI Analysis] " + analysis);
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

    private void resolveFromNaturalLanguage() {
        System.out.println("[NL Parser] Interpreting: \"" + naturalLanguageInput + "\"");
        NaturalLanguageParser parser = new NaturalLanguageParser();
        ParsedIntent intent = parser.parse(naturalLanguageInput);

        if (intent.getDbType() != null) { dbType = intent.getDbType(); System.out.println("[NL Parser] Detected DB type: " + dbType); }
        if (intent.getHost() != null)   { host = intent.getHost();     System.out.println("[NL Parser] Detected host: " + host); }
        if (intent.getPort() != null)   { port = intent.getPort();     System.out.println("[NL Parser] Detected port: " + port); }
        if (intent.getUser() != null)   { user = intent.getUser();     System.out.println("[NL Parser] Detected user: " + user); }
        if (intent.getDbName() != null) { dbName = intent.getDbName(); System.out.println("[NL Parser] Detected database: " + dbName); }
        if (intent.isMock())            { mock = true;                 System.out.println("[NL Parser] Mock mode detected"); }

        System.out.println("[NL Parser] Note: passwords are never parsed from natural language — you'll be prompted separately.\n");
    }

    private String promptForValue(String promptText) {
        java.io.Console console = System.console();
        if (console != null) {
            return console.readLine(promptText);
        }
        System.out.print(promptText);
        return new java.util.Scanner(System.in).nextLine();
    }
}
