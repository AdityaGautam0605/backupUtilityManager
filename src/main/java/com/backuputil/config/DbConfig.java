package com.backuputil.config;

public class DbConfig {
    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String dbName;
    private final boolean mock;

    public DbConfig(String host, int port, String user, String password, String dbName, boolean mock){
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.dbName = dbName;
        this.mock = mock;
    }

    public String getHost(){return host ;}
    public int getPort(){return port;}
    public String getUser(){return user;}
    public String getPassword(){return password;}
    public String getDbName(){return dbName;}

    public boolean isMock(){return mock;}
}
