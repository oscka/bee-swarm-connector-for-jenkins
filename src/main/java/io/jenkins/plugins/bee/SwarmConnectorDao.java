package io.jenkins.plugins.bee;

import jenkins.model.Jenkins;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

public class SwarmConnectorDao {

    public SwarmConnector getOrCreateObject() {
        try {
            final String path = getFilePath();
            return (SwarmConnector) Jenkins.XSTREAM.fromXML(new FileInputStream(path));
        } catch (final Exception e) {
            return new SwarmConnector();
        }
    }

    public void save(SwarmConnector object) throws Exception {
        FileOutputStream outputStream = null;
        OutputStreamWriter outputStreamWriter = null;
        try {
            outputStream = new FileOutputStream(getFilePath());
            outputStreamWriter = new OutputStreamWriter(outputStream, "UTF-8");
            Jenkins.XSTREAM.toXML(object, outputStreamWriter);
        } catch(Exception e) {
            e.printStackTrace();
        } finally {
            if(outputStream != null) {
                outputStream.close();
            }
            if(outputStreamWriter != null) {
                outputStreamWriter.close();
            }
        }
    }

    private static String getFilePath() {
        return Jenkins.get().getRootDir() + System.getProperty("file.separator", "/") + "swarmConnector.xml";
    }
}
