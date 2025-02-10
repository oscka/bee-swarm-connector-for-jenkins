package io.jenkins.plugins.bee;

import java.io.PrintWriter;
import java.io.StringWriter;

public class SwarmLog {

    private String logs;

    public String getLogs() {
        return logs;
    }
    public void setLogs(String logs) {
        this.logs = logs;
    }

    public static String getPrintStackTrace(Exception e) {

        StringWriter errors = new StringWriter();
        e.printStackTrace(new PrintWriter(errors));
        return errors.toString();

    }
}
