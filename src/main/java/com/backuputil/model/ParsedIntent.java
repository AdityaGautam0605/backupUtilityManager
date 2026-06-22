package com.backuputil.model;

public class ParsedIntent {
    private String dbType;
    private String host;
    private Integer port;
    private String user;
    private String dbName;
    private boolean mock;

    public String getDbType(){ return dbType; }
    public void setDbType(String dbType) {
        this.dbType = dbType;
    }

    public String getHost() { return host; }
    public void setHost(String host){
        this.host = host;
    }

    public Integer getPort(){ return port; }
    public void setPort(Integer port){
        this.port = port;
    }

    public String getUser(){ return user; }
    public void setUser(String user){
        this.user = user;
    }

    public String getDbName(){return dbName; }
    public void setDbName(String dbName){
        this.dbName = dbName;
    }

    public boolean isMock() {return mock; }
    public void setMock(boolean mock){
        this.mock = mock;
    }

}
